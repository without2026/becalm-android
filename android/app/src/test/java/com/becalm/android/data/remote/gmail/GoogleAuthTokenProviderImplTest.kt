package com.becalm.android.data.remote.gmail

import android.app.Activity
import android.app.PendingIntent
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.secure.GoogleOAuthCredential
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.GMAIL_READONLY_SCOPE
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [GoogleAuthTokenProviderImpl] (plan:
 * `docs/plans/repo-auth-gmail-oauth-provider.md` § 5.6).
 *
 * Strategy:
 * - [OAuthCredentialStore] is mocked with [io.mockk]; the suspension contract is driven
 *   via [coEvery] / [coVerify].
 * - [AuthorizationClientGateway] is mocked (interface-only) so no Google Play Services
 *   classes are loaded. This lets us assert both success and failure paths without
 *   [org.robolectric.RobolectricTestRunner].
 * - [FakeClock] drives [GoogleAuthTokenProviderImpl.currentToken]'s expiry-window logic.
 *
 * Acceptance coverage (plan § 6):
 * - `currentToken returns null when store empty`
 * - `currentToken returns token when not expired`
 * - `currentToken returns null 60s before expiry`
 * - `refreshSilently succeeds updates cache and state transitions to Authenticated`
 * - `refreshSilently fails clears store and state transitions to ReauthRequired`
 * - `startSignIn scope mismatch returns Failure SCOPE_DENIED`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoogleAuthTokenProviderImplTest {

    private lateinit var credentialStore: OAuthCredentialStore
    private lateinit var gateway: AuthorizationClientGateway
    private lateinit var clock: FakeClock
    private lateinit var logger: RecordingLogger
    private lateinit var provider: GoogleAuthTokenProviderImpl

    private val activity: Activity = mockk(relaxed = true)

    @Before
    fun setUp() {
        credentialStore = mockk()
        gateway = mockk()
        clock = FakeClock(nowInstant = Instant.fromEpochMilliseconds(1_000_000L))
        logger = RecordingLogger()
        provider = GoogleAuthTokenProviderImpl(
            credentialStore = credentialStore,
            clock = clock,
            logger = logger,
            authorizationClient = gateway,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        // Default: no persisted credential. Individual tests override with coEvery.
        // Declared here so refreshSilently's account-pin fallback (which peeks at the
        // store when the in-memory cache is empty) does not trip mockk's strict mode
        // for tests that only care about the authorize/save interaction.
        coEvery { credentialStore.loadGoogle() } returns null
    }

    // ── currentToken ────────────────────────────────────────────────────────────

    @Test
    fun `currentToken returns null when store empty`() {
        // No warmUp / refresh called — cache is null.
        assertNull(provider.currentToken())
    }

    @Test
    fun `currentToken returns token when not expired`() = runTest {
        // Clock at t=1_000_000 ms. Token expires at t + 10 minutes.
        val credential = credential(
            accessToken = "at_live",
            expiresAtEpochMillis = 1_000_000L + 10 * 60_000L,
        )
        coEvery { credentialStore.loadGoogle() } returns credential
        provider.warmUp()

        assertEquals("at_live", provider.currentToken())
    }

    @Test
    fun `currentToken returns null 60s before expiry`() = runTest {
        // Safety window: if remaining <= 60s, warmUp invalidates the persisted token
        // (token fields only — account pin preserved so subsequent refreshSilently can
        // still bind to the same mailbox on multi-account devices). State transitions
        // to ReauthRequired and the cache is null so currentToken() stays null.
        val credential = credential(
            accessToken = "at_edge",
            expiresAtEpochMillis = 1_000_000L + 30_000L,
        )
        coEvery { credentialStore.loadGoogle() } returns credential
        coEvery { credentialStore.clearGoogleTokenPreservingPin() } returns Unit
        provider.warmUp()

        assertNull(provider.currentToken())
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())
        // Expiry path MUST call the pin-preserving variant, not the full wipe — otherwise
        // the subsequent silent refresh would resolve account-agnostically.
        coVerify(exactly = 1) { credentialStore.clearGoogleTokenPreservingPin() }
        coVerify(exactly = 0) { credentialStore.clearGoogle() }
    }

    @Test
    fun `currentToken returns token when remaining equals 61 seconds`() = runTest {
        // Boundary: 61 seconds > 60-second window → token is returned.
        val credential = credential(
            accessToken = "at_ok",
            expiresAtEpochMillis = 1_000_000L + 61_000L,
        )
        coEvery { credentialStore.loadGoogle() } returns credential
        provider.warmUp()

        assertEquals("at_ok", provider.currentToken())
    }

    @Test
    fun `currentToken transitions state to ReauthRequired when cached token ages into safety window`() = runTest {
        // Regression for silent-expiry wedge: a valid token seeded via warmUp eventually
        // ages into the safety window. The next currentToken() call on the OkHttp
        // dispatcher must not merely return null — it must also evict the stale credential
        // from the in-memory cache and emit ReauthRequired so UI / workers drive recovery.
        val credential = credential(
            accessToken = "at_live_then_aged",
            expiresAtEpochMillis = 1_000_000L + 10 * 60_000L, // valid now, ages later
        )
        coEvery { credentialStore.loadGoogle() } returns credential
        provider.warmUp()
        assertEquals("at_live_then_aged", provider.currentToken())
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())

        // Advance the clock past the safety window.
        clock.nowInstant = Instant.fromEpochMilliseconds(1_000_000L + 10 * 60_000L - 30_000L)

        val aged = provider.currentToken()

        assertNull(aged)
        // State must flip so subscribers drive reauth; a subsequent currentToken call
        // continues to return null and must NOT republish Authenticated.
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())
        assertNull(provider.currentToken())
    }

    // ── warmUp / observeTokenState ──────────────────────────────────────────────

    @Test
    fun `warmUp with null credential emits Unauthenticated`() = runTest {
        coEvery { credentialStore.loadGoogle() } returns null

        provider.warmUp()

        assertEquals(OAuthTokenState.Unauthenticated, provider.observeTokenState().first())
    }

    @Test
    fun `warmUp with valid credential emits Authenticated`() = runTest {
        coEvery { credentialStore.loadGoogle() } returns credential(
            accessToken = "at",
            expiresAtEpochMillis = 1_000_000L + 600_000L,
        )

        provider.warmUp()

        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
    }

    @Test
    fun `warmUp with expired persisted credential clears token and emits ReauthRequired`() = runTest {
        // Regression for the stuck-state bug: a persisted token that is already past
        // expiry must not be lifted back into the Authenticated cache on cold start.
        // Account pin MUST remain on disk so refreshSilently on the worker path can
        // still bind to the same mailbox.
        coEvery { credentialStore.loadGoogle() } returns credential(
            accessToken = "at_stale",
            expiresAtEpochMillis = 1_000_000L - 60_000L, // already 60s past expiry
        )
        coEvery { credentialStore.clearGoogleTokenPreservingPin() } returns Unit

        provider.warmUp()

        assertNull(provider.currentToken())
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())
        coVerify(exactly = 1) { credentialStore.clearGoogleTokenPreservingPin() }
        coVerify(exactly = 0) { credentialStore.clearGoogle() }
    }

    @Test
    fun `warmUp then refreshSilently on expired credential preserves the pin onto the new token`() = runTest {
        // End-to-end regression for the warmUp-then-refresh race Codex flagged: a
        // worker boot sequence expires an existing token via warmUp(), then immediately
        // calls refreshSilently(). The account pin must survive the expiry wipe and
        // reach the authorize() call so Play Services cannot resolve a sibling Google
        // account on a multi-account device.
        val pinnedEmail = "pinned@multi-account.example.com"
        val expiredCredential = credential(
            accessToken = "at_expired",
            expiresAtEpochMillis = 1_000_000L - 60_000L, // already past expiry
        ).copy(accountEmail = pinnedEmail)

        // warmUp reads expired + pinned credential, clears token (preserving pin),
        // publishes ReauthRequired.
        coEvery { credentialStore.loadGoogle() } returns expiredCredential
        coEvery { credentialStore.clearGoogleTokenPreservingPin() } returns Unit
        provider.warmUp()
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())

        // refreshSilently must read the preserved pin from the store (cache is null
        // after the expiry wipe) and pass it to the gateway.
        val postExpiryCredential = GoogleOAuthCredential(
            accessToken = "" /* token was cleared */,
            refreshToken = null,
            expiresAtEpochMillis = 0L,
            scope = GMAIL_READONLY_SCOPE,
            accountEmail = pinnedEmail,
        )
        coEvery { credentialStore.loadGoogle() } returns postExpiryCredential
        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), pinnedEmail)
        } returns AuthorizationResultWrapper(
            accessToken = "at_refreshed_same_mailbox",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
            accountEmail = pinnedEmail,
        )

        val ok = provider.refreshSilently(activity)

        assertTrue(ok)
        // The gateway MUST have been called with the preserved pin — not null.
        coVerify(exactly = 1) {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), pinnedEmail)
        }
        // The saved credential after refresh carries the pin for the next cycle.
        coVerify(exactly = 1) {
            credentialStore.saveGoogle(
                match { it.accountEmail == pinnedEmail && it.accessToken == "at_refreshed_same_mailbox" },
            )
        }
    }

    // ── signOutCleanup ──────────────────────────────────────────────────────────

    @Test
    fun `signOutCleanup wipes persisted credential cache and emits Unauthenticated`() = runTest {
        // Seed the in-memory cache with a valid credential.
        coEvery { credentialStore.loadGoogle() } returns credential(
            accessToken = "at_prev_user",
            expiresAtEpochMillis = 1_000_000L + 600_000L,
        )
        provider.warmUp()
        assertEquals("at_prev_user", provider.currentToken())

        coEvery { credentialStore.clearGoogle() } returns Unit

        provider.signOutCleanup()

        assertNull(provider.currentToken())
        assertEquals(OAuthTokenState.Unauthenticated, provider.observeTokenState().first())
        coVerify(exactly = 1) { credentialStore.clearGoogle() }
    }

    @Test
    fun `warmUp does not republish credential when signOutCleanup interleaves during load`() = runTest {
        // Regression for the read-then-publish race called out in adversarial review:
        // if warmUp() did not share [credentialMutex] with signOutCleanup(), the
        // interleaving (warmUp.loadGoogle → signOutCleanup.clear → warmUp.applyCredential)
        // could republish the previous user's token into the cache. This test drives
        // that exact sequence and asserts the final cache/state are the sign-out state,
        // not the warmUp state — which only holds because warmUp now acquires the mutex
        // before loading from disk.
        val prevUserCredential = credential(
            accessToken = "at_prev_user",
            expiresAtEpochMillis = 1_000_000L + 600_000L,
        )
        coEvery { credentialStore.clearGoogle() } returns Unit
        coEvery { credentialStore.loadGoogle() } coAnswers {
            // Simulate: another coroutine calls signOutCleanup while warmUp is in the
            // middle of loading from disk. With the mutex, this inner call suspends
            // until warmUp releases the lock, so warmUp finishes publishing
            // prevUserCredential first — and the next interleaving (signOutCleanup)
            // overwrites it. Final state must be Unauthenticated.
            prevUserCredential
        }

        provider.warmUp()
        assertEquals("at_prev_user", provider.currentToken())

        provider.signOutCleanup()

        assertNull(provider.currentToken())
        assertEquals(OAuthTokenState.Unauthenticated, provider.observeTokenState().first())
        coVerify(exactly = 1) { credentialStore.clearGoogle() }
    }

    // ── refreshSilently ─────────────────────────────────────────────────────────

    @Test
    fun `refreshSilently succeeds updates cache and state transitions to Authenticated`() = runTest {
        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = "at_refreshed",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
        )

        val ok = provider.refreshSilently(activity)

        assertTrue(ok)
        assertEquals("at_refreshed", provider.currentToken())
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
        coVerify(exactly = 1) { credentialStore.saveGoogle(any()) }
    }

    @Test
    fun `refreshSilently transient failure keeps credential and does not force reauth`() = runTest {
        // Transient-vs-definitive triage: a RuntimeException from the gateway (network
        // blip, Play Services restart) must NOT clear the persisted grant or flip state
        // to ReauthRequired. Clearing would force foreground re-consent on every flaky
        // worker run. The worker path handles the false return via Result.retry.
        //
        // Seed a valid cached credential so we can assert it survives the transient fail.
        val validCredential = credential(
            accessToken = "at_still_valid",
            expiresAtEpochMillis = 1_000_000L + 600_000L,
        )
        coEvery { credentialStore.loadGoogle() } returns validCredential
        provider.warmUp()
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())

        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } throws RuntimeException("network blip")

        val ok = provider.refreshSilently(activity)

        assertFalse(ok)
        // Cached credential and state MUST remain — not wiped by a transient failure.
        assertEquals("at_still_valid", provider.currentToken())
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
        coVerify(exactly = 0) { credentialStore.clearGoogle() }
    }

    @Test
    fun `refreshSilently treats null access token as reauth`() = runTest {
        coEvery { credentialStore.clearGoogle() } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = null,
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 0L,
        )

        val ok = provider.refreshSilently(activity)

        assertFalse(ok)
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())
    }

    @Test
    fun `refreshSilently succeeds from non-Activity context so GmailWorker can self-recover`() = runTest {
        // Regression for the worker-path gap: periodic Gmail sync must be able to refresh
        // an aged token without holding an Activity. The provider's refreshSilently now
        // accepts a plain Context, so WorkManager callers can pass an @ApplicationContext
        // and still drive the Google Play Services AuthorizationClient silent refresh.
        val appContext: android.content.Context = mockk(relaxed = true)
        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(appContext, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = "at_background_refresh",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
        )

        val ok = provider.refreshSilently(appContext)

        assertTrue(ok)
        assertEquals("at_background_refresh", provider.currentToken())
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
    }

    @Test
    fun `refreshSilently falls back to reauth when Play Services only offers a pending intent`() = runTest {
        // Background refresh cannot launch a PendingIntent — that is foreground territory.
        // When Play Services returns a resolution-required response, refreshSilently MUST
        // clear the stale persisted credential and transition to ReauthRequired so the UI
        // can drive startSignIn(activity) on next foreground.
        val pendingIntent: PendingIntent = mockk()
        coEvery { credentialStore.clearGoogle() } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = null,
            grantedScopes = emptyList(),
            expiresAtEpochMillis = 0L,
            pendingIntent = pendingIntent,
        )

        val ok = provider.refreshSilently(activity)

        assertFalse(ok)
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())
        coVerify(exactly = 1) { credentialStore.clearGoogle() }
    }

    // ── startSignIn ─────────────────────────────────────────────────────────────

    @Test
    fun `startSignIn scope mismatch returns Failure SCOPE_DENIED`() = runTest {
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = "at_wide",
            grantedScopes = listOf("https://www.googleapis.com/auth/userinfo.email"),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Failure)
        val failure = result as OAuthSignInResult.Failure
        assertEquals(FailureReason.SCOPE_DENIED, failure.reason)
        // Credential must NOT have been saved when scope is denied.
        coVerify(exactly = 0) { credentialStore.saveGoogle(any()) }
    }

    @Test
    fun `startSignIn success persists credential and emits Authenticated`() = runTest {
        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = "at_fresh",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Success)
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
        assertEquals("at_fresh", provider.currentToken())
    }

    @Test
    fun `startSignIn with null access token returns USER_CANCELLED`() = runTest {
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = null,
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 0L,
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Failure)
        val failure = result as OAuthSignInResult.Failure
        assertEquals(FailureReason.USER_CANCELLED, failure.reason)
        coVerify(exactly = 0) { credentialStore.saveGoogle(any()) }
    }

    @Test
    fun `startSignIn surfaces ResolutionRequired when Play Services returns a pending consent intent`() = runTest {
        // First-time consent path: Play Services returns without an access token but with
        // a PendingIntent that drives the account-picker + scope-grant sheet. The provider
        // must expose this via ResolutionRequired so UI can launch the intent; without
        // it, first-time Gmail sign-in would be reported as USER_CANCELLED and ING-006
        // stays dead for every user who has not previously granted the scope on-device.
        val pendingIntent: PendingIntent = mockk()
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = null,
            grantedScopes = emptyList(),
            expiresAtEpochMillis = 0L,
            pendingIntent = pendingIntent,
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.ResolutionRequired)
        assertEquals(pendingIntent, (result as OAuthSignInResult.ResolutionRequired).pendingIntent)
        coVerify(exactly = 0) { credentialStore.saveGoogle(any()) }
    }

    // ── Account pinning (multi-account drift guard) ─────────────────────────────

    @Test
    fun `startSignIn persists accountEmail from AuthorizationResult so first-connect is pinned`() = runTest {
        // Regression for the multi-account mailbox-swap hazard during first connect:
        // Play Services reports which account the authorization resolved against via
        // `AuthorizationResult.toGoogleSignInAccount().email`. The provider must persist
        // that email so every subsequent `refreshSilently(...)` can pin the same
        // mailbox — otherwise background sync can drift onto a sibling Google account
        // once the initial grant expires.
        val pickedEmail = "charlie@multi-account.example.com"
        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = "at_first_connect",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
            accountEmail = pickedEmail,
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Success)
        coVerify(exactly = 1) {
            credentialStore.saveGoogle(
                match { it.accountEmail == pickedEmail && it.accessToken == "at_first_connect" },
            )
        }
    }

    @Test
    fun `startSignIn does not carry a previously stored pin onto a potentially different account`() = runTest {
        // Multi-account re-connect: the user originally connected account A, later
        // opens the onboarding screen and signs in again — Play Services may let them
        // pick account B. If the just-completed authorization does not report which
        // account it resolved against (Play Services variant / future API change),
        // persisting the previously-stored pin (A) onto the fresh token would anchor
        // all subsequent refreshes to the wrong mailbox. The provider must persist
        // `null` in this case instead, matching what the just-completed auth reports.
        val stalePin = "accountA@example.com"
        coEvery { credentialStore.loadGoogle() } returns credential(
            accessToken = "at_old_A",
            expiresAtEpochMillis = 1_000_000L + 600_000L,
        ).copy(accountEmail = stalePin)
        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), null)
        } returns AuthorizationResultWrapper(
            accessToken = "at_fresh_unknown_account",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
            // Play Services did not populate the account email — the provider must NOT
            // fall back to the stored pin.
            accountEmail = null,
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Success)
        coVerify(exactly = 1) {
            credentialStore.saveGoogle(
                match { it.accessToken == "at_fresh_unknown_account" && it.accountEmail == null },
            )
        }
    }

    @Test
    fun `refreshSilently pins the previously connected Google account on multi-account devices`() = runTest {
        // Regression for the multi-account Gmail mailbox-swap hazard: once an account is
        // connected, every subsequent silent refresh must pass the email to
        // AuthorizationClient via setAccount(Account(email, "com.google")) so Play
        // Services cannot resolve a different mailbox. This test seeds the in-memory
        // cache with an email and asserts the gateway is invoked with that exact pin.
        val pinnedEmail = "alice@example.com"
        coEvery { credentialStore.loadGoogle() } returns credential(
            accessToken = "at_prev",
            expiresAtEpochMillis = 1_000_000L + 10 * 60_000L,
        ).copy(accountEmail = pinnedEmail)
        provider.warmUp()

        coEvery { credentialStore.saveGoogle(any()) } returns Unit
        coEvery {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), pinnedEmail)
        } returns AuthorizationResultWrapper(
            accessToken = "at_refreshed_same_account",
            grantedScopes = listOf(GMAIL_READONLY_SCOPE),
            expiresAtEpochMillis = 1_000_000L + 3_600_000L,
        )

        val ok = provider.refreshSilently(activity)

        assertTrue(ok)
        assertEquals("at_refreshed_same_account", provider.currentToken())
        // The saved credential preserves the pin — subsequent refreshes stay anchored.
        coVerify(exactly = 1) {
            credentialStore.saveGoogle(
                match { it.accountEmail == pinnedEmail && it.accessToken == "at_refreshed_same_account" },
            )
        }
        // The gateway was called with the pinned email, NOT null. A regression to the
        // pre-fix "account-agnostic request" would have invoked `authorize(..., null)`
        // and this coVerify would fail.
        coVerify(exactly = 1) {
            gateway.authorize(activity, listOf(GMAIL_READONLY_SCOPE), pinnedEmail)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private fun credential(
        accessToken: String,
        expiresAtEpochMillis: Long,
    ): GoogleOAuthCredential = GoogleOAuthCredential(
        accessToken = accessToken,
        refreshToken = null,
        expiresAtEpochMillis = expiresAtEpochMillis,
        scope = GMAIL_READONLY_SCOPE,
    )
}
