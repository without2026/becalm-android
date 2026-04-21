package com.becalm.android.data.remote.msgraph

import android.app.Activity
import android.content.Context
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.secure.MsGraphOAuthCredential
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.MS_GRAPH_MAIL_READ_SCOPE
import com.becalm.android.data.remote.gmail.FailureReason
import com.becalm.android.data.remote.gmail.OAuthSignInResult
import com.becalm.android.data.remote.gmail.OAuthTokenState
import com.becalm.android.data.remote.msgraph.MsGraphTokenProviderImpl.Companion.MAIL_READ_SCOPE
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

/**
 * Unit tests for [MsGraphTokenProviderImpl] (plan:
 * `docs/plans/repo-auth-msgraph-oauth-provider.md` § 5.3).
 *
 * Strategy:
 * - [OAuthCredentialStore] is mocked with [io.mockk]; `coEvery` drives the suspension
 *   contract and `coVerify` asserts writes.
 * - [ISingleAccountPublicClientApplication] (MSAL) is mocked with [io.mockk]. MSAL's
 *   callback-driven async APIs (`acquireToken`, `acquireTokenSilentAsync`,
 *   `getCurrentAccountAsync`, `signOut`) are invoked by the production code; tests
 *   capture the callback arg via [CapturingSlot] and call back synchronously — the
 *   `suspendCancellableCoroutine` wrapper then resumes the coroutine immediately.
 * - The MSAL client factory is injected via [MsGraphTokenProviderImpl.setMsalClientFactoryForTest]
 *   so no native MSAL library is loaded in the JVM test environment (pure JUnit, no
 *   Robolectric).
 * - [FakeClock] drives [MsGraphTokenProviderImpl.getAccessToken]'s expiry-window logic.
 *
 * Acceptance coverage (plan § 5.3 / § 6):
 * 1. `getAccessToken returns cached token when not expired`
 * 2. `getAccessToken refreshes silently when expired`
 * 3. `acquireTokenSilent throws MsalUiRequiredException → state = ReauthRequired, store cleared`
 * 4. `startSignIn success → store updated with ms_graph_* keys only (google_* untouched)`
 * 5. `scope requested = [Mail.Read, offline_access] exactly (no extras)`
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MsGraphTokenProviderImplTest {

    private lateinit var credentialStore: OAuthCredentialStore
    private lateinit var msal: ISingleAccountPublicClientApplication
    private lateinit var clock: FakeClock
    private lateinit var logger: RecordingLogger
    private lateinit var provider: MsGraphTokenProviderImpl
    private lateinit var appContext: Context

    private val activity: Activity = mockk(relaxed = true)

    @Before
    fun setUp() {
        credentialStore = mockk()
        msal = mockk()
        clock = FakeClock(nowInstant = Instant.fromEpochMilliseconds(1_000_000L))
        logger = RecordingLogger()
        appContext = mockk(relaxed = true)
        provider = MsGraphTokenProviderImpl(
            applicationContext = appContext,
            credentialStore = credentialStore,
            clock = clock,
            logger = logger,
            msalClientId = "test-client-id-00000000",
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        // Inject the mocked MSAL client without triggering PublicClientApplication's
        // native init path.
        provider.setMsalClientFactoryForTest { _: Context -> msal }

        // Default: empty store — individual tests override with coEvery.
        coEvery { credentialStore.loadMsGraph() } returns null
    }

    // ── Test 1: cached token path ────────────────────────────────────────────

    @Test
    fun `getAccessToken returns cached token when not expired`() = runTest {
        // Clock at t=1_000_000 ms; token expires at t + 10 minutes.
        val credential = MsGraphOAuthCredential(
            accessToken = "ms_at_live",
            refreshToken = null,
            expiresAtEpochMillis = 1_000_000L + 10 * 60_000L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "home-account-id",
        )
        coEvery { credentialStore.loadMsGraph() } returns credential

        val token = provider.getAccessToken()

        assertEquals("ms_at_live", token)
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
        // No MSAL silent refresh should have been invoked on the valid-cache path.
        coVerify(exactly = 0) { credentialStore.saveMsGraph(any()) }
    }

    // ── Test 2: silent refresh path ──────────────────────────────────────────

    @Test
    fun `getAccessToken refreshes silently when expired`() = runTest {
        val expiredCredential = MsGraphOAuthCredential(
            accessToken = "ms_at_stale",
            refreshToken = null,
            // Within the 60-second safety window.
            expiresAtEpochMillis = 1_000_000L + 30_000L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "home-account-id",
        )
        coEvery { credentialStore.loadMsGraph() } returns expiredCredential
        coEvery { credentialStore.saveMsGraph(any()) } returns Unit

        val account = mockAccount(id = "home-account-id", authority = "https://login.microsoftonline.com/common")
        stubCurrentAccountCallback(msal, onLoaded = account)

        val silentScopes = slot<List<String>>()
        stubAcquireTokenSilent(
            msal,
            capturedScopes = silentScopes,
            accessToken = "ms_at_refreshed",
            account = account,
            expiresOn = Date(1_000_000L + 3_600_000L),
        )

        val token = provider.getAccessToken()

        assertEquals("ms_at_refreshed", token)
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
        assertEquals(MAIL_READ_SCOPE, silentScopes.captured)
        coVerify(exactly = 1) {
            credentialStore.saveMsGraph(
                match {
                    it.accessToken == "ms_at_refreshed" &&
                        it.scope == MS_GRAPH_MAIL_READ_SCOPE &&
                        it.accountIdentifier == "home-account-id"
                },
            )
        }
    }

    // ── Test 3: reauth-required path ─────────────────────────────────────────

    @Test
    fun `acquireTokenSilent throws MsalUiRequiredException then state ReauthRequired and store cleared`() = runTest {
        val expiredCredential = MsGraphOAuthCredential(
            accessToken = "ms_at_stale",
            refreshToken = null,
            expiresAtEpochMillis = 1_000_000L + 30_000L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "home-account-id",
        )
        coEvery { credentialStore.loadMsGraph() } returns expiredCredential
        coEvery { credentialStore.clearMsGraph() } returns Unit

        val account = mockAccount("home-account-id", "https://login.microsoftonline.com/common")
        stubCurrentAccountCallback(msal, onLoaded = account)
        stubAcquireTokenSilentError(
            msal,
            error = MsalUiRequiredException("interaction_required", "refresh token expired"),
        )

        val token = provider.getAccessToken()

        assertNull(token)
        assertEquals(OAuthTokenState.ReauthRequired, provider.observeTokenState().first())
        coVerify(exactly = 1) { credentialStore.clearMsGraph() }
    }

    // ── Test 4: interactive sign-in persists ms_graph_* only ─────────────────

    @Test
    fun `startSignIn success persists credential in ms_graph_ namespace without touching google_`() = runTest {
        coEvery { credentialStore.saveMsGraph(any()) } returns Unit

        val account = mockAccount("new-home-account-id", "https://login.microsoftonline.com/common")
        val interactiveScopes = slot<List<String>>()
        stubAcquireTokenInteractive(
            msal,
            capturedScopes = interactiveScopes,
            accessToken = "ms_at_fresh",
            account = account,
            grantedScopes = arrayOf("Mail.Read", "offline_access"),
            expiresOn = Date(1_000_000L + 3_600_000L),
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Success)
        assertEquals(OAuthTokenState.Authenticated, provider.observeTokenState().first())
        assertEquals(MAIL_READ_SCOPE, interactiveScopes.captured)
        coVerify(exactly = 1) {
            credentialStore.saveMsGraph(
                match {
                    it.accessToken == "ms_at_fresh" &&
                        it.scope == MS_GRAPH_MAIL_READ_SCOPE &&
                        it.accountIdentifier == "new-home-account-id"
                },
            )
        }
        // Namespace isolation: the Google-side save API must never be invoked by the
        // MS Graph flow (Hilt-ordering safety — the Gmail provider has its own
        // startSignIn and this one must not leak cross-provider).
        coVerify(exactly = 0) { credentialStore.saveGoogle(any()) }
        coVerify(exactly = 0) { credentialStore.clearGoogle() }
    }

    // ── Test 5: scope set is exactly Mail.Read + offline_access ──────────────

    @Test
    fun `scope requested is exactly Mail Read and offline_access (no extras)`() = runTest {
        // Plan § 5.2 CRITICAL: exactly two scopes. This test exercises both code paths
        // that inject the scope set — startSignIn and the silent-refresh branch of
        // getAccessToken — so a regression that introduces a third scope anywhere (e.g.
        // adding "User.Read" for profile enrichment) breaks this assertion.

        // --- Silent refresh path ---
        val expiredCredential = MsGraphOAuthCredential(
            accessToken = "ms_at_stale",
            refreshToken = null,
            expiresAtEpochMillis = 1_000_000L + 30_000L,
            scope = MS_GRAPH_MAIL_READ_SCOPE,
            accountIdentifier = "home-account-id",
        )
        coEvery { credentialStore.loadMsGraph() } returns expiredCredential
        coEvery { credentialStore.saveMsGraph(any()) } returns Unit

        val account = mockAccount("home-account-id", "https://login.microsoftonline.com/common")
        stubCurrentAccountCallback(msal, onLoaded = account)
        val silentScopes = slot<List<String>>()
        stubAcquireTokenSilent(
            msal,
            capturedScopes = silentScopes,
            accessToken = "ms_at_refreshed",
            account = account,
            expiresOn = Date(1_000_000L + 3_600_000L),
        )

        provider.getAccessToken()

        assertEquals(listOf("Mail.Read", "offline_access"), silentScopes.captured)
        assertEquals(2, silentScopes.captured.size)

        // --- Interactive path ---
        val interactiveScopes = slot<List<String>>()
        stubAcquireTokenInteractive(
            msal,
            capturedScopes = interactiveScopes,
            accessToken = "ms_at_fresh2",
            account = account,
            grantedScopes = arrayOf("Mail.Read", "offline_access"),
            expiresOn = Date(1_000_000L + 3_600_000L),
        )

        provider.startSignIn(activity)

        assertEquals(listOf("Mail.Read", "offline_access"), interactiveScopes.captured)
        assertEquals(2, interactiveScopes.captured.size)
    }

    // ── Additional coverage — scope-denied branch ────────────────────────────

    @Test
    fun `startSignIn scope mismatch returns Failure SCOPE_DENIED`() = runTest {
        // MSAL grants a wider set than requested — provider must reject and not save.
        val account = mockAccount("home-account-id", "https://login.microsoftonline.com/common")
        stubAcquireTokenInteractive(
            msal,
            capturedScopes = slot(),
            accessToken = "ms_at_wide",
            account = account,
            grantedScopes = arrayOf("Mail.Read", "offline_access", "User.Read"),
            expiresOn = Date(1_000_000L + 3_600_000L),
        )

        val result = provider.startSignIn(activity)

        assertTrue(result is OAuthSignInResult.Failure)
        val failure = result as OAuthSignInResult.Failure
        assertEquals(FailureReason.SCOPE_DENIED, failure.reason)
        coVerify(exactly = 0) { credentialStore.saveMsGraph(any()) }
    }

    // ── Helpers: MSAL callback stubbing ──────────────────────────────────────

    private fun mockAccount(id: String, authority: String): IAccount {
        val account = mockk<IAccount>()
        every { account.id } returns id
        every { account.authority } returns authority
        return account
    }

    /**
     * Arranges the mocked MSAL client so `getCurrentAccountAsync(callback)` invokes
     * `onAccountLoaded(onLoaded)` synchronously. The test-side callback fires on the
     * same thread that calls `getAccessToken`, which — via [UnconfinedTestDispatcher]
     * and [kotlinx.coroutines.suspendCancellableCoroutine] — is the current coroutine
     * thread. `resume` runs immediately.
     */
    private fun stubCurrentAccountCallback(
        msal: ISingleAccountPublicClientApplication,
        onLoaded: IAccount?,
    ) {
        every { msal.getCurrentAccountAsync(any()) } answers {
            val cb = firstArg<ISingleAccountPublicClientApplication.CurrentAccountCallback>()
            cb.onAccountLoaded(onLoaded)
        }
    }

    /**
     * Arranges the mocked MSAL client so `acquireTokenSilentAsync(params)` invokes
     * `params.callback.onSuccess(...)` with an [IAuthenticationResult] carrying the
     * supplied values. Captures the scope list via [capturedScopes].
     */
    private fun stubAcquireTokenSilent(
        msal: ISingleAccountPublicClientApplication,
        capturedScopes: CapturingSlot<List<String>>,
        accessToken: String,
        account: IAccount,
        expiresOn: Date,
    ) {
        val authResult = mockk<IAuthenticationResult>().apply {
            every { this@apply.accessToken } returns accessToken
            every { this@apply.account } returns account
            every { this@apply.expiresOn } returns expiresOn
            every { this@apply.scope } returns arrayOf("Mail.Read", "offline_access")
        }
        every { msal.acquireTokenSilentAsync(any<AcquireTokenSilentParameters>()) } answers {
            val params = firstArg<AcquireTokenSilentParameters>()
            capturedScopes.captured = params.scopes
            params.callback.onSuccess(authResult)
        }
    }

    private fun stubAcquireTokenSilentError(
        msal: ISingleAccountPublicClientApplication,
        error: MsalException,
    ) {
        every { msal.acquireTokenSilentAsync(any<AcquireTokenSilentParameters>()) } answers {
            val params = firstArg<AcquireTokenSilentParameters>()
            (params.callback as SilentAuthenticationCallback).onError(error)
        }
    }

    /**
     * Arranges `acquireToken(params)` to invoke `params.callback.onSuccess(...)`.
     * `grantedScopes` is the array surfaced on the `IAuthenticationResult` — this is
     * what the provider compares against `MAIL_READ_SCOPE` for the scope-denied path.
     */
    private fun stubAcquireTokenInteractive(
        msal: ISingleAccountPublicClientApplication,
        capturedScopes: CapturingSlot<List<String>>,
        accessToken: String,
        account: IAccount,
        grantedScopes: Array<String>,
        expiresOn: Date,
    ) {
        val authResult = mockk<IAuthenticationResult>().apply {
            every { this@apply.accessToken } returns accessToken
            every { this@apply.account } returns account
            every { this@apply.expiresOn } returns expiresOn
            every { this@apply.scope } returns grantedScopes
        }
        every { msal.acquireToken(any<AcquireTokenParameters>()) } answers {
            val params = firstArg<AcquireTokenParameters>()
            capturedScopes.captured = params.scopes
            (params.callback as AuthenticationCallback).onSuccess(authResult)
        }
    }
}
