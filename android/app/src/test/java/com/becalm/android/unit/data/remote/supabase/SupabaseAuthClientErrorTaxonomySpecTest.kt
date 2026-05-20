package com.becalm.android.unit.data.remote.supabase

import com.becalm.android.core.result.BecalmError
import com.becalm.android.data.remote.supabase.mapEmailSignUpValidationError
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseAuthClientErrorTaxonomySpecTest {

    @Test
    fun `signup taxonomy detects an already registered email`() {
        val error = mapEmailSignUpValidationError(
            statusCode = 400,
            message = "User already registered",
        )

        assertEquals(BecalmError.Validation(field = "email", message = "email_already_registered"), error)
    }

    @Test
    fun `signup taxonomy detects Supabase auth error codes for duplicate email`() {
        val error = mapEmailSignUpValidationError(
            statusCode = 422,
            message = "user_already_exists\nA user with this email address has already been registered",
        )

        assertEquals(BecalmError.Validation(field = "email", message = "email_already_registered"), error)
    }

    @Test
    fun `signup taxonomy detects weak password responses`() {
        val error = mapEmailSignUpValidationError(
            statusCode = 422,
            message = "Password should be at least 8 characters",
        )

        assertEquals(BecalmError.Validation(field = "password", message = "weak_password"), error)
    }

    @Test
    fun `signup taxonomy keeps unknown validation failures generic`() {
        val error = mapEmailSignUpValidationError(
            statusCode = 400,
            message = "invalid signup payload",
        )

        assertEquals(BecalmError.Validation(field = "email", message = "signup_failed"), error)
    }
}
