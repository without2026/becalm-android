package com.becalm.android.data.remote.gmail

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.secure.GoogleOAuthCredential
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore.Companion.GMAIL_READONLY_SCOPE
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Production implementation of [GoogleAuthTokenProvider] that drives the Credential
 * Manager + [com.google.android.gms.auth.api.identity.AuthorizationClient] OAuth flow
 * and persists the resulting access token in [OAuthCredentialStore].
 *
 * ## Scope lock (ING-006 MVP, spec `.spec/data-ingestion.spec.yml:62`)
 * The only scope ever requested is `https://www.googleapis.com/auth/gmail.readonly`
 * (constant [OAuthCredentialStore.GMAIL_READONLY_SCOPE]). Any authorization result that
 * does not include this scope is rejected with
 * [OAuthSignInResult.Failure] / [FailureReason.SCOPE_DENIED] and the credential is **not**
 * persisted. Expansion to modify/send scopes requires a new provider class — do not
 * add scopes here.
 *
 * ## PIPA invariant (spec 153)
 * Access tokens are written exclusively to [OAuthCredentialStore] — the Keystore-backed
 * store — and are **never** transmitted to Railway. `.spec/data-ingestion.spec.yml:153`
 * requires Gmail/Outlook OAuth tokens to live only on the device.
 *
 * ## Hot-path contract
 * [currentToken] is invoked on the OkHttp dispatcher thread per-request from
 * [GmailClientImpl]. It reads an in-memory [AtomicReference] cache with no disk, lock,
 * or blocking I/O. A 60-second safety window causes [currentToken] to return `null` when
 * the cached token is within 60s of expiring; the caller then falls through to its
 * `BecalmError.Unauthorized` error path, the UI subscribes to [observeTokenState]
 * for the subsequent [OAuthTokenState.ReauthRequired] signal, and a
 * [refreshSilently] / [startSignIn] round-trip repopulates the cache.
 *
 * ## Warm-up
 * [warmUp] loads the persisted credential (if any) into the cache and publishes the
 * resulting [OAuthTokenState]. It is idempotent and safe to call multiple times;
 * production callers invoke it once on app startup from a Hilt `EntryPoint` or the
 * Application onCreate path.
 *
 * ## Refresh semantics
 * [com.google.android.gms.auth.api.identity.AuthorizationClient] does not expose the
 * long-lived refresh token to the app. Silent refresh is implemented by re-invoking
 * `authorize(...)` — the platform returns a fresh access token when a device-cached
 * consent grant is still valid, and otherwise surfaces a failure that translates to
 * [OAuthTokenState.ReauthRequired].
 */
