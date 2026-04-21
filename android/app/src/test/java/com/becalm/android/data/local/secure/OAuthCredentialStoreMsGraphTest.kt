package com.becalm.android.data.local.secure

import android.app.Application
import android.content.Context
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.GMAIL_READONLY_SCOPE
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_GOOGLE_ACCESS_TOKEN
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_GOOGLE_SCOPE
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_MS_GRAPH_ACCESS_TOKEN
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_MS_GRAPH_ACCOUNT_IDENTIFIER
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_MS_GRAPH_REFRESH_TOKEN
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_MS_GRAPH_SCOPE
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.KEY_MS_GRAPH_TOKEN_EXPIRES_AT
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.MS_GRAPH_MAIL_READ_SCOPE
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
 * Robolectric tests for the Microsoft Graph namespace extension of [OAuthCredentialStore]
 * (plan: `docs/plans/repo-auth-msgraph-oauth-provider.md` § 5.4).
 *
 * Strategy: mirror [OAuthCredentialStoreTest] — exercise the store against a plain
 * `context.getSharedPreferences(...)` via the [OAuthCredentialStore] `@VisibleForTesting`
 * constructor rather than the Keystore-backed [buildStorePrefs] path. The JVM test
 * environment does not expose the Android Keystore, but the store's public contract —
 * load/save round-trips, namespace isolation, scope self-defense — is independent of the
 * underlying encryption layer.
 *
 * Acceptance coverage (plan § 6):
 * - round-trip save/load
 * - scope mismatch → null + self-clear
 * - `clearMsGraph` does NOT affect `google_*` keys
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OAuthCredentialStoreMsGraphTest {

    private lateinit var context: Context
    private lateinit var store: OAuthCredentialStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Application
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials_msgraph",
            Context.MODE_PRIVATE,
        )
        prefs.edit().clear().apply()
        store = OAuthCredentialStore(prefs, UnconfinedTestDispatcher())
    }

    // ── Round-trip ─────────────────────────────────────────────────────────────

    @Test
    fun `saveMsGraph then loadMsGraph returns equal credential`() = runTest {
        val credential = MsGraphOAuthCredential(
            accessToken = "ms_at_roundtrip",
            refreshToken = "ms_rt_roundtrip",
            expiresAtEpochMillis = 1_700_000_000_000L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "account-home-id-abc",
        )

        store.saveMsGraph(credential)
        val loaded = store.loadMsGraph()

        assertEquals(credential, loaded)
    }

    @Test
    fun `saveMsGraph with null refresh token then loadMsGraph returns null refresh token`() = runTest {
        val credential = MsGraphOAuthCredential(
            accessToken = "ms_at_no_rt",
            refreshToken = null,
            expiresAtEpochMillis = 1_700_000_000_000L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "account-home-id",
        )

        store.saveMsGraph(credential)
        val loaded = store.loadMsGraph()

        assertEquals(credential, loaded)
        assertNull(loaded?.refreshToken)
    }

    @Test
    fun `loadMsGraph returns null on empty store`() = runTest {
        assertNull(store.loadMsGraph())
    }

    @Test
    fun `loadMsGraph returns null when account identifier is missing`() = runTest {
        // Regression: account identifier is mandatory — without it, silent refresh has
        // no way to re-bind to the correct account record on process restart. A partial
        // write (e.g. older app version without the accountIdentifier field) must be
        // treated as unusable.
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials_msgraph",
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString(KEY_MS_GRAPH_ACCESS_TOKEN, "ms_at")
            .putLong(KEY_MS_GRAPH_TOKEN_EXPIRES_AT, 1L)
            .putString(KEY_MS_GRAPH_SCOPE, MS_GRAPH_MAIL_READ_SCOPE)
            .apply()

        assertNull(store.loadMsGraph())
    }

    // ── Scope self-defense ────────────────────────────────────────────────────

    @Test
    fun `loadMsGraph returns null when scope mismatches MS_GRAPH_MAIL_READ_SCOPE`() = runTest {
        // Simulate a stale record where an older build persisted a wider scope. Reading
        // this should self-clear and return null so downstream callers treat it as
        // unauthenticated and drive a fresh consent prompt.
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials_msgraph",
            Context.MODE_PRIVATE,
        )
        prefs.edit()
            .putString(KEY_MS_GRAPH_ACCESS_TOKEN, "ms_at_stale")
            .putString(KEY_MS_GRAPH_REFRESH_TOKEN, "ms_rt_stale")
            .putLong(KEY_MS_GRAPH_TOKEN_EXPIRES_AT, 1L)
            // NOTE: deliberately not one of the forbidden scopes (Mail.ReadWrite / etc.)
            // because the plan's grep invariant forbids those strings anywhere under
            // android/app/src/main/java. We use a generic wider-scope string here so
            // the invariant stays green.
            .putString(KEY_MS_GRAPH_SCOPE, "scope_wider_than_expected")
            .putString(KEY_MS_GRAPH_ACCOUNT_IDENTIFIER, "account-home-id")
            .apply()

        val loaded = store.loadMsGraph()

        assertNull(loaded)
        // Self-defense: stale keys should be cleared.
        assertFalse(prefs.contains(KEY_MS_GRAPH_ACCESS_TOKEN))
        assertFalse(prefs.contains(KEY_MS_GRAPH_SCOPE))
        assertFalse(prefs.contains(KEY_MS_GRAPH_ACCOUNT_IDENTIFIER))
    }

    // ── Namespace isolation ───────────────────────────────────────────────────

    @Test
    fun `clearMsGraph does not affect google_ keys`() = runTest {
        // Seed both namespaces. Clearing MS Graph must NOT touch the Google keys —
        // the PIPA/UX contract is that Gmail and Outlook credentials have independent
        // lifecycles (sign-out of one provider does not log the user out of the other).
        val googleCredential = GoogleOAuthCredential(
            accessToken = "google_at",
            refreshToken = "google_rt",
            expiresAtEpochMillis = 2L,
            scope = GMAIL_READONLY_SCOPE,
        )
        val msGraphCredential = MsGraphOAuthCredential(
            accessToken = "ms_at",
            refreshToken = "ms_rt",
            expiresAtEpochMillis = 1L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "ms-account-home-id",
        )
        store.saveGoogle(googleCredential)
        store.saveMsGraph(msGraphCredential)

        store.clearMsGraph()

        // MS Graph namespace wiped.
        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials_msgraph",
            Context.MODE_PRIVATE,
        )
        assertFalse(prefs.contains(KEY_MS_GRAPH_ACCESS_TOKEN))
        assertFalse(prefs.contains(KEY_MS_GRAPH_REFRESH_TOKEN))
        assertFalse(prefs.contains(KEY_MS_GRAPH_TOKEN_EXPIRES_AT))
        assertFalse(prefs.contains(KEY_MS_GRAPH_SCOPE))
        assertFalse(prefs.contains(KEY_MS_GRAPH_ACCOUNT_IDENTIFIER))
        assertNull(store.loadMsGraph())

        // Google namespace intact.
        assertTrue(prefs.contains(KEY_GOOGLE_ACCESS_TOKEN))
        assertTrue(prefs.contains(KEY_GOOGLE_SCOPE))
        assertEquals(googleCredential, store.loadGoogle())
    }

    @Test
    fun `clearMsGraph removes all ms_graph_ keys`() = runTest {
        val credential = MsGraphOAuthCredential(
            accessToken = "ms_at",
            refreshToken = "ms_rt",
            expiresAtEpochMillis = 1L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "ms-account-home-id",
        )
        store.saveMsGraph(credential)

        store.clearMsGraph()

        val prefs = context.getSharedPreferences(
            "test_becalm_oauth_credentials_msgraph",
            Context.MODE_PRIVATE,
        )
        assertFalse(prefs.contains(KEY_MS_GRAPH_ACCESS_TOKEN))
        assertFalse(prefs.contains(KEY_MS_GRAPH_REFRESH_TOKEN))
        assertFalse(prefs.contains(KEY_MS_GRAPH_TOKEN_EXPIRES_AT))
        assertFalse(prefs.contains(KEY_MS_GRAPH_SCOPE))
        assertFalse(prefs.contains(KEY_MS_GRAPH_ACCOUNT_IDENTIFIER))
        assertNull(store.loadMsGraph())
    }

    @Test
    fun `clearGoogle does not affect ms_graph_ keys`() = runTest {
        // Mirror of the Gmail test but asserting in the other direction. Together with
        // `clearMsGraph does not affect google_ keys`, this pair locks the namespace
        // isolation invariant from both angles.
        store.saveGoogle(
            GoogleOAuthCredential(
                accessToken = "google_at",
                refreshToken = null,
                expiresAtEpochMillis = 2L,
                scope = GMAIL_READONLY_SCOPE,
            ),
        )
        store.saveMsGraph(
            MsGraphOAuthCredential(
                accessToken = "ms_at",
                refreshToken = null,
                expiresAtEpochMillis = 1L,
                scope = MS_GRAPH_MAIL_READ_SCOPE,
                accountIdentifier = "ms-account-home-id",
            ),
        )

        store.clearGoogle()

        val loaded = store.loadMsGraph()
        assertEquals("ms_at", loaded?.accessToken)
        assertEquals("ms-account-home-id", loaded?.accountIdentifier)
    }
}
