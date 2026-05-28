package com.becalm.android.domain.onboarding

public object FirstMemoryValidator {
    private const val PERSON_NAME_MAX: Int = 80
    private const val PROMISE_TEXT_MAX: Int = 240
    private const val DUE_HINT_MAX: Int = 80

    public enum class Field { ORIGIN, PERSON_NAME, PROMISE_TEXT, KIND, DUE_HINT }

    public enum class Error {
        ORIGIN_REQUIRED,
        PERSON_NAME_REQUIRED,
        PERSON_NAME_TOO_LONG,
        PROMISE_TEXT_REQUIRED,
        PROMISE_TEXT_TOO_LONG,
        KIND_REQUIRED,
        DUE_HINT_TOO_LONG,
    }

    public sealed interface ValidationResult {
        public data class Ok(val input: FirstMemoryInput) : ValidationResult
        public data class Err(val fieldErrors: Map<Field, Error>) : ValidationResult
    }

    public fun validate(draft: FirstMemoryDraft): ValidationResult {
        val errors = linkedMapOf<Field, Error>()
        val personName = draft.personName.trim()
        val promiseText = draft.promiseText.trim()
        val dueHint = draft.dueHint?.trim().orEmpty()

        if (draft.origin == null) errors[Field.ORIGIN] = Error.ORIGIN_REQUIRED
        when {
            personName.isBlank() -> errors[Field.PERSON_NAME] = Error.PERSON_NAME_REQUIRED
            personName.length > PERSON_NAME_MAX -> errors[Field.PERSON_NAME] = Error.PERSON_NAME_TOO_LONG
        }
        when {
            promiseText.isBlank() -> errors[Field.PROMISE_TEXT] = Error.PROMISE_TEXT_REQUIRED
            promiseText.length > PROMISE_TEXT_MAX -> errors[Field.PROMISE_TEXT] = Error.PROMISE_TEXT_TOO_LONG
        }
        if (draft.kind == null) errors[Field.KIND] = Error.KIND_REQUIRED
        if (dueHint.length > DUE_HINT_MAX) errors[Field.DUE_HINT] = Error.DUE_HINT_TOO_LONG

        if (errors.isNotEmpty()) return ValidationResult.Err(errors)
        return ValidationResult.Ok(
            FirstMemoryInput(
                clientMemoryId = draft.clientMemoryId,
                origin = requireNotNull(draft.origin),
                personName = personName,
                promiseText = promiseText,
                kind = requireNotNull(draft.kind),
                dueHint = dueHint.takeIf { draft.kind == FirstMemoryKind.SHARED_SCHEDULE && it.isNotBlank() },
            ),
        )
    }
}
