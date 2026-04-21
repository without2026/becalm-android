package com.becalm.android.ui.commitments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.domain.commitment.CommitmentEditDraft
import com.becalm.android.domain.commitment.CommitmentEditValidator
import com.becalm.android.domain.commitment.CommitmentEditValidator.Field
import com.becalm.android.domain.commitment.CommitmentEditValidator.ValidationResult
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
 * Read-only snapshot of the evidentiary columns displayed above the edit form.
 *
 * The quote string is presented as a selectable [androidx.compose.material3.Text]
 * in the Sheet — **never** as a [androidx.compose.material3.TextField] — because
 * the quote is legally evidentiary per `.spec/commitment-edit.spec.yml`
 * invariant 1 and must not be silently editable.
 *
 * @property quote Verbatim source quote; never mutable from this screen.
 * @property quoteDisputed Current value of `quote_disputed` (EDIT-005).
 * @property sourceLabel Human-readable source line (e.g. "manual", "voice · 3/12 14:05 KST").
 */
public data class EditReadOnly(
    val quote: String,
    val quoteDisputed: Boolean,
    val sourceLabel: String,
)

/**
 * Immutable UI state for [CommitmentEditSheet].
 *
 * - [loading] is true until the initial Room fetch lands; false while the user
 *   types (the form does not re-flip to loading on every keystroke).
 * - [saving] is true while a repository write is in flight — the save button
 *   uses this to show a spinner and disable itself.
 * - [fieldErrors] keyed by [Field] so the Composable can render per-field
 *   helper text without string-matching.
 * - [notFound] flips true when the repository reports `NotFound` on load —
 *   the Sheet dismisses itself in that case.
 * - [saveError] is a single-shot human-readable message; the Sheet displays
 *   it in a snackbar and the VM clears it via [clearSaveError].
 */
public data class EditUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val notFound: Boolean = false,
    val readOnly: EditReadOnly? = null,
    val title: String = "",
    val dueAtMillis: Long? = null,
    val dueIsApproximate: Boolean = false,
    val dueHint: String = "",
    val personRef: String = "",
    val direction: String = "give",
    val fieldErrors: Map<Field, String> = emptyMap(),
    val saveError: String? = null,
)

/**
 * One-shot side-effect emitted by the VM when the Sheet should dismiss itself.
 *
 * - [Dismissed.Saved] on successful save (flows through to the repository's
 *   optimistic DAO write — the Compose list will re-emit via observeById).
 * - [Dismissed.Deleted] after a successful soft-delete. The Sheet pops the
 *   back-stack; the detail sheet behind it will show the "삭제된 약속" empty
 *   state on the next Room emission.
 * - [Dismissed.Cancelled] when the user taps 취소.
 */
public sealed interface EditDismissEvent {
    /** Successful save; caller pops back to the detail sheet. */
    public data object Saved : EditDismissEvent

    /** Successful soft-delete; caller pops twice (edit sheet + detail sheet). */
    public data object Deleted : EditDismissEvent

