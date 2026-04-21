package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.repository.CommitmentRepository
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

// ─── Mode ─────────────────────────────────────────────────────────────────────

/**
 * Entry mode for [CommitmentCreateSheet].
 *
 * - [MANUAL] — plain manual add from the management screen FAB
 *   (`supersedeOf = null`). The user types every field from scratch.
 * - [SUPERSEDE] — EDIT-007 "이건 다른 약속입니다" path. The quote column is
 *   rendered as read-only text seeded from the old row; on save the old row
 *   is soft-deleted and the new row carries `supersedes_commitment_id = oldId`.
 *
 * Both modes ultimately call
 * [CommitmentRepository.saveManualCommitment] with
 * `source_type = 'manual'` — the mode only affects which inputs are editable
 * and which `supersedeOf` is passed.
 */
public enum class CommitmentCreateMode { MANUAL, SUPERSEDE }

// ─── UI state ─────────────────────────────────────────────────────────────────

/**
 * Immutable UI state for [CommitmentCreateSheet] (MAN-001..006 + EDIT-007).
 *
 * @property mode Drives sheet header copy and quote-field editability.
 * @property supersedeSource In [CommitmentCreateMode.SUPERSEDE] mode this is
 *   the old row being superseded — the quote + source columns render from it
 *   verbatim. Null in [CommitmentCreateMode.MANUAL] mode.
 * @property draft Raw form state handed to [CommitmentManualValidator].
 * @property fieldErrors Per-field localised error messages surfaced adjacent
 *   to each input.
 * @property saving True while the repository write is in flight — the save
 *   button shows a spinner and disables itself.
 * @property saved Flips true on repository success; the sheet dismisses when
 *   this transitions.
 * @property saveError One-shot human-readable message displayed above the
 *   action row; cleared by [CommitmentCreateViewModel.clearSaveError].
 */
public data class CreateUiState(
    val mode: CommitmentCreateMode = CommitmentCreateMode.MANUAL,
    val supersedeSource: CommitmentEntity? = null,
    val draft: ManualCommitmentDraft = ManualCommitmentDraft(
        title = "",
        direction = "give",
        quote = "",
        personRef = null,
        dueAtMillis = null,
        dueHint = null,
        dueIsApproximate = false,
    ),
    val fieldErrors: Map<Field, String> = emptyMap(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val saveError: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentCreateVM"

/**
 * ViewModel for [CommitmentCreateSheet] (MAN-001..006 + EDIT-007).
 *
 * Reads the optional `supersedeOf` nav argument from [SavedStateHandle]; when
 * present the VM loads the referenced commitment via
 * [CommitmentRepository.observeById] and seeds [CreateUiState.supersedeSource]
 * so the sheet can render the old row's quote as read-only. The mode is
 * [CommitmentCreateMode.SUPERSEDE] in that case, else [CommitmentCreateMode.MANUAL].
 *
 * Save path ([onSave]):
 * 1. For [CommitmentCreateMode.SUPERSEDE] copy the old row's quote into the
 *    draft so the validator + repository see the evidentiary string verbatim.
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
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
) : ViewModel() {

    private val supersedeOf: String? =
        savedStateHandle.get<String>(BecalmRoute.CommitmentCreate.ARG_SUPERSEDE_OF)
            ?.takeIf { it.isNotBlank() }

    private val _uiState: MutableStateFlow<CreateUiState> = MutableStateFlow(
        CreateUiState(
            mode = if (supersedeOf == null) {
                CommitmentCreateMode.MANUAL
            } else {
                CommitmentCreateMode.SUPERSEDE
            },
        ),
    )

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

    public fun onQuoteChange(value: String) {
        // In SUPERSEDE mode the quote is read-only by spec — the UI ignores
        // keystrokes, but we also drop the write defensively in case a future
        // caller bypasses the composable.
        if (_uiState.value.mode == CommitmentCreateMode.SUPERSEDE) return
        _uiState.update {
            it.copy(
                draft = it.draft.copy(quote = value),
                fieldErrors = it.fieldErrors - Field.QUOTE,
            )
        }
    }

    public fun onPersonRefChange(value: String) {
        _uiState.update {
            it.copy(
                draft = it.draft.copy(personRef = value.ifBlank { null }),
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
     * MAN-006: validates the draft, normalises it, and writes via
     * [CommitmentRepository.saveManualCommitment]. In SUPERSEDE mode the
     * old row's quote is copied into the draft before validation so the
     * evidentiary column is preserved verbatim (spec EDIT-007 invariant 1).
     */
    public fun onSave() {
        val snap = _uiState.value
        val effectiveDraft = when (snap.mode) {
            CommitmentCreateMode.MANUAL -> snap.draft
            CommitmentCreateMode.SUPERSEDE -> {
                val source = snap.supersedeSource
                if (source == null) {
                    _uiState.update {
                        it.copy(saveError = "원문 commitment를 찾지 못했습니다")
                    }
                    return
                }
                // Preserve evidentiary quote verbatim (EDIT-007 invariant 1).
                snap.draft.copy(quote = source.quote)
            }
        }
        when (val v = CommitmentManualValidator.validate(effectiveDraft)) {
            is ValidationResult.Err -> {
                _uiState.update { it.copy(fieldErrors = v.fieldErrors) }
                return
            }
            is ValidationResult.Ok -> Unit
        }
        val input = CommitmentManualValidator.normalise(effectiveDraft)

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null) }
            val result = commitmentRepository.saveManualCommitment(
                input = input,
                supersedeOf = supersedeOf,
            )
            _uiState.update { it.copy(saving = false) }
            when (result) {
                is BecalmResult.Success -> {
                    _uiState.update { it.copy(saved = true) }
                    _dismiss.tryEmit(Unit)
                }
                is BecalmResult.Failure -> {
                    logger.e(TAG, "saveManualCommitment failed error=${result.error}")
                    _uiState.update { it.copy(saveError = result.error.toSaveError()) }
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
            val entity = commitmentRepository.observeById(id).firstOrNull()
            if (entity == null) {
                logger.w(TAG, "supersede source not found id=${hashId(id)}")
                // Leave supersedeSource null; the UI shows an empty read-only
                // quote card and onSave surfaces the explicit error.
                return@launch
            }
            _uiState.update {
                it.copy(
                    supersedeSource = entity,
                    // Seed editable fields from the old row so the user's
                    // "supersede" action reads as an amendment — they can
                    // tweak title / due / person_ref and leave the rest.
                    draft = it.draft.copy(
                        title = entity.title,
                        direction = entity.direction,
                        quote = entity.quote,
                        personRef = entity.personRef,
                        dueAtMillis = entity.dueAt?.toEpochMilliseconds(),
                        dueHint = entity.dueHint,
                        dueIsApproximate = entity.dueIsApproximate,
                    ),
                )
            }
        }
    }

    private fun BecalmError.toSaveError(): String = when (this) {
        is BecalmError.Unauthorized -> "로그인이 필요합니다"
        is BecalmError.NotFound -> "삭제된 약속입니다"
        is BecalmError.Validation -> message
        else -> "저장 실패 — 다시 시도해주세요"
    }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())
}
