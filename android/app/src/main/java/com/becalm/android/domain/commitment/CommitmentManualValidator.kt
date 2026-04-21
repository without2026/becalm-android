package com.becalm.android.domain.commitment

import kotlinx.datetime.Instant

/**
 * Raw form state for the manual-create sheet (MAN-001..005). The VM holds this
 * and passes it to [CommitmentManualValidator.validate] on every save attempt.
 *
 * [dueAtMillis] is carried as nullable epoch-millisecond Long rather than an
 * `Instant` because the Material3 DatePicker / TimePicker APIs expose the
 * selected moment as epoch milliseconds. Null means "no deadline".
 */
public data class ManualCommitmentDraft(
    val title: String,
    val direction: String,
    val quote: String,
    val personRef: String?,
    val dueAtMillis: Long?,
    val dueHint: String?,
    val dueIsApproximate: Boolean,
)

/**
 * Pure-Kotlin validator for the manual-create form. Implements MAN-005 rules:
 *
 * - `title` is stripped of leading/trailing whitespace. Empty → error. More than
 *   200 chars → error.
 * - `direction` must be exactly "give" or "take" (the radio-group UI defaults
 *   to "give" per spec).
 * - `quote` is stripped. Empty → error (spec MAN-002: "증거 없는 기록 방지").
 *   More than 500 chars → error.
 * - `personRef` is normalised by [PersonRefNormalizer.normalize]; phone-shaped
 *   values must pass strict E.164. Free-form names are accepted as-is.
 * - `dueAtMillis` may be null. Any non-null value is acceptable (past is OK
 *   per MAN-005 "지난 약속 추후 기록").
 *
 * Validation error messages are English seed strings to let the unit tests
 * assert exact text without pulling in Android resources; the VM forwards them
 * to localized resources at render time.
 */
public object CommitmentManualValidator {

    private const val TITLE_MAX = 200
    private const val QUOTE_MAX = 500

    /**
     * Fields that can carry a validation error. The enum lets the Composable
     * render errors adjacent to specific inputs without string-matching.
     */
    public enum class Field { TITLE, DIRECTION, QUOTE, PERSON_REF }

    public sealed interface ValidationResult {
        public data object Ok : ValidationResult
        public data class Err(val fieldErrors: Map<Field, String>) : ValidationResult
    }

    /**
     * Runs the MAN-005 ruleset. Non-suspending; cheap to call on every field
     * change to drive live error state.
     */
    public fun validate(input: ManualCommitmentDraft): ValidationResult {
        val errors = mutableMapOf<Field, String>()

        val trimmedTitle = input.title.trim()
        when {
            trimmedTitle.isEmpty() -> errors[Field.TITLE] = "Title must not be empty"
            trimmedTitle.length > TITLE_MAX ->
                errors[Field.TITLE] = "Title must be at most $TITLE_MAX characters"
        }

        if (input.direction != "give" && input.direction != "take") {
            errors[Field.DIRECTION] = "Direction must be 'give' or 'take'"
        }

        val trimmedQuote = input.quote.trim()
        when {
            trimmedQuote.isEmpty() -> errors[Field.QUOTE] = "Quote must not be empty"
            trimmedQuote.length > QUOTE_MAX ->
                errors[Field.QUOTE] = "Quote must be at most $QUOTE_MAX characters"
        }

        val normalisedRef = PersonRefNormalizer.normalize(input.personRef)
        if (normalisedRef != null && !PersonRefNormalizer.isValidPhoneShapeOrFreeForm(normalisedRef)) {
            errors[Field.PERSON_REF] =
                "Phone-shaped person reference must be valid E.164 (e.g. +821012345678)"
        }

        return if (errors.isEmpty()) ValidationResult.Ok else ValidationResult.Err(errors)
    }

    /**
     * Normalises a validated [ManualCommitmentDraft] into a persistable
     * [ManualCommitmentInput]. Callers SHOULD invoke [validate] first.
     */
    public fun normalise(draft: ManualCommitmentDraft): ManualCommitmentInput =
        ManualCommitmentInput(
            title = draft.title.trim(),
            direction = draft.direction,
            quote = draft.quote.trim(),
            personRef = PersonRefNormalizer.normalize(draft.personRef),
            dueAt = draft.dueAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            dueHint = draft.dueHint?.trim()?.takeIf { it.isNotBlank() },
            dueIsApproximate = draft.dueIsApproximate,
        )
}
