package com.becalm.android.ui.auth

internal enum class LoginInputValidationError {
    EmptyFields,
    InvalidEmail,
    ShortPassword,
}

internal object LoginInputValidator {
    private const val PasswordMinLength = 8
    private val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun validate(email: String, password: String): Set<LoginInputValidationError> {
        if (email.isBlank() || password.isBlank()) {
            return setOf(LoginInputValidationError.EmptyFields)
        }
        return buildSet {
            if (!emailPattern.matches(email.trim())) add(LoginInputValidationError.InvalidEmail)
            if (password.length < PasswordMinLength) add(LoginInputValidationError.ShortPassword)
        }
    }
}
