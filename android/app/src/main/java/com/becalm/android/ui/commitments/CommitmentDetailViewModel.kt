package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerAliasDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.MeetingSpeakerAliasEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.navigation.BecalmRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

public enum class CommitmentSheetAction {
    REMIND,
    FOLLOW_UP,
    COMPLETE,
    CANCEL,
}

public data class CommitmentDetailActionState(
    val availableActions: Set<CommitmentSheetAction> = emptySet(),
    val editEnabled: Boolean = false,
)

public data class CommitmentSourcePresentation(
    val isManual: Boolean = false,
    val sourceType: String? = null,
    val sourceTitle: String? = null,
    val sourceOccurredAt: Instant? = null,
    val sourceLabel: CommitmentText? = null,
)

public data class CommitmentHistoryPresentation(
    val lastEditedAt: Instant? = null,
    val disputeRaisedAt: Instant? = null,
    val showSupersedeLink: Boolean = false,
)

public data class MeetingTranscriptPresentation(
    val rawEventId: String,
    val originalBodyText: String,
    val bodyText: String,
    val speakerIds: List<String>,
    val aliases: Map<String, String>,
    val truncated: Boolean,
)

public sealed interface CommitmentDetailEffect {
    public data class OpenEdit(val commitmentId: String) : CommitmentDetailEffect
}

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Immutable snapshot of [CommitmentDetailSheet] UI.
 *
 * @property entity Backing commitment row, or null while loading / on soft-delete.
 * @property counterpartyDisplayName Resolved display name for the counterparty (CMT-001
 *   fallback chain: `enrichment.displayName` → `nickname` → `counterpartyRef` →
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
    val quote: String = "",
    val counterpartyDisplayName: String? = null,
    val actionState: CommitmentState = CommitmentState.PENDING,
    val source: CommitmentSourcePresentation = CommitmentSourcePresentation(),
    val actionButtons: CommitmentDetailActionState = CommitmentDetailActionState(),
    val history: CommitmentHistoryPresentation = CommitmentHistoryPresentation(),
    val meetingTranscript: MeetingTranscriptPresentation? = null,
    val loading: Boolean = true,
    val error: UiMessage? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentDetailVM"

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
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val meetingSpeakerAliasDao: MeetingSpeakerAliasDao,
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val userPrefsStore: UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val id: String =
        savedStateHandle.get<String>(BecalmRoute.CommitmentDetail.ARG_ID).orEmpty()

    private val _uiState: MutableStateFlow<DetailUiState> = MutableStateFlow(DetailUiState())

    /** Current UI state; starts loading and settles on the first Room emission. */
    public val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    private val _effects: MutableSharedFlow<CommitmentDetailEffect> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    public val effects: SharedFlow<CommitmentDetailEffect> = _effects.asSharedFlow()

    init {
        if (id.isEmpty()) {
            _uiState.update {
                it.copy(loading = false, error = UiMessage.resource(R.string.commitment_detail_error_missing_id))
            }
        } else {
            observe()
        }
    }

    public fun onEditClick() {
        if (_uiState.value.actionButtons.editEnabled) {
            _effects.tryEmit(CommitmentDetailEffect.OpenEdit(id))
        }
    }

    public fun onSpeakerAliasChange(speakerId: String, displayName: String) {
        val current = _uiState.value.meetingTranscript ?: return
        val normalizedSpeakerId = speakerId.trim()
        val normalizedDisplayName = displayName.trim().ifBlank { normalizedSpeakerId }
        if (normalizedSpeakerId !in current.speakerIds) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull() ?: return@launch
            meetingSpeakerAliasDao.upsert(
                MeetingSpeakerAliasEntity(
                    userId = userId,
                    rawEventId = current.rawEventId,
                    speakerId = normalizedSpeakerId,
                    displayName = normalizedDisplayName,
                    updatedAt = kotlinx.datetime.Clock.System.now(),
                ),
            )
            _uiState.update { state ->
                val transcript = state.meetingTranscript ?: return@update state
                val aliases = transcript.aliases + (normalizedSpeakerId to normalizedDisplayName)
                state.copy(
                    meetingTranscript = transcript.copy(
                        aliases = aliases,
                        bodyText = applySpeakerAliases(transcript.originalBodyText, aliases),
                    ),
                )
            }
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
            userPrefsStore.observeCurrentUserId()
                .flatMapLatest { userId ->
                    if (userId.isNullOrBlank()) {
                        flowOf(CommitmentDetailProjector.buildMissingState())
                    } else {
                        combine(
                            commitmentRepository.observeByIdForUser(userId, id),
                            personEnrichmentRepository.observeEnrichmentMap(),
                        ) { entity, enrichment ->
                            if (entity == null) {
                                CommitmentDetailProjector.buildMissingState()
                            } else {
                                CommitmentDetailProjector.buildLoadedState(
                                    entity = entity,
                                    enrichment = enrichment,
                                    meetingTranscript = loadMeetingTranscript(userId, entity),
                                )
                            }
                        }
                    }
                }
                .distinctUntilChanged()
                .flowOn(ioDispatcher)
                .catch { e ->
                    logger.e(TAG, "observe failed id=${hashId(id)}", e)
                    _uiState.update {
                        it.copy(loading = false, error = UiMessage.resource(R.string.commitment_detail_error_load_failed))
                    }
                }
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    private suspend fun loadMeetingTranscript(
        userId: String,
        entity: CommitmentEntity,
    ): MeetingTranscriptPresentation? {
        if (entity.sourceType != SourceType.MEETING || entity.itemType != CommitmentItemType.SCHEDULE) return null
        val sourceRef = entity.sourceRef ?: return null
        val rawEvent = rawIngestionEventDao.findBySourceRefsForUser(userId, listOf(sourceRef))
            .firstOrNull { it.sourceType == SourceType.MEETING }
            ?: return null
        val archived = sourceArtifactRepository.findMarkdownOriginal(userId, rawEvent.id) ?: return null
        val markdown = archived.markdown?.takeIf { it.isNotBlank() } ?: return null
        val aliases = meetingSpeakerAliasDao.findForRawEvent(userId, rawEvent.id)
            .associate { it.speakerId to it.displayName }
        val speakerIds = extractSpeakerIds(markdown)
        return MeetingTranscriptPresentation(
            rawEventId = rawEvent.id,
            originalBodyText = markdown,
            bodyText = applySpeakerAliases(markdown, aliases),
            speakerIds = speakerIds,
            aliases = aliases,
            truncated = archived.markdownTruncated,
        )
    }

    private fun extractSpeakerIds(markdown: String): List<String> =
        SPEAKER_ID_REGEX.findAll(markdown)
            .map { it.value }
            .distinct()
            .sorted()
            .toList()

    private fun applySpeakerAliases(markdown: String, aliases: Map<String, String>): String =
        aliases.entries.fold(markdown) { acc, (speakerId, alias) ->
            acc.replace(Regex("\\b${Regex.escape(speakerId)}\\b"), alias)
        }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())

    private companion object {
        private val SPEAKER_ID_REGEX = Regex("\\bSPEAKER_\\d+\\b")
    }
}
