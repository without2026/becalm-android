package com.becalm.android.domain.commitment

import kotlinx.datetime.Instant

/**
 * Raw form state for the EDIT-007 supersede correction sheet. The VM holds this
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
    val counterpartyRef: String?,
    val dueAtMillis: Long?,
    val dueHint: String?,
    val dueIsApproximate: Boolean,
)

/**
 * Pure-Kotlin validator for the supersede correction form. Implements MAN-005 rules:
 *
 * - `title` is stripped of leading/trailing whitespace. Empty → error. More than
 *   200 chars → error.
 * - `direction` must be exactly "give" or "take" (the radio-group UI defaults
 *   to "give" per spec).
 * - `quote` is stripped. Empty → error (spec MAN-002: "증거 없는 기록 방지").
 *   More than 500 chars → error.
 * - `counterpartyRef` is normalised by [CounterpartyRefNormalizer.normalize]; phone-shaped
 *   values must pass strict E.164. Free-form names are accepted as-is.
 * - `dueAtMillis` may be null. Any non-null value is acceptable (past is OK
 *   per MAN-005 "지난 약속 추후 기록").
 *
 * Validation emits stable error codes. The UI maps those codes to localized
 * resources at render time, keeping this domain validator Android-free.
 */
public object CommitmentManualValidator {

    private const val TITLE_MAX = 200
    private const val QUOTE_MAX = 500

    /**
     * Fields that can carry a validation error. The enum lets the Composable
     * render errors adjacent to specific inputs without string-matching.
     */
    public enum class Field { TITLE, DIRECTION, QUOTE, PERSON_REF }

    public enum class Error {
        TITLE_REQUIRED,
        TITLE_TOO_LONG,
        DIRECTION_INVALID,
        QUOTE_REQUIRED,
        QUOTE_TOO_LONG,
        PERSON_REF_INVALID,
    }

    public sealed interface ValidationResult {
        public data object Ok : ValidationResult
        public data class Err(val fieldErrors: Map<Field, Error>) : ValidationResult
    }

    /**
     * Runs the MAN-005 ruleset. Non-suspending; cheap to call on every field
     * change to drive live error state.
     */
    public fun validate(input: ManualCommitmentDraft): ValidationResult {
        val errors = mutableMapOf<Field, Error>()

        val trimmedTitle = input.title.trim()
        when {
            trimmedTitle.isEmpty() -> errors[Field.TITLE] = Error.TITLE_REQUIRED
            trimmedTitle.length > TITLE_MAX ->
                errors[Field.TITLE] = Error.TITLE_TOO_LONG
        }

        if (input.direction != "give" && input.direction != "take") {
            errors[Field.DIRECTION] = Error.DIRECTION_INVALID
        }

        val trimmedQuote = input.quote.trim()
        when {
            trimmedQuote.isEmpty() -> errors[Field.QUOTE] = Error.QUOTE_REQUIRED
            trimmedQuote.length > QUOTE_MAX ->
                errors[Field.QUOTE] = Error.QUOTE_TOO_LONG
        }

        val normalisedRef = CounterpartyRefNormalizer.normalize(input.counterpartyRef)
        if (normalisedRef != null && !CounterpartyRefNormalizer.isValidPhoneShapeOrFreeForm(normalisedRef)) {
            errors[Field.PERSON_REF] = Error.PERSON_REF_INVALID
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
            counterpartyRef = CounterpartyRefNormalizer.normalize(draft.counterpartyRef),
            dueAt = draft.dueAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            dueHint = draft.dueHint?.trim()?.takeIf { it.isNotBlank() },
            dueIsApproximate = draft.dueIsApproximate,
        )
}
