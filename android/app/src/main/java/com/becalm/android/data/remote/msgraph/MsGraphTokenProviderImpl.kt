package com.becalm.android.data.remote.msgraph

import android.app.Activity
import android.content.Context
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.secure.MsGraphOAuthCredential
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.MS_GRAPH_MAIL_READ_SCOPE
import com.becalm.android.data.remote.gmail.FailureReason
import com.becalm.android.data.remote.gmail.OAuthSignInResult
import com.becalm.android.data.remote.gmail.OAuthTokenState
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SilentAuthenticationCallback
import com.microsoft.identity.client.exception.MsalDeclinedScopeException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production implementation of [MsGraphTokenProvider] driven by Microsoft Authentication
 * Library (MSAL) in single-account mode.
 *
 * ## Scope lock (ING-007 MVP, spec `.spec/data-ingestion.spec.yml:71`)
 * The only scopes ever requested are those in [MAIL_READ_SCOPE] — `Mail.Read` plus the
 * standard OpenID `offline_access` scope that enables silent refresh. Any authorization
 * result granting a wider scope string is rejected with [OAuthSignInResult.Failure] /
 * [FailureReason.SCOPE_DENIED] and the credential is **not** persisted. Expanding to
 * `Mail.ReadWrite` / `Mail.Send` / `Calendars.Read` requires a new provider class — do
 * not add scopes here.
 *
 * ## PIPA invariant (spec 153)
 * Access tokens are written exclusively to [OAuthCredentialStore] (the Keystore-backed
 * encrypted prefs store) and are **never** transmitted to Railway. MSAL maintains its
 * own token cache on top of Android Account Manager; BeCalm mirrors the token here to
 * keep the single call path [getAccessToken] independent of MSAL's cache internals —
 * see plan doc `docs/plans/repo-auth-msgraph-oauth-provider.md` § 5.1 for the
 * defensive double-write rationale.
 *
 * ## Lifecycle
 * - [getAccessToken]: silent path; returns the cached bearer token when valid, otherwise
 *   invokes `acquireTokenSilent` to refresh. No [Activity] required — callable from
 *   [com.becalm.android.worker.ingestion.OutlookMailWorker].
 * - [startSignIn]: interactive consent; launches Chrome Custom Tabs via MSAL and persists
 *   the resulting credential on success. Requires an [Activity].
 * - [signOut]: wipes both MSAL's cache and the BeCalm credential store.
 * - [observeTokenState]: reuses the Gmail-side [OAuthTokenState] enum so UI can render a
 *   single, provider-agnostic auth-state surface.
 *
 * ## MSAL initialization
 * [singleAccountApp] is created lazily from `R.raw.msal_config` (plan § 5.3) on first use
 * — `PublicClientApplication.createSingleAccountPublicClientApplication(context, ...)` is
 * callback-based, wrapped here with [suspendCancellableCoroutine]. The expensive
 * native-library init happens on the dispatcher thread of the first caller (typically
 * the worker's IO thread or an onboarding coroutine); subsequent calls see a warm handle.
 *
 * ## Thread dispatch
 * MSAL callbacks resume on the main thread. [suspendCancellableCoroutine.resume] is
 * called directly from the callback — we do NOT shift to [ioDispatcher] before
 * resumption because MSAL's internal `onSuccess` is already dispatched on a worker
 * thread, and an extra `withContext(ioDispatcher)` would block that thread waiting on
 * our continuation machinery. Store writes only are wrapped in `withContext(ioDispatcher)`.
 */
@Singleton
public class MsGraphTokenProviderImpl @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val credentialStore: OAuthCredentialStore,
    private val clock: Clock,
    private val logger: Logger,
    @Named("msalClientId") private val msalClientId: String,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MsGraphTokenProvider {

    public companion object {
        /**
         * The only scope set ever requested by this provider. Stored as
         * `"Mail.Read offline_access"` (space-separated, MSAL convention) in
         * [OAuthCredentialStore.MS_GRAPH_MAIL_READ_SCOPE]; the Kotlin list form here is
         * what MSAL's `AcquireTokenParameters.setScopes(...)` expects.
         *
         * `offline_access` is the standard OpenID scope that enables silent refresh via
         * `acquireTokenSilent`. Without it every access-token renewal would require a
         * foreground interactive flow — unworkable for the background ingestion worker.
         */
        public val MAIL_READ_SCOPE: List<String> = listOf("Mail.Read", "offline_access")

        /** 60-second safety window — [getAccessToken] refreshes when the token is this close to expiry. */
        internal const val EXPIRY_SAFETY_WINDOW_MILLIS: Long = 60_000L

        /** Fallback lifetime when MSAL returns null `expiresOn`. Microsoft's standard access token lifetime is 1 hour. */
        internal const val DEFAULT_TOKEN_LIFETIME_MILLIS: Long = 3_600_000L

        private const val TAG: String = "MsGraphTokenProvider"
    }

    // ── State ────────────────────────────────────────────────────────────────────

    /**
     * In-memory mirror of the persisted [MsGraphOAuthCredential]. Writers publish new
     * values via [AtomicReference.set]; serialisation between writers is provided by
     * [credentialMutex].
     */
    private val cached = AtomicReference<MsGraphOAuthCredential?>(null)

    /** Serialises interactive sign-in, silent refresh, and sign-out. */
    private val credentialMutex = Mutex()

    private val stateFlow = MutableStateFlow(OAuthTokenState.Unauthenticated)

    /**
     * Lazily-initialised handle to MSAL's single-account client. Guarded by
     * [msalInitMutex] so parallel initializers cannot race the native library init.
     */
    private var singleAccountApp: ISingleAccountPublicClientApplication? = null
    private val msalInitMutex = Mutex()

    /**
     * Factory hook for the MSAL [ISingleAccountPublicClientApplication]. Production
     * uses [defaultMsalClientFactory] (which calls
     * `PublicClientApplication.createSingleAccountPublicClientApplication(...)`);
     * tests inject a fake returning a mocked client via [setMsalClientFactoryForTest].
     *
     * This indirection exists only because MSAL's static factory takes a raw
     * [Context] + resource ID and cannot be mocked without loading native libraries
     * on the JVM.
     */
    @Volatile
    private var msalClientFactory: suspend (Context) -> ISingleAccountPublicClientApplication =
        ::defaultMsalClientFactory

    // ── MsGraphTokenProvider ────────────────────────────────────────────────────

    /**
     * Returns the current MS Graph bearer token, silently refreshing when the cached
     * token is within [EXPIRY_SAFETY_WINDOW_MILLIS] of expiry.
     *
     * Call path:
     * 1. If no credential is cached, load from [OAuthCredentialStore] (disk read on IO).
     * 2. If the credential is comfortably valid (remaining > 60s), return immediately.
     * 3. Otherwise invoke MSAL `acquireTokenSilentAsync` with the cached account identifier.
     * 4. On success: persist the refreshed credential + update cache + emit [OAuthTokenState.Authenticated].
     * 5. On [MsalUiRequiredException]: clear the credential + emit [OAuthTokenState.ReauthRequired] + return null.
     * 6. On other [MsalException]: log + return null, leave the credential intact so a transient failure
     *    does not force re-consent.
     *
     * Safe to call from a WorkManager worker — no [Activity] required.
     */
    override suspend fun getAccessToken(): String? = credentialMutex.withLock {
        val current = cached.get() ?: credentialStore.loadMsGraph()?.also { cached.set(it) }
        if (current == null) {
            if (stateFlow.value != OAuthTokenState.Unauthenticated) {
                stateFlow.value = OAuthTokenState.Unauthenticated
            }
            return@withLock null
        }

        val nowMillis = clock.nowInstant().toEpochMilliseconds()
        val remaining = current.expiresAtEpochMillis - nowMillis
        if (remaining > EXPIRY_SAFETY_WINDOW_MILLIS) {
            if (stateFlow.value != OAuthTokenState.Authenticated) {
                stateFlow.value = OAuthTokenState.Authenticated
            }
            return@withLock current.accessToken
        }

        // Silent refresh path.
        return@withLock try {
            val msal = ensureMsalClient()
            val account = resolveCachedAccount(msal, current.accountIdentifier)
                ?: throw MsalUiRequiredException(
                    "no_account",
                    "MSAL cache has no account matching identifier=${current.accountIdentifier}",
                )
            val refreshed = acquireTokenSilent(msal, account)
            val refreshedCredential = MsGraphOAuthCredential(
                accessToken = refreshed.accessToken,
                refreshToken = null,
                expiresAtEpochMillis = refreshed.expiresOn?.time
                    ?: (nowMillis + DEFAULT_TOKEN_LIFETIME_MILLIS),
                scope = MS_GRAPH_MAIL_READ_SCOPE,
                accountIdentifier = refreshed.account.id,
            )
            withContext(ioDispatcher) { credentialStore.saveMsGraph(refreshedCredential) }
            cached.set(refreshedCredential)
            stateFlow.value = OAuthTokenState.Authenticated
            refreshedCredential.accessToken
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            if (t is MsalUiRequiredException) {
                logger.w(TAG, "getAccessToken: silent refresh requires reauth — clearing credential", t)
                withContext(ioDispatcher) { credentialStore.clearMsGraph() }
                cached.set(null)
                stateFlow.value = OAuthTokenState.ReauthRequired
            } else {
                // Transient — do not clear the persisted credential; caller can surface
                // Unauthorized to the worker retry path. Matches the Gmail-side triage.
                logger.w(TAG, "getAccessToken: transient MSAL failure — keeping credential", t)
            }
            null
        }
    }

    // ── Observable state ─────────────────────────────────────────────────────────

    /**
     * Surfaces the current [OAuthTokenState] so UI composables can drive reauth without
     * polling [getAccessToken]. Mirrors the Gmail-side `observeTokenState` contract.
     */
    public fun observeTokenState(): Flow<OAuthTokenState> = stateFlow.asStateFlow()

    // ── Interactive sign-in ──────────────────────────────────────────────────────

    /**
     * Runs an interactive MSAL authorization flow and, on success, persists the
     * resulting [MsGraphOAuthCredential] + updates the in-memory cache + emits
     * [OAuthTokenState.Authenticated].
     *
     * The only requested scope set is [MAIL_READ_SCOPE]. If MSAL reports granted scopes
     * that differ from this exact set, the credential is rejected with
     * [FailureReason.SCOPE_DENIED] — the plan § 5.2 constraint — rather than allowing a
     * wider grant to slip into the store.
     *
     * @param activity UI host — required for Chrome Custom Tabs launch.
     */
    public suspend fun startSignIn(activity: Activity): OAuthSignInResult = credentialMutex.withLock {
        try {
            val msal = ensureMsalClient()
            val authResult = acquireTokenInteractive(msal, activity)
            if (!grantedScopesMatch(authResult.scope.toList())) {
                logger.w(
                    TAG,
                    "startSignIn: scope mismatch — requested=$MAIL_READ_SCOPE granted=${authResult.scope.toList()}",
                )
                return@withLock OAuthSignInResult.Failure(FailureReason.SCOPE_DENIED)
            }
            val credential = MsGraphOAuthCredential(
                accessToken = authResult.accessToken,
                refreshToken = null,
                expiresAtEpochMillis = authResult.expiresOn?.time
                    ?: (clock.nowInstant().toEpochMilliseconds() + DEFAULT_TOKEN_LIFETIME_MILLIS),
                scope = MS_GRAPH_MAIL_READ_SCOPE,
                accountIdentifier = authResult.account.id,
            )
            withContext(ioDispatcher) { credentialStore.saveMsGraph(credential) }
            cached.set(credential)
            stateFlow.value = OAuthTokenState.Authenticated
            OAuthSignInResult.Success
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            mapFailure(t, verb = "startSignIn")
        }
    }

    // ── Sign-out cleanup ─────────────────────────────────────────────────────────

    /**
     * Wipes the MS Graph credential from disk + in-memory cache + MSAL's own cache.
     * Emits [OAuthTokenState.Unauthenticated] (not [OAuthTokenState.ReauthRequired]).
     *
     * Without wiping MSAL's cache, the next `getAccessToken` could silently return a
     * cached token belonging to the previous user — a cross-account data leak.
     * Idempotent — safe to call when no credential is present.
     */
    public suspend fun signOut(): Unit = credentialMutex.withLock {
        try {
            val msal = singleAccountApp
            if (msal != null) {
                signOutMsal(msal)
            }
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            // Log and continue — clearing our own store is still required even if MSAL
            // sign-out fails, so that the next getAccessToken cannot read the stale token.
            logger.w(TAG, "signOut: MSAL signOut threw; continuing to clear credential store", t)
        }
        withContext(ioDispatcher) { credentialStore.clearMsGraph() }
        cached.set(null)
        stateFlow.value = OAuthTokenState.Unauthenticated
        logger.d(TAG, "signOut: cleared MS Graph credential")
    }

    // ── Internals: MSAL initialization ───────────────────────────────────────────

    /**
     * Test-only hook to inject a pre-built MSAL client factory. Production code uses
     * [defaultMsalClientFactory]; unit tests assign a fake that returns a mocked
     * [ISingleAccountPublicClientApplication] without triggering native library init.
     */
    internal fun setMsalClientFactoryForTest(factory: suspend (Context) -> ISingleAccountPublicClientApplication) {
        this.msalClientFactory = factory
        // Drop any previously-cached handle so the next ensureMsalClient call re-inits.
        this.singleAccountApp = null
    }

    private suspend fun ensureMsalClient(): ISingleAccountPublicClientApplication {
        singleAccountApp?.let { return it }
        return msalInitMutex.withLock {
            singleAccountApp?.let { return@withLock it }
            val created = msalClientFactory(applicationContext)
            singleAccountApp = created
            created
        }
    }

    /**
     * Production MSAL factory — wraps
     * `PublicClientApplication.createSingleAccountPublicClientApplication(context, R.raw.msal_config, callback)`
     * into a suspending call via [suspendCancellableCoroutine].
     *
     * The [msalClientId] parameter is read from BuildConfig at inject time; MSAL's
     * file-only init API reads the client id from the JSON resource, so the constructor
     * currently keeps `msalClientId` available for a follow-up refactor that will
     * splice the id into the config via `manifestPlaceholders` at build time.
     */
    private suspend fun defaultMsalClientFactory(context: Context): ISingleAccountPublicClientApplication =
        suspendCancellableCoroutine { cont ->
            PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                com.becalm.android.R.raw.msal_config,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(app: ISingleAccountPublicClientApplication) {
                        cont.resume(app)
                    }

                    override fun onError(exception: MsalException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

    // ── Internals: MSAL calls wrapped as suspending fns ──────────────────────────

    private suspend fun acquireTokenInteractive(
        msal: ISingleAccountPublicClientApplication,
        activity: Activity,
    ): IAuthenticationResult = suspendCancellableCoroutine { cont ->
        val params = AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(MAIL_READ_SCOPE)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    cont.resume(result)
                }

                override fun onError(exception: MsalException) {
                    cont.resumeWithException(exception)
                }

                override fun onCancel() {
                    cont.resumeWithException(UserCancelledAuthException())
                }
            })
            .build()
        msal.acquireToken(params)
    }

    /**
     * Resolves the MSAL account record matching [accountIdentifier] from MSAL's cache
     * via the single-account `getCurrentAccountAsync` callback. Returns `null` when
     * MSAL has no current account or the identifier does not match — caller translates
     * this into an [MsalUiRequiredException] so the normal reauth path fires.
     */
    private suspend fun resolveCachedAccount(
        msal: ISingleAccountPublicClientApplication,
        accountIdentifier: String,
    ): IAccount? = suspendCancellableCoroutine { cont ->
        msal.getCurrentAccountAsync(
            object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (activeAccount != null && activeAccount.id == accountIdentifier) {
                        cont.resume(activeAccount)
                    } else {
                        cont.resume(null)
                    }
                }

                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {
                    // Account changed mid-flight — reauth is required. Returning null
                    // forces the caller to surface MsalUiRequired.
                    cont.resume(null)
                }

                override fun onError(exception: MsalException) {
                    cont.resumeWithException(exception)
                }
            },
        )
    }

    private suspend fun acquireTokenSilent(
        msal: ISingleAccountPublicClientApplication,
        account: IAccount,
    ): IAuthenticationResult = suspendCancellableCoroutine { cont ->
        val params = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(account.authority)
            .withScopes(MAIL_READ_SCOPE)
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    cont.resume(result)
                }

                override fun onError(exception: MsalException) {
                    cont.resumeWithException(exception)
                }
            })
            .build()
        msal.acquireTokenSilentAsync(params)
    }

    private suspend fun signOutMsal(msal: ISingleAccountPublicClientApplication): Unit =
        suspendCancellableCoroutine { cont ->
            msal.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    cont.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    // ── Internals: helpers ───────────────────────────────────────────────────────

    /**
     * Compares the MSAL-granted scopes against [MAIL_READ_SCOPE] with set semantics
     * (order-insensitive). MSAL may echo the granted scopes in a different order than
     * requested and/or include additional implicit scopes — the plan § 5.2 constraint
     * requires the granted set to be **exactly** `{Mail.Read, offline_access}`, nothing
     * wider.
     */
    private fun grantedScopesMatch(grantedScopes: List<String>): Boolean {
        val required = MAIL_READ_SCOPE.toSet()
        val granted = grantedScopes.toSet()
        return granted == required
    }

    /**
     * Translates an MSAL exception into an [OAuthSignInResult.Failure] per plan § 5.3:
     * - [MsalDeclinedScopeException] → [FailureReason.SCOPE_DENIED]
     * - [UserCancelledAuthException] → [FailureReason.USER_CANCELLED]
     * - other [MsalException] / [Throwable] → [FailureReason.UNKNOWN]
     *
     * [MsalUiRequiredException] on interactive start-sign-in is rare (interactive flow
     * already drives consent) and is handled as [FailureReason.UNKNOWN] so the caller
     * can surface it to crash reporting rather than silently pretending the flow
     * "succeeded with reauth"; the silent refresh path in [getAccessToken] handles the
     * common reauth case.
     */
    private fun mapFailure(t: Throwable, verb: String): OAuthSignInResult.Failure {
        logger.w(TAG, "$verb: failed reason=${t::class.simpleName}", t)
        val reason = when (t) {
            is UserCancelledAuthException -> FailureReason.USER_CANCELLED
            is MsalDeclinedScopeException -> FailureReason.SCOPE_DENIED
            else -> FailureReason.UNKNOWN
        }
        return OAuthSignInResult.Failure(reason = reason, throwable = t)
    }

    private class UserCancelledAuthException : RuntimeException("User cancelled MSAL consent")
}
