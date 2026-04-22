package com.becalm.android.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM coverage for the [GoogleSignInResult] sealed hierarchy.
 *
 * The launcher itself ([GoogleSignInHandle.launch]) calls into `CredentialManager`
 * system services that need a live Android instrumentation runtime, so the hardware
 * path is validated by manual QA — documented in
 * `docs/plans/ui-auth-google-signin-wiring.md` §6. This test pins the *shape* of the
 * result type so a refactor cannot silently collapse [UserCancelled] into [NoCredentials]
 * (both had previously been swallowed through a single `Error` arm in the pre-S6-C
 * placeholder UI).
 */
public class GoogleSignInResultTest {

    @Test
    public fun success_preservesIdTokenByValue() {
        val a = GoogleSignInResult.Success("token-1")
        val b = GoogleSignInResult.Success("token-1")
        val c = GoogleSignInResult.Success("token-2")

        assertEquals("data class equality keys on idToken", a, b)
        assertNotEquals("different tokens must not compare equal", a, c)
    }

    @Test
    public fun userCancelledAndNoCredentialsAreDistinctSingletons() {
        assertSame(GoogleSignInResult.UserCancelled, GoogleSignInResult.UserCancelled)
        assertSame(GoogleSignInResult.NoCredentials, GoogleSignInResult.NoCredentials)
        assertNotEquals(
            "cancel and no-credentials must remain distinguishable",
            GoogleSignInResult.UserCancelled as Any,
            GoogleSignInResult.NoCredentials as Any,
        )
    }

    @Test
    public fun errorRetainsCauseForOpsTriage() {
        val cause = IllegalStateException("transport")
        val wrapped = GoogleSignInResult.Error(cause)

        assertSame("underlying throwable must be preserved for the caller", cause, wrapped.throwable)
    }
}
