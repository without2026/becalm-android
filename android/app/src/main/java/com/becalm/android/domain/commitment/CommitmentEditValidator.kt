package com.becalm.android.domain.commitment

/**
 * Raw user-entered draft for the commitment edit form. The validator normalises
 * this into a persistable [CommitmentEditPatch].
 *
 * [dueAtMillis] is carried as a nullable epoch-millisecond Long rather than an
 * `Instant` because the Material3 DatePicker / TimePicker APIs expose the
 * selected moment as epoch milliseconds. Null means "no deadline".
 *
 * Spec refs: `.spec/commitment-edit.spec.yml` EDIT-002, EDIT-004.
 */
public data class CommitmentEditDraft(
    val title: String,
    val dueAtMillis: Long?,
    val dueHint: String?,
    val dueIsApproximate: Boolean,
    val counterpartyRef: String?,
    val direction: String,
)

/**
 * Pure-Kotlin validator for the commitment edit form, implementing EDIT-004:
 *
 * - `title` is stripped of leading/trailing whitespace. Empty → error. More than
 *   200 chars → error.
 * - `dueAt` may be null (the user can clear the deadline). When present, any
 *   instant is accepted — the spec allows past dates for late-logged
 *   commitments (EDIT-004).
 * - `counterpartyRef` is normalised by stripping whitespace and lowercasing. A blank
 *   result is acceptable (Unassigned). If the string looks phone-shaped (i.e.
 *   it is composed exclusively of the characters `+`, `-`, space, and digits,
 *   and contains at least one digit) it must match a strict E.164 regex —
 *   otherwise we accept the free-form string as-is (display-name / opaque id).
 * - `direction` must be exactly `"give"` or `"take"`. The RadioButton UI
 *   guarantees this at steady state, but we still guard against an
 *   empty initial state.
 *
 * Returns [ValidationResult.Ok] on success or [ValidationResult.Err] with a
 * [Field]-keyed map of human-readable error messages. The VM forwards these to
 * localized resources; the validator emits English seed strings so the unit
 * tests can assert without pulling in Android resources.
 *
 * Spec refs: `.spec/commitment-edit.spec.yml` EDIT-004.
 */
public object CommitmentEditValidator {

    private const val TITLE_MAX = 200

    /**
     * Fields that can carry a validation error. The enum exists so the UI can
     * render errors adjacent to specific form inputs without string-matching
     * message keys.
     */
    public enum class Field { TITLE, DUE_AT, PERSON_REF, DIRECTION }

    public sealed interface ValidationResult {
        public data object Ok : ValidationResult
        public data class Err(val fieldErrors: Map<Field, String>) : ValidationResult
    }

    /**
     * Runs the EDIT-004 ruleset. Non-suspending; cheap to call on every field
     * change to drive live error state.
     */
    public fun validate(input: CommitmentEditDraft): ValidationResult {
        val errors = mutableMapOf<Field, String>()

        val trimmedTitle = input.title.trim()
        when {
            trimmedTitle.isEmpty() -> errors[Field.TITLE] = "Title must not be empty"
            trimmedTitle.length > TITLE_MAX ->
                errors[Field.TITLE] = "Title must be at most $TITLE_MAX characters"
        }

        // dueAtMillis: null is allowed. Any non-null Long is a valid Instant — we
        // do not reject past dates per EDIT-004 ("past is OK").

        val normalisedRef = CounterpartyRefNormalizer.normalize(input.counterpartyRef)
        if (normalisedRef != null && !CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm(normalisedRef)) {
            errors[Field.PERSON_REF] =
                "Phone-shaped person reference must be valid E.164 (e.g. +821012345678)"
        }

        if (input.direction != "give" && input.direction != "take") {
            errors[Field.DIRECTION] = "Direction must be 'give' or 'take'"
        }

        return if (errors.isEmpty()) ValidationResult.Ok else ValidationResult.Err(errors)
    }

    /**
     * Normalises a validated [CommitmentEditDraft] into a persistable
     * [CommitmentEditPatch]. Callers SHOULD invoke [validate] first — this
     * method does not re-check the invariants.
     *
     * @param draft The user-entered form values.
     * @return A [CommitmentEditPatch] ready for the repository layer.
     */
    public fun normalise(draft: CommitmentEditDraft): CommitmentEditPatch =
        CommitmentEditPatch(
            title = draft.title.trim(),
            dueAt = draft.dueAtMillis?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
            dueHint = draft.dueHint?.trim()?.takeIf { it.isNotBlank() },
            dueIsApproximate = draft.dueIsApproximate,
            counterpartyRef = CounterpartyRefNormalizer.normalize(draft.counterpartyRef),
            direction = draft.direction,
        )
}
