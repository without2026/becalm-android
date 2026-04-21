package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.navigation.BecalmRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of [CommitmentDetailSheet] UI.
 *
 * @property entity Backing commitment row, or null while loading / on soft-delete.
 * @property counterpartyDisplayName Resolved display name for the counterparty (CMT-001
 *   fallback chain: `enrichment.displayName` → `nickname` → `personRef` →
 *   `counterpartyRaw`). Null when nothing resolves.
 * @property actionState Typed enum mirror of [entity.actionState]; exposed so the
 *   composable can drive button enable/disable state without parsing wire strings.
 * @property loading True until the first emission from [CommitmentRepository.observeById]
 *   lands. The sheet renders a spinner during this window.
 * @property error Non-null when the row is missing or soft-deleted ("삭제된 약속") or
 *   the upstream flow threw.
 */
public data class DetailUiState(
    val entity: CommitmentEntity? = null,
    val counterpartyDisplayName: String? = null,
    val actionState: CommitmentState = CommitmentState.PENDING,
    val loading: Boolean = true,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentDetailVM"
private const val COUNTERPARTY_DISPLAY_MAX = 30

/**
 * ViewModel for [CommitmentDetailSheet] (CMT-003 + EDIT-008 + MAN-004).
 *
 * Subscribes to [CommitmentRepository.observeById] for the commitment identified by
 * the `id` nav argument and joins it against [PersonEnrichmentRepository.observeEnrichmentMap]
 * so the counterparty display name is resolved through the PIPA-scoped on-device
 * enrichment table.
 *
 * No mutating actions — state transitions remain the responsibility of
 * [CommitmentManagementViewModel]. The sheet composable holds a second
 * `CommitmentManagementViewModel` handle to call `onRemind/onFollowUp/onComplete/onCancel`;
 * both VMs talk to the same singleton repository so the detached instance is safe.
 *
 * @param commitmentRepository Source of the reactive commitment row.
 * @param personEnrichmentRepository PIPA-scoped on-device enrichment map source.
 * @param savedStateHandle Navigation argument carrier; expects
 *   [BecalmRoute.CommitmentDetail.ARG_ID].
 * @param logger Structured log sink.
 */
@HiltViewModel
public class CommitmentDetailViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
) : ViewModel() {

    private val id: String =
        savedStateHandle.get<String>(BecalmRoute.CommitmentDetail.ARG_ID).orEmpty()

    private val _uiState: MutableStateFlow<DetailUiState> = MutableStateFlow(DetailUiState())

    /** Current UI state; starts loading and settles on the first Room emission. */
    public val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        if (id.isEmpty()) {
            _uiState.update {
                it.copy(loading = false, error = "commitment id missing")
            }
        } else {
            observe()
        }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun observe() {
        viewModelScope.launch {
            // User-scoped observation: the deep link / nav argument carries only
            // the commitment id, but the shared Room DB could otherwise surface
            // another account's row after account switching. Resolving the user
            // id here and switching to observeByIdForUser closes that gap
            // (cross-account leak guard, data-model.yml:476).
            val commitmentFlow = userPrefsStore.observeCurrentUserId().flatMapLatest { userId ->
                if (userId.isNullOrBlank()) flowOf(null)
                else commitmentRepository.observeByIdForUser(userId, id)
            }
            combine(
                commitmentFlow,
                personEnrichmentRepository.observeEnrichmentMap(),
            ) { entity, enrichment -> entity to enrichment }
                .catch { e ->
                    logger.e(TAG, "observe failed id=${hashId(id)}", e)
                    _uiState.update {
                        it.copy(loading = false, error = e.message ?: "load failed")
                    }
                }
                .collect { (entity, enrichment) ->
                    if (entity == null) {
                        // Row absent or soft-deleted — surface the "삭제된 약속" empty
                        // state per plan §5.3. Loading flips to false so the sheet
                        // swaps out the spinner for the empty message immediately.
                        _uiState.update {
                            it.copy(
                                entity = null,
                                counterpartyDisplayName = null,
                                loading = false,
                                error = EMPTY_ERROR_KEY,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                entity = entity,
                                counterpartyDisplayName =
                                    resolveCounterpartyDisplay(entity, enrichment),
                                actionState = CommitmentState.fromWire(entity.actionState),
                                loading = false,
                                error = null,
                            )
                        }
                    }
                }
        }
    }

    /**
     * CMT-001 counterparty display resolution — mirrors the fallback chain used by
     * [CommitmentManagementViewModel.resolveCounterpartyDisplay] so the detail sheet
     * and the card show the same label. Intentionally duplicated rather than shared
     * via a UseCase because the rule is 8 lines and the two VMs will diverge as
     * edit-flow-specific logic lands.
     */
    private fun resolveCounterpartyDisplay(
        commitment: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): String? {
        val ref = commitment.personRef
        return if (ref != null) {
            val hit = enrichment[ref]
            hit?.displayName ?: hit?.nickname ?: ref
        } else {
            commitment.counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)
        }
    }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())

    public companion object {
        /**
         * Sentinel string emitted in [DetailUiState.error] when the commitment is
         * missing or soft-deleted. The composable substitutes the localized string
         * resource `commitment_detail_empty_error` when this exact value is observed;
         * kept as a stable key so the VM stays Context-free.
         */
        public const val EMPTY_ERROR_KEY: String = "deleted-commitment"
    }
}
