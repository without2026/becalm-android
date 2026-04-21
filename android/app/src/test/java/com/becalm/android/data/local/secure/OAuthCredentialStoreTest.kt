package com.becalm.android.data.local.secure

import android.app.Application
import android.content.Context
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.GMAIL_READONLY_SCOPE
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_GOOGLE_ACCESS_TOKEN
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_GOOGLE_REFRESH_TOKEN
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_GOOGLE_SCOPE
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_GOOGLE_TOKEN_EXPIRES_AT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Robolectric tests for [OAuthCredentialStore] (plan:
 * `docs/plans/repo-auth-gmail-oauth-provider.md` § 5.6).
 *
 * Strategy: exercise the store against a plain `context.getSharedPreferences(...)` (via
 * the [OAuthCredentialStore] `@VisibleForTesting internal` constructor) rather than the
 * Keystore-backed [buildStorePrefs] path. Robolectric's JVM test environment does not
 * expose the Android Keystore, so [androidx.security.crypto.EncryptedSharedPreferences]
 * cannot be instantiated here — but the store's public contract (load / save / clear
 * round-trips, namespace isolation, scope self-defense) is independent of the underlying
 * encryption layer.
 *
 * Acceptance coverage (plan § 6):
 * - `saveGoogle then loadGoogle returns equal credential`
 * - `clearGoogle removes all google_* keys`
 * - `loadGoogle returns null when scope mismatches GMAIL_READONLY_SCOPE`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var store: OAuthCredentialStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Application
        // Each test gets a fresh, isolated prefs file.
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials",
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()
        store = OAuthCredentialStore(prefs, UnconfinedTestDispatcher())
    }

    // ── Round-trips ─────────────────────────────────────────────────────────────

    @Test
    fun `saveGoogle then loadGoogle returns equal credential`() = runTest {
        val credential = GoogleOAuthCredential(
            accessToken = "at_roundtrip",
            refreshToken = "rt_roundtrip",
            expiresAtEpochMillis = 1_700_000_000_000L,
            scope = GMAIL_READONLY_SCOPE,
        )

        store.saveGoogle(credential)
        val loaded = store.loadGoogle()

        assertEquals(credential, loaded)
    }

    @Test
    fun `saveGoogle with null refresh token then loadGoogle returns null refresh token`() = runTest {
        val credential = GoogleOAuthCredential(
            accessToken = "at_no_rt",
            refreshToken = null,
            expiresAtEpochMillis = 1_700_000_000_000L,
            scope = GMAIL_READONLY_SCOPE,
        )

        store.saveGoogle(credential)
        val loaded = store.loadGoogle()

        assertEquals(credential, loaded)
        assertNull(loaded?.refreshToken)
    }

    // ── loadGoogle emptiness semantics ──────────────────────────────────────────

    @Test
    fun `loadGoogle returns null on empty store`() = runTest {
        assertNull(store.loadGoogle())
    }

    // ── Namespace isolation ─────────────────────────────────────────────────────

    @Test
    fun `clearGoogle removes all google_* keys`() = runTest {
        val credential = GoogleOAuthCredential(
            accessToken = "at",
            refreshToken = "rt",
            expiresAtEpochMillis = 1L,
            scope = GMAIL_READONLY_SCOPE,
        )
        store.saveGoogle(credential)

        store.clearGoogle()

        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials",
            Context.MODE_PRIVATE,
        )
        assertFalse(prefs.contains(KEY_GOOGLE_ACCESS_TOKEN))
        assertFalse(prefs.contains(KEY_GOOGLE_REFRESH_TOKEN))
        assertFalse(prefs.contains(KEY_GOOGLE_TOKEN_EXPIRES_AT))
        assertFalse(prefs.contains(KEY_GOOGLE_SCOPE))
        assertNull(store.loadGoogle())
    }

    @Test
    fun `clearGoogle preserves unrelated provider keys`() = runTest {
        // Simulate a co-resident future Microsoft Graph namespace.
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials",
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString("ms_graph_access_token", "ms_at")
            .putString("ms_graph_scope", "Mail.Read")
            .apply()
        store.saveGoogle(
            GoogleOAuthCredential(
                accessToken = "at",
                refreshToken = null,
                expiresAtEpochMillis = 1L,
                scope = GMAIL_READONLY_SCOPE,
            ),
        )

        store.clearGoogle()

        assertTrue(prefs.contains("ms_graph_access_token"))
        assertEquals("ms_at", prefs.getString("ms_graph_access_token", null))
        assertEquals("Mail.Read", prefs.getString("ms_graph_scope", null))
    }

    // ── Scope self-defense ──────────────────────────────────────────────────────

    @Test
    fun `loadGoogle returns null when scope mismatches GMAIL_READONLY_SCOPE`() = runTest {
        // Simulate a stale record with a wider/different scope.
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials",
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString(KEY_GOOGLE_ACCESS_TOKEN, "at_stale")
            .putString(KEY_GOOGLE_REFRESH_TOKEN, "rt_stale")
            .putLong(KEY_GOOGLE_TOKEN_EXPIRES_AT, 1L)
            .putString(KEY_GOOGLE_SCOPE, "https://www.googleapis.com/auth/gmail.modify")
            .apply()

        val loaded = store.loadGoogle()

        assertNull(loaded)
        // Self-defense: stale keys should be cleared.
        assertFalse(prefs.contains(KEY_GOOGLE_ACCESS_TOKEN))
        assertFalse(prefs.contains(KEY_GOOGLE_SCOPE))
    }
}
