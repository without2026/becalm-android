package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.ui.components.CommitmentWire
import com.becalm.android.domain.commitment.CommitmentManualValidator
import com.becalm.android.domain.commitment.CommitmentManualValidator.Field
import com.becalm.android.domain.commitment.CommitmentManualValidator.ValidationResult
import com.becalm.android.domain.commitment.ManualCommitmentDraft
import com.becalm.android.ui.navigation.BecalmRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Immutable UI state for the EDIT-007 supersede-only [CommitmentCreateSheet].
 *
 * @property supersedeSource The old row being superseded. The quote + source
 *   columns render from it verbatim; saving is blocked until this is loaded.
 * @property draft Raw form state handed to [CommitmentManualValidator].
 * @property fieldErrors Per-field localised error message resources surfaced adjacent
 *   to each input.
 * @property saving True while the repository write is in flight — the save
 *   button shows a spinner and disables itself.
 * @property saved Flips true on repository success; the sheet dismisses when
 *   this transitions.
 * @property saveError One-shot human-readable message displayed above the
 *   action row; cleared by [CommitmentCreateViewModel.clearSaveError].
 */
public data class CreateUiState(
    val supersedeSource: CommitmentEntity? = null,
    val draft: ManualCommitmentDraft = ManualCommitmentDraft(
        title = "",
        direction = CommitmentWire.DIRECTION_GIVE,
        quote = "",
        counterpartyRef = null,
        dueAtMillis = null,
        dueHint = null,
        dueIsApproximate = false,
    ),
    val fieldErrors: Map<Field, CommitmentText> = emptyMap(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveError: CommitmentText? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentCreateVM"

/**
 * ViewModel for the supersede-only [CommitmentCreateSheet] (EDIT-007).
 *
 * Reads `supersedeOf` from [SavedStateHandle], loads the referenced commitment
 * via [CommitmentRepository.observeById], and seeds [CreateUiState.supersedeSource]
 * so the sheet can render the old row's quote as read-only.
 *
 * Save path ([onSave]):
 * 1. Copy the old row's quote into the draft so the validator + repository
 *    see the evidentiary string verbatim.
 * 2. Run [CommitmentManualValidator.validate]; surface field errors if any.
 * 3. Normalise via [CommitmentManualValidator.normalise] and call
 *    [CommitmentRepository.saveManualCommitment]. On success flip [CreateUiState.saved].
 *
 * @param commitmentRepository Source of truth for the optional supersede row
 *   and target of the create write.
 * @param savedStateHandle Navigation argument carrier; reads
 *   [BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF].
 * @param logger Structured log sink.
 */
@HiltViewModel
public class CommitmentCreateViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val userPrefsStore: com.becalm.android.data.local.datastore.UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
) : ViewModel() {

    private val supersedeOf: String? =
        savedStateHandle.get<String>(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF)
            ?.takeIf { it.isNotBlank() }

    private val _uiState: MutableStateFlow<CreateUiState> = MutableStateFlow(CreateUiState())

    /** Current UI state. */
    public val uiState: StateFlow<CreateUiState> = _uiState.asStateFlow()

    private val _dismiss: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /**
     * One-shot dismiss event emitted on successful save. The Composable
     * collects this and pops the back-stack. Cancel is a pure UI signal so
     * the VM does not emit for it.
     */
    public val dismiss: SharedFlow<Unit> = _dismiss.asSharedFlow()

    init {
        if (supersedeOf != null) {
            loadSupersedeSource(supersedeOf)
        }
    }

    // ─── Public actions ───────────────────────────────────────────────────────

    public fun onTitleChange(value: String) {
        _uiState.update {
            it.copy(
                draft = it.draft.copy(title = value),
                fieldErrors = it.fieldErrors - Field.TITLE,
            )
        }
    }

    public fun onDirectionChange(value: String) {
        _uiState.update {
            it.copy(
                draft = it.draft.copy(direction = value),
                fieldErrors = it.fieldErrors - Field.DIRECTION,
            )
        }
    }

    public fun onCounterpartyRefChange(value: String) {
        _uiState.update {
            it.copy(
                draft = it.draft.copy(counterpartyRef = value.ifBlank { null }),
                fieldErrors = it.fieldErrors - Field.PERSON_REF,
            )
        }
    }

    public fun onDueAtMillisChange(value: Long?) {
        _uiState.update { it.copy(draft = it.draft.copy(dueAtMillis = value)) }
    }

    public fun onDueHintChange(value: String) {
        _uiState.update {
            it.copy(draft = it.draft.copy(dueHint = value.ifBlank { null }))
        }
    }

    public fun onApproxChange(value: Boolean) {
        _uiState.update { it.copy(draft = it.draft.copy(dueIsApproximate = value)) }
    }

    /**
     * Validates the draft, normalises it, and writes via
     * [CommitmentRepository.saveManualCommitment]. The old row's quote is
     * copied into the draft before validation so the evidentiary column is
     * preserved verbatim (spec EDIT-007 invariant 1).
     */
    public fun onSave() {
        val snap = _uiState.value
        val supersedeTarget = supersedeOf
        if (supersedeTarget == null) {
            _uiState.update {
                it.copy(saveError = CommitmentSaveErrorFormatter.SUPERSEDE_SOURCE_NOT_FOUND)
            }
            return
        }
        val effectiveDraft = CommitmentCreateProjector.effectiveDraft(snap)
        if (effectiveDraft == null) {
            _uiState.update {
                it.copy(saveError = CommitmentSaveErrorFormatter.SUPERSEDE_SOURCE_NOT_FOUND)
            }
            return
        }
        when (val v = CommitmentManualValidator.validate(effectiveDraft)) {
            is ValidationResult.Err -> {
                _uiState.update {
                    it.copy(fieldErrors = v.fieldErrors.mapValues { entry ->
                        manualValidationErrorText(entry.value)
                    })
                }
                return
            }
            is ValidationResult.Ok -> Unit
        }
        val input = CommitmentManualValidator.normalise(effectiveDraft)

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null) }
            val result = commitmentRepository.saveManualCommitment(
                input = input,
                supersedeOf = supersedeTarget,
            )
            _uiState.update { it.copy(saving = false) }
            when (result) {
                is BecalmResult.Success -> {
                    _uiState.update { it.copy(saved = true) }
                    _dismiss.tryEmit(Unit)
                }
                is BecalmResult.Failure -> {
                    logger.e(TAG, "saveManualCommitment failed error=${result.error}")
                    _uiState.update {
                        it.copy(saveError = CommitmentCreateProjector.saveError(result.error))
                    }
                }
            }
        }
    }

    /** Clears a one-shot [CreateUiState.saveError] after the Composable displays it. */
    public fun clearSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun loadSupersedeSource(id: String) {
        viewModelScope.launch {
            // User-scoped read so a deep-linked supersede id cannot resolve to
            // another account's row after account switching on the shared
            // Room DB (data-model.yml:476 cross-account leak guard).
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId.isNullOrBlank()) {
                logger.w(TAG, "supersede source — no signed-in user id=${hashId(id)}")
                return@launch
            }
            val entity = commitmentRepository.observeByIdForUser(userId, id).firstOrNull()
            if (entity == null) {
                logger.w(TAG, "supersede source not found id=${hashId(id)}")
                // Leave supersedeSource null; the UI shows an empty read-only
                // quote card and onSave surfaces the explicit error.
                return@launch
            }
            _uiState.update { state -> CommitmentCreateProjector.applySupersedeSource(state, entity) }
        }
    }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())
}
