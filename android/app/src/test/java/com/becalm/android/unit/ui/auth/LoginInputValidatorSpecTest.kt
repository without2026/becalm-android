package com.becalm.android.unit.ui.auth

import com.becalm.android.ui.auth.LoginInputValidationError
import com.becalm.android.ui.auth.LoginInputValidator
import com.becalm.android.ui.auth.SignUpInputValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginInputValidatorSpecTest {

    @Test
    fun `blank email or password returns empty-fields only`() {
        assertEquals(
            setOf(LoginInputValidationError.EmptyFields),
            LoginInputValidator.validate(email = "", password = "ValidPass1!"),
        )
        assertEquals(
            setOf(LoginInputValidationError.EmptyFields),
            LoginInputValidator.validate(email = "user@example.com", password = ""),
        )
    }

    @Test
    fun `invalid email and short password can be shown together`() {
        assertEquals(
            setOf(LoginInputValidationError.InvalidEmail, LoginInputValidationError.ShortPassword),
            LoginInputValidator.validate(email = "not-an-email", password = "short"),
        )
    }

    @Test
    fun `trimmed valid email and eight character password pass`() {
        assertTrue(
            LoginInputValidator.validate(email = " user@example.com ", password = "12345678").isEmpty(),
        )
    }

    @Test
    fun `signup requires all fields`() {
        assertEquals(
            setOf(SignUpInputValidationError.EmptyFields),
            LoginInputValidator.validateSignUp(email = "user@example.com", password = "ValidPass1!", confirmPassword = ""),
        )
    }

    @Test
    fun `signup validates email password length and confirmation`() {
        assertEquals(
            setOf(
                SignUpInputValidationError.InvalidEmail,
                SignUpInputValidationError.ShortPassword,
                SignUpInputValidationError.PasswordMismatch,
            ),
            LoginInputValidator.validateSignUp(
                email = "not-an-email",
                password = "short",
                confirmPassword = "different",
            ),
        )
    }

    @Test
    fun `signup valid trimmed email and matching password pass`() {
        assertTrue(
            LoginInputValidator.validateSignUp(
                email = " user@example.com ",
                password = "ValidPass1!",
                confirmPassword = "ValidPass1!",
            ).isEmpty(),
        )
    }
}
