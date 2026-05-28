package com.becalm.android.unit.data.remote.supabase

import com.becalm.android.data.remote.supabase.SupabaseSessionAuthority
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSessionAuthoritySpecTest {

    @Test
    fun `expected issuer normalizes configured Supabase URL`() {
        assertEquals(
            "https://cbexbayyvalaoeybthgd.supabase.co/auth/v1",
            SupabaseSessionAuthority.expectedIssuerFor(" https://cbexbayyvalaoeybthgd.supabase.co/ "),
        )
        assertNull(SupabaseSessionAuthority.expectedIssuerFor(" "))
    }

    @Test
    fun `issuer decoder extracts non-secret JWT payload issuer`() {
        val token = jwtWithIssuer("https://cbexbayyvalaoeybthgd.supabase.co/auth/v1")

        assertEquals(
            "https://cbexbayyvalaoeybthgd.supabase.co/auth/v1",
            SupabaseSessionAuthority.issuerForAccessToken(token),
        )
    }

    @Test
    fun `issuer matcher keeps current project tokens and rejects stale project tokens`() {
        val expected = "https://cbexbayyvalaoeybthgd.supabase.co/auth/v1"

        assertTrue(
            SupabaseSessionAuthority.hasExpectedIssuer(
                jwtWithIssuer("https://cbexbayyvalaoeybthgd.supabase.co/auth/v1"),
                expected,
            ),
        )
        assertFalse(
            SupabaseSessionAuthority.hasExpectedIssuer(
                jwtWithIssuer("https://kcrrxxhcvtevilwtkgmr.supabase.co/auth/v1"),
                expected,
            ),
        )
    }

    @Test
    fun `issuer matcher rejects malformed or issuerless tokens`() {
        val expected = "https://cbexbayyvalaoeybthgd.supabase.co/auth/v1"

        assertNull(SupabaseSessionAuthority.issuerForAccessToken("not-a-jwt"))
        assertFalse(SupabaseSessionAuthority.hasExpectedIssuer("not-a-jwt", expected))
        assertFalse(SupabaseSessionAuthority.hasExpectedIssuer(jwtWithPayload("""{"sub":"user-1"}"""), expected))
    }

    private fun jwtWithIssuer(issuer: String): String =
        jwtWithPayload("""{"iss":"$issuer","sub":"user-1","aud":"authenticated"}""")

    private fun jwtWithPayload(payload: String): String =
        "${base64Url("""{"alg":"ES256","typ":"JWT"}""")}." +
            "${base64Url(payload)}." +
            "signature"

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}