@Singleton
public class GoogleAuthTokenProviderImpl @Inject constructor(
    private val credentialStore: OAuthCredentialStore,
    private val clock: Clock,
    private val logger: Logger,
    private val authorizationClient: AuthorizationClientGateway,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : GoogleAuthTokenProvider {

    private companion object {
        /** 60-second safety window — [currentToken] returns null when the token is this close to expiry. */
        const val EXPIRY_SAFETY_WINDOW_MILLIS: Long = 60_000L

        const val TAG: String = "GoogleAuthTokenProvider"
    }

    // ── State ────────────────────────────────────────────────────────────────────

    /**
     * In-memory mirror of the persisted [GoogleOAuthCredential]. Accessed on every
     * [currentToken] call on the OkHttp dispatcher thread; must remain lock-free.
     *
     * Writers ([warmUp], [startSignIn], [refreshSilently]) publish new values via
     * [AtomicReference.set]. There is no CAS requirement because [startSignIn] and
     * [refreshSilently] are serialised by [credentialMutex].
     */
    private val cached = AtomicReference<GoogleOAuthCredential?>(null)

    /** Serialises interactive sign-in and silent refresh attempts. */
    private val credentialMutex = Mutex()

    private val stateFlow = MutableStateFlow(OAuthTokenState.Unauthenticated)

    // ── GoogleAuthTokenProvider (hot path) ───────────────────────────────────────

    /**
     * Returns the cached Google OAuth2 access token, or `null` when no credential is
     * cached / the cached credential is within [EXPIRY_SAFETY_WINDOW_MILLIS] of expiry.
     *
     * Called on the OkHttp dispatcher thread — must not touch disk or suspend.
     *
     * ## Expiry-side effect (lock-free)
     * On a hit against the safety window this method additionally evicts the stale
     * credential from the in-memory cache via [AtomicReference.compareAndSet] and
     * publishes [OAuthTokenState.ReauthRequired] so subscribers of [observeTokenState]
     * see the session flip to "needs re-auth" without waiting for the next
     * [warmUp] / [refreshSilently] call. Without this signal the provider could sit
     * in [OAuthTokenState.Authenticated] indefinitely while every Gmail request was
     * mapped to [com.becalm.android.core.result.BecalmError.Unauthorized] — a silent
     * production wedge.
     *
     * The persisted credential is intentionally NOT cleared here (that would require
     * disk I/O, which is forbidden on the OkHttp dispatcher thread). The next
     * [warmUp] invocation — which runs on startup and at the head of every
     * ingestion worker — detects the expiry inside the mutex and clears the store
     * atomically. Between now and that warmUp the cache mirrors the cleared state,
     * so `currentToken()` continues to return `null` without republishing
     * `Authenticated`.
     */
    override fun currentToken(): String? {
        val credential = cached.get() ?: return null
        val nowMillis = clock.nowInstant().toEpochMilliseconds()
        val remaining = credential.expiresAtEpochMillis - nowMillis
        if (remaining > EXPIRY_SAFETY_WINDOW_MILLIS) return credential.accessToken

        if (cached.compareAndSet(credential, null)) {
            // Only the winning CAS publishes the state change so concurrent hot-path
            // callers do not race to re-emit the same transition.
            stateFlow.value = OAuthTokenState.ReauthRequired
        }
        return null
    }

    // ── Observable state ─────────────────────────────────────────────────────────

    /**
     * Surfaces the current [OAuthTokenState] to UI observers so they can react to
     * [OAuthTokenState.ReauthRequired] without polling [currentToken].
     */
    public fun observeTokenState(): Flow<OAuthTokenState> = stateFlow.asStateFlow()

    // ── Warm-up ──────────────────────────────────────────────────────────────────

    /**
     * Populates the in-memory cache from [OAuthCredentialStore] on startup. Safe to
     * call multiple times — the last write wins. Emits the resulting [OAuthTokenState]
     * to [observeTokenState] subscribers.
     *
     * ## Expiry guard
     * A persisted credential whose `expiresAtEpochMillis` is within
     * [EXPIRY_SAFETY_WINDOW_MILLIS] of now (or already past) is treated as unusable:
     * the on-device store is cleared and [OAuthTokenState.ReauthRequired] is emitted.
     * Without this guard, [currentToken] would return `null` for an expired cached
     * credential (via the safety-window check) while the state stayed at
     * [OAuthTokenState.Authenticated] — a stuck state with no UI trigger to drive
     * recovery after a stale cold start.
     *
     * @return the [OAuthTokenState] published after the load.
     */
    public suspend fun warmUp(): OAuthTokenState = withContext(ioDispatcher) {
        // Serialise with [startSignIn], [refreshSilently], and [signOutCleanup] so a
        // mid-flight warmUp cannot republish a credential into the cache after
        // sign-out has cleared it. Without this lock, the interleaving
        //   warmUp.loadGoogle() → signOutCleanup.clearGoogle+cache+state → warmUp.applyCredential(oldCred)
        // leaves the in-memory cache in Authenticated with a token the user just
        // signed out of (cross-account leak regression).
        credentialMutex.withLock {
            val credential = credentialStore.loadGoogle()
                ?: return@withLock applyCredential(null)
            val remaining = credential.expiresAtEpochMillis - clock.nowInstant().toEpochMilliseconds()
            if (remaining <= EXPIRY_SAFETY_WINDOW_MILLIS) {
                logger.w(
                    TAG,
                    "warmUp: persisted token within safety window (remaining=${remaining}ms) — " +
                        "clearing token and requiring reauth (account pin preserved)",
                )
                // Preserve the account pin across the expiry wipe so a subsequent
                // refreshSilently call can still bind the authorization to the same
                // mailbox. Using [clearGoogle] (full wipe) would drop
                // [GoogleOAuthCredential.accountEmail] alongside the token, which would
                // let Play Services resolve a different Google account on a multi-account
                // device (Gmail mailbox-swap regression).
                credentialStore.clearGoogleTokenPreservingPin()
                cached.set(null)
                stateFlow.value = OAuthTokenState.ReauthRequired
                return@withLock OAuthTokenState.ReauthRequired
            }
            applyCredential(credential)
        }
    }

    // ── Interactive sign-in ──────────────────────────────────────────────────────

    /**
     * Runs an interactive Google authorization flow and, on success, persists the
     * resulting [GoogleOAuthCredential] + updates the in-memory cache + emits
     * [OAuthTokenState.Authenticated].
     *
     * The only requested scope is [GMAIL_READONLY_SCOPE]. Any authorization result that
     * does not include this scope is rejected with [FailureReason.SCOPE_DENIED].
     */
    public suspend fun startSignIn(activity: Activity): OAuthSignInResult = credentialMutex.withLock {
        try {
            // Initial connect: no account pin — Play Services shows the account picker if
            // the device has multiple Google accounts. The UI onboarding PR captures the
            // resulting email via the Credential Manager flow and re-invokes
            // `startSignIn` with a saved credential that `refreshSilently` can then pin.
            val result = authorizationClient.authorize(
                context = activity,
                scopes = listOf(GMAIL_READONLY_SCOPE),
                pinnedAccountEmail = null,
            )
            val accessToken = result.accessToken
            if (accessToken.isNullOrBlank()) {
                // AuthorizationClient returned without a bearer token. Two distinct cases:
                // 1. `pendingIntent != null` — first-time consent / incremental scope flow.
                //    Surface ResolutionRequired so UI launches the intent; the user finishes
                //    on the system consent sheet and the UI calls startSignIn again.
                // 2. `pendingIntent == null` — no consent path available (cancelled, offline,
                //    etc.); treat as user-cancelled.
                val pendingIntent = result.pendingIntent
                return@withLock if (pendingIntent != null) {
                    logger.d(TAG, "startSignIn: resolution required — surfacing PendingIntent to UI")
                    OAuthSignInResult.ResolutionRequired(pendingIntent)
                } else {
                    logger.w(TAG, "startSignIn: authorize returned null accessToken without resolution — treating as cancel")
                    OAuthSignInResult.Failure(FailureReason.USER_CANCELLED)
                }
            }
            if (!result.grantedScopes.contains(GMAIL_READONLY_SCOPE)) {
                logger.w(
                    TAG,
                    "startSignIn: scope mismatch — requested=$GMAIL_READONLY_SCOPE " +
                        "granted=${result.grantedScopes}",
                )
                return@withLock OAuthSignInResult.Failure(FailureReason.SCOPE_DENIED)
            }
            // Interactive sign-in is launched unpinned so the user can pick any Google
            // account. Therefore the ONLY safe source for `accountEmail` is the email
            // the just-completed authorization actually resolved to — we deliberately
            // do NOT fall back to a previously-stored pin, because doing so could
            // attach account A's pin to a freshly authorized account B token. If
            // Play Services does not populate the field, we persist null; subsequent
            // `refreshSilently` calls then run unpinned (the pre-fix multi-account
            // weakness), which is still strictly safer than persisting the wrong pin.
            // The UI onboarding PR captures the email via Credential Manager and
            // backfills it on next sign-in.
            val credential = GoogleOAuthCredential(
                accessToken = accessToken,
                refreshToken = null,
                expiresAtEpochMillis = result.expiresAtEpochMillis,
                scope = GMAIL_READONLY_SCOPE,
                accountEmail = result.accountEmail,
            )
            credentialStore.saveGoogle(credential)
            applyCredential(credential)
            OAuthSignInResult.Success
        } catch (t: Throwable) {
            // Always rethrow coroutine cancellation unchanged — if this coroutine is being
            // cancelled (Activity recreation, scope teardown), the caller's structured
            // concurrency must see a real CancellationException, not our "Failure". Only
            // non-cancellation throwables translate to an OAuth failure.
            t.rethrowIfCancellation()
            mapFailure(t, verb = "startSignIn")
        }
    }

    // ── Silent refresh ───────────────────────────────────────────────────────────

    /**
     * Re-runs [AuthorizationClientGateway.authorize]; on success updates the store +
     * cache + emits [OAuthTokenState.Authenticated]. On failure (including
     * "resolution required") clears the store and emits
     * [OAuthTokenState.ReauthRequired] so the UI can prompt for re-consent.
     *
     * [context] is declared as [Context] rather than [Activity] so the periodic
     * `GmailWorker` can invoke this from the background with an `@ApplicationContext`.
     * Because the flow is explicitly silent, a `PendingIntent` returned by Google Play
     * Services is **not** launched — the provider treats it as "the cached grant is no
     * longer sufficient" and requires foreground reauth. Interactive reauth goes
     * through [startSignIn] with an `Activity`.
     *
     * @return `true` when refresh succeeded and a fresh credential was published.
     */
    public suspend fun refreshSilently(context: Context): Boolean = credentialMutex.withLock {
        try {
            // Pin the Google account that was recorded when the user first connected so
            // Play Services cannot silently resolve a different mailbox on a multi-account
            // device. When the pin is null (pre-Credential-Manager onboarding upgrade),
            // Play Services falls back to the default-account grant associated with the
            // previously-cached access token — functionally correct but weaker isolation;
            // the onboarding PR backfills the pin on next interactive sign-in.
            val pinnedAccountEmail = cached.get()?.accountEmail
                ?: credentialStore.loadGoogle()?.accountEmail
            val result = authorizationClient.authorize(
                context = context,
                scopes = listOf(GMAIL_READONLY_SCOPE),
                pinnedAccountEmail = pinnedAccountEmail,
            )
            val accessToken = result.accessToken
            if (accessToken.isNullOrBlank() ||
                !result.grantedScopes.contains(GMAIL_READONLY_SCOPE)
            ) {
                // `result.pendingIntent != null` means Play Services wants a user consent
                // resolution — that is foreground UI territory, not background refresh.
                // Both paths are definitive signals from Play Services that the current
                // grant no longer covers what we need, so clearing the persisted
                // credential is correct here (not a transient-failure wipe).
                logger.w(
                    TAG,
                    "refreshSilently: missing access token or scope " +
                        "(resolutionRequired=${result.pendingIntent != null}); forcing reauth",
                )
                forceReauth()
                return@withLock false
            }
            val credential = GoogleOAuthCredential(
                accessToken = accessToken,
                refreshToken = null,
                expiresAtEpochMillis = result.expiresAtEpochMillis,
                scope = GMAIL_READONLY_SCOPE,
                // Prefer the refreshed result's reported email so the pin self-heals if
                // the initial connect did not populate it; fall back to the pin we
                // already hold so a temporary Play Services downgrade cannot erase it.
                accountEmail = result.accountEmail ?: pinnedAccountEmail,
            )
            credentialStore.saveGoogle(credential)
            applyCredential(credential)
            true
        } catch (t: Throwable) {
            // Rethrow coroutine cancellation unchanged so structured-concurrency teardown
            // (Activity recreation, scope cancellation) does not get laundered into a
            // fake auth failure that clears a perfectly good persisted credential.
            t.rethrowIfCancellation()
            // Transient-vs-definitive triage: a network blip, Play Services restart, or
            // momentarily-unavailable device (ApiException/IOException) is NOT a reason
            // to wipe the persisted grant — doing so would force the user through
            // foreground re-consent every time their connection flakes. Only clear when
            // Play Services explicitly tells us the grant is gone (handled above as
            // the null-access-token / scope-mismatch / PendingIntent branches).
            //
            // For unclassified throwables we keep the credential but return false so the
            // worker surfaces Result.retry(). The state stays Authenticated so UI does
            // not spuriously prompt for reauth on every transient failure.
            logger.w(TAG, "refreshSilently: transient failure — keeping credential, returning false", t)
            false
        }
    }

    // ── Sign-out cleanup ─────────────────────────────────────────────────────────

    /**
     * Wipes the Gmail OAuth credential from disk and in-memory cache as part of the
     * user sign-out / session-invalidate flow in
     * [com.becalm.android.data.repository.AuthRepository]. Emits
     * [OAuthTokenState.Unauthenticated] (not [OAuthTokenState.ReauthRequired]) because
     * the user deliberately ended the session — there is no valid grant to resume.
     *
     * Without this method, the previous account's persisted bearer token would
     * survive the sign-out, and a subsequent sign-in on the same device could resume
     * Gmail ingestion against the previous Google grant (cross-account data leak).
     * Idempotent — safe to call when no credential is present.
     */
    public suspend fun signOutCleanup(): Unit = credentialMutex.withLock {
        credentialStore.clearGoogle()
        cached.set(null)
        stateFlow.value = OAuthTokenState.Unauthenticated
        logger.d(TAG, "signOutCleanup: cleared Gmail OAuth credential")
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    /**
     * Publishes [credential] to the in-memory cache and recomputes [OAuthTokenState].
     * Called from both [warmUp] and the success arms of [startSignIn] / [refreshSilently].
     */
    private fun applyCredential(credential: GoogleOAuthCredential?): OAuthTokenState {
        cached.set(credential)
        val nextState = if (credential == null) {
            OAuthTokenState.Unauthenticated
        } else {
            OAuthTokenState.Authenticated
        }
        stateFlow.value = nextState
        return nextState
    }

    /**
     * Silent refresh failed — clear the persisted credential and transition to
     * [OAuthTokenState.ReauthRequired] so the UI can surface the sign-in prompt again.
     */
    private suspend fun forceReauth() {
        credentialStore.clearGoogle()
        cached.set(null)
        stateFlow.value = OAuthTokenState.ReauthRequired
    }

    /**
     * Translates an exception thrown from [AuthorizationClientGateway.authorize] into
     * an [OAuthSignInResult.Failure]. Non-matching throwables fall through to
     * [FailureReason.UNKNOWN] with the original [Throwable] attached for Sentry.
     */
    private fun mapFailure(t: Throwable, verb: String): OAuthSignInResult.Failure {
        logger.w(TAG, "$verb: failed reason=${t::class.simpleName}", t)
        val reason = when (t) {
            is ApiException -> FailureReason.PLAY_SERVICES_UNAVAILABLE
            is IOException -> FailureReason.NETWORK
            else -> FailureReason.UNKNOWN
        }
        return OAuthSignInResult.Failure(reason = reason, throwable = t)
    }
}

// ── Gateway abstraction (mockable AuthorizationClient wrapper) ───────────────────

/**
 * Thin abstraction over [com.google.android.gms.auth.api.identity.AuthorizationClient]
 * so that [GoogleAuthTokenProviderImpl] can be unit-tested without loading Google Play
 * classes that require an actual Activity.
 *
 * Production binding: [AuthorizationClientGatewayImpl]. Tests provide a hand-written
 * fake.
 */
public interface AuthorizationClientGateway {

    /**
     * Requests a scoped access token for [scopes] on behalf of the user signed in to
     * [context].
     *
     * [context] is declared as [Context] rather than [Activity] so background paths
     * ([GoogleAuthTokenProviderImpl.refreshSilently] from a WorkManager worker) can
     * participate in silent refresh without holding an Activity reference. Interactive
     * paths ([GoogleAuthTokenProviderImpl.startSignIn]) pass an Activity (which is a
     * Context), so the same gateway serves both.
     *
     * [pinnedAccountEmail] pins the authorization request to a specific Google account
     * (`AuthorizationRequest.Builder.setAccount(Account(email, "com.google"))`). When
     * null, Play Services resolves the default account — acceptable on the initial
     * connect path but required to be non-null on silent refresh to prevent multi-account
     * mailbox drift.
     *
     * On success returns an [AuthorizationResultWrapper]. The caller is responsible
     * for checking [AuthorizationResultWrapper.accessToken] and
     * [AuthorizationResultWrapper.grantedScopes]. Throws an exception on transport /
     * Play Services / consent failure; [GoogleAuthTokenProviderImpl] translates these
     * into [OAuthSignInResult.Failure] reasons.
     */
    public suspend fun authorize(
        context: Context,
        scopes: List<String>,
        pinnedAccountEmail: String?,
    ): AuthorizationResultWrapper
}

/**
 * Normalised view of an [AuthorizationResult]. Tests construct instances directly;
 * [AuthorizationClientGatewayImpl] builds one from the Play Services response.
 *
 * Exactly one of the following holds for any wrapper returned on a success path:
 * - [accessToken] is non-null → caller persists the credential and emits
 *   [OAuthTokenState.Authenticated].
 * - [pendingIntent] is non-null → first-time consent / incremental scope grant is
 *   required; caller returns [OAuthSignInResult.ResolutionRequired] so the UI can
 *   launch the intent and re-invoke `startSignIn` on completion.
 *
 * @param accessToken          The granted bearer token, or `null` when Play Services
 *   requires interactive consent ([pendingIntent] will then be non-null).
 * @param grantedScopes        The scopes actually granted by the user; callers must
 *   check membership of the requested scope and reject on mismatch.
 * @param expiresAtEpochMillis Epoch millis when [accessToken] ceases to be valid.
 *   Meaningful only when [accessToken] is non-null.
 * @param pendingIntent        Non-null when Play Services needs a foreground resolution
 *   (first-time consent, incremental scope grant, account picker). The UI launches this
 *   via `ActivityResultLauncher` / `startIntentSenderForResult`.
 * @param accountEmail         Best-effort email of the Google account the authorization
 *   resolved against, sourced from `AuthorizationResult.toGoogleSignInAccount()?.email`.
 *   Null when Play Services did not populate the field (may happen on the first call of
 *   a resolution-required flow). Callers persist this to pin subsequent silent refreshes
 *   to the same mailbox (multi-account drift guard).
 */
public data class AuthorizationResultWrapper(
    val accessToken: String?,
    val grantedScopes: List<String>,
    val expiresAtEpochMillis: Long,
    val pendingIntent: PendingIntent? = null,
    val accountEmail: String? = null,
)

/**
 * Production [AuthorizationClientGateway] that calls
 * [com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient] and
 * bridges the returned [com.google.android.gms.tasks.Task] to a coroutine via
 * [suspendCancellableCoroutine].
 *
 * **Deliberate choice**: we do not pull in `kotlinx-coroutines-play-services` just for
 * the `Task.await` extension. The bridge is a few lines and avoids a transitive dep
 * on Google Play Tasks' coroutine module.
 */
public class AuthorizationClientGatewayImpl @Inject constructor() : AuthorizationClientGateway {

    override suspend fun authorize(
        context: Context,
        scopes: List<String>,
        pinnedAccountEmail: String?,
    ): AuthorizationResultWrapper {
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(scopes.map { Scope(it) })
            .apply {
                // Pin to a specific Google account so a silent refresh on a multi-account
                // device cannot resolve against a different mailbox than the one the user
                // originally granted (Gmail mailbox-swap guard).
                if (pinnedAccountEmail != null) {
                    setAccount(
                        android.accounts.Account(
                            pinnedAccountEmail,
                            "com.google",
                        ),
                    )
                }
            }
            .build()
        val result: AuthorizationResult = suspendCancellableCoroutine { cont ->
            // Context-variant of getAuthorizationClient: returned AuthorizationClient can
            // still surface a PendingIntent for resolution; launching that intent requires
            // an Activity (handled by the interactive caller `startSignIn`). Background
            // silent callers ([refreshSilently]) read `accessToken` and treat a
            // resolution-required response as a reauth signal instead of launching the
            // intent.
            Identity.getAuthorizationClient(context)
                .authorize(request)
                .addOnSuccessListener { authResult -> cont.resume(authResult) }
                .addOnFailureListener { t -> cont.resumeWithException(t) }
        }
        return AuthorizationResultWrapper(
            accessToken = result.accessToken,
            grantedScopes = result.grantedScopes.map { it.toString() },
            // AuthorizationResult does not currently expose an explicit expiry — Google's
            // default access token lifetime is 3600 seconds. Using `now + 3600s` keeps the
            // 60-second safety window in [GoogleAuthTokenProviderImpl.currentToken]
            // meaningful without requiring additional API calls.
            expiresAtEpochMillis = System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MILLIS,
            // Propagate the first-time-consent / incremental-scope resolution intent so
            // [GoogleAuthTokenProviderImpl.startSignIn] can surface
            // [OAuthSignInResult.ResolutionRequired] to the UI. If the user already granted
            // the Gmail scope on this device, `hasResolution()` is false and this stays
            // null (the happy path returns a non-null accessToken instead).
            pendingIntent = if (result.hasResolution()) result.pendingIntent else null,
            // Best-effort account email so the caller can pin subsequent silent refreshes
            // to the same Google account. `toGoogleSignInAccount()` returns null when the
            // AuthorizationResult carries only a PendingIntent (pre-consent) or when the
            // device Play Services version does not populate the field; callers fall
            // back to the previously-persisted pin in that case.
            accountEmail = runCatching { result.toGoogleSignInAccount()?.email }.getOrNull(),
        )
    }

    private companion object {
        /** Default Google OAuth2 access token lifetime (1 hour). */
        const val DEFAULT_TOKEN_LIFETIME_MILLIS: Long = 3_600_000L
    }
}