    /** User cancelled without saving; caller pops the edit sheet only. */
    public data object Cancelled : EditDismissEvent
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "CommitmentEditVM"

/**
 * ViewModel for [CommitmentEditSheet] (EDIT-001..008).
 *
 * Load path:
 * 1. Resolve the commitment UUID from [SavedStateHandle] under
 *    [BecalmRoute.CommitmentEdit.ARG_ID].
 * 2. Read the initial row via [CommitmentRepository.observeById] and seed the
 *    form fields. We read the first emission only — the form must NOT
 *    re-sync while the user is typing (that would clobber in-flight edits).
 *
 * Save path ([onSave]):
 * 1. Build a [CommitmentEditDraft] from the current state.
 * 2. Run [CommitmentEditValidator.validate]. If errors, expose them via
 *    [EditUiState.fieldErrors] and abort.
 * 3. Normalise to a [com.becalm.android.domain.commitment.CommitmentEditPatch]
 *    and call [CommitmentRepository.editCommitment]. The repo writes locally
 *    (sync_status='pending') and does a best-effort PATCH — see its KDoc for
 *    the invariant.
 *
 * Dispute toggle ([onToggleDispute]) and delete ([onConfirmDelete]) route to
 * the corresponding repository methods. Both follow the same "spin → dismiss
 * or surface error" pattern as save.
 *
 * @param commitmentRepository Source of the initial row + target of edit writes.
 * @param savedStateHandle Navigation argument carrier; expects
 *   [BecalmRoute.CommitmentEdit.ARG_ID].
 * @param logger Structured log sink.
 */
@HiltViewModel
public class CommitmentEditViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val userPrefsStore: com.becalm.android.data.local.datastore.UserPrefsStore,
    savedStateHandle: SavedStateHandle,
    private val logger: Logger,
) : ViewModel() {

    private val id: String =
        savedStateHandle.get<String>(BecalmRoute.CommitmentEdit.ARG_ID).orEmpty()

    private val _uiState: MutableStateFlow<EditUiState> = MutableStateFlow(EditUiState())

    /** Current UI state; starts loading and settles on the initial Room fetch. */
    public val uiState: StateFlow<EditUiState> = _uiState.asStateFlow()

    private val _dismiss: MutableSharedFlow<EditDismissEvent> =
        MutableSharedFlow(extraBufferCapacity = 1)

    /** One-shot dismiss events consumed by the composable layer. */
    public val dismiss: SharedFlow<EditDismissEvent> = _dismiss.asSharedFlow()

    init {
        if (id.isEmpty()) {
            logger.e(TAG, "missing commitment id on edit sheet init")
            _uiState.update { it.copy(loading = false, notFound = true) }
        } else {
            load()
        }
    }

    // ─── Public actions ───────────────────────────────────────────────────────

    public fun onTitleChange(value: String) {
        _uiState.update { it.copy(title = value, fieldErrors = it.fieldErrors - Field.TITLE) }
    }

    public fun onDueAtMillisChange(value: Long?) {
        _uiState.update { it.copy(dueAtMillis = value, fieldErrors = it.fieldErrors - Field.DUE_AT) }
    }

    public fun onDueIsApproximateChange(value: Boolean) {
        _uiState.update { it.copy(dueIsApproximate = value) }
    }

    public fun onDueHintChange(value: String) {
        _uiState.update { it.copy(dueHint = value) }
    }

    public fun onPersonRefChange(value: String) {
        _uiState.update {
            it.copy(personRef = value, fieldErrors = it.fieldErrors - Field.PERSON_REF)
        }
    }

    public fun onDirectionChange(value: String) {
        _uiState.update {
            it.copy(direction = value, fieldErrors = it.fieldErrors - Field.DIRECTION)
        }
    }

    /** EDIT-003: validate, normalise, and persist the form via [CommitmentRepository.editCommitment]. */
    public fun onSave() {
        val snap = _uiState.value
        val draft = snap.toDraft()
        when (val v = CommitmentEditValidator.validate(draft)) {
            is ValidationResult.Err -> {
                _uiState.update { it.copy(fieldErrors = v.fieldErrors) }
                return
            }
            is ValidationResult.Ok -> Unit
        }
        val patch = CommitmentEditValidator.normalise(draft)

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null) }
            val result = commitmentRepository.editCommitment(id, patch)
            _uiState.update { it.copy(saving = false) }
            when (result) {
                is BecalmResult.Success -> _dismiss.tryEmit(EditDismissEvent.Saved)
                is BecalmResult.Failure -> {
                    logger.e(TAG, "editCommitment failed id=${hashId(id)} error=${result.error}")
                    _uiState.update { it.copy(saveError = result.error.toSaveError()) }
                }
            }
        }
    }

    /** EDIT-005: toggle `quote_disputed` on or off. */
    public fun onToggleDispute() {
        val snap = _uiState.value
        val readOnly = snap.readOnly ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null) }
            val result = if (readOnly.quoteDisputed) {
                commitmentRepository.clearQuoteDispute(id)
            } else {
                commitmentRepository.markQuoteDisputed(id)
            }
            when (result) {
                is BecalmResult.Success -> {
                    _uiState.update {
                        it.copy(
                            saving = false,
                            readOnly = it.readOnly?.copy(
                                quoteDisputed = !readOnly.quoteDisputed,
                            ),
                        )
                    }
                }
                is BecalmResult.Failure -> {
                    logger.e(TAG, "toggleDispute failed id=${hashId(id)} error=${result.error}")
                    _uiState.update {
                        it.copy(saving = false, saveError = result.error.toSaveError())
                    }
                }
            }
        }
    }

    /** EDIT-006: soft-delete via [CommitmentRepository.softDelete] after user confirms. */
    public fun onConfirmDelete() {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, saveError = null) }
            val result = commitmentRepository.softDelete(id)
            _uiState.update { it.copy(saving = false) }
            when (result) {
                is BecalmResult.Success -> _dismiss.tryEmit(EditDismissEvent.Deleted)
                is BecalmResult.Failure -> {
                    logger.e(TAG, "softDelete failed id=${hashId(id)} error=${result.error}")
                    _uiState.update { it.copy(saveError = result.error.toSaveError()) }
                }
            }
        }
    }

    /** User tapped 취소 — no writes, just dismiss. */
    public fun onCancel() {
        _dismiss.tryEmit(EditDismissEvent.Cancelled)
    }

    /** Clears a one-shot [EditUiState.saveError] after the Composable displays it. */
    public fun clearSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun load() {
        viewModelScope.launch {
            // Resolve the current user id first so we can read via the user-scoped
            // DAO query. A bare-id observation could otherwise surface another
            // account's row after a sign-out/sign-in on the shared Room DB.
            val userId = userPrefsStore.observeCurrentUserId().firstOrNull()
            if (userId.isNullOrBlank()) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            val entity = commitmentRepository.observeByIdForUser(userId, id).firstOrNull()
            if (entity == null) {
                _uiState.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            _uiState.update { seed(entity) }
        }
    }

    private fun seed(entity: CommitmentEntity): EditUiState = EditUiState(
        loading = false,
        saving = false,
        notFound = false,
        readOnly = EditReadOnly(
            quote = entity.quote,
            quoteDisputed = entity.quoteDisputed,
            sourceLabel = entity.sourceType,
        ),
        title = entity.title,
        dueAtMillis = entity.dueAt?.toEpochMilliseconds(),
        dueIsApproximate = entity.dueIsApproximate,
        dueHint = entity.dueHint.orEmpty(),
        personRef = entity.personRef.orEmpty(),
        direction = entity.direction,
        fieldErrors = emptyMap(),
        saveError = null,
    )

    private fun EditUiState.toDraft(): CommitmentEditDraft = CommitmentEditDraft(
        title = title,
        dueAtMillis = dueAtMillis,
        dueHint = dueHint.ifBlank { null },
        dueIsApproximate = dueIsApproximate,
        personRef = personRef.ifBlank { null },
        direction = direction,
    )

    private fun BecalmError.toSaveError(): String = when (this) {
        is BecalmError.NotFound -> "삭제된 약속입니다"
        is BecalmError.Unauthorized -> "로그인이 필요합니다"
        is BecalmError.Validation -> message
        else -> "저장 실패 — 다시 시도해주세요"
    }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())
}
