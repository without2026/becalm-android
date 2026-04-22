package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.onSuccess
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ─── Auth state ──────────────────────────────────────────────────────────────

/**
 * Represents the current authentication state of the app.
 */
public sealed class AuthState {
    /** A valid session is present. */
    public data class Authenticated(val session: SupabaseSession) : AuthState()

    /** No session is present; the user must sign in. */
    public data object Unauthenticated : AuthState()
}

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Orchestrates all authentication flows for BeCalm Android.
 *
 * Delegates network operations to [SupabaseAuthClient] and coordinates the
 * full PIPA-compliant local wipe on sign-out (AUTH-005).
 */
public interface AuthRepository {

    /**
     * Signs in with email and password (AUTH-001).
     *
     * @return [BecalmResult.Success] with the session on success, or a typed failure.
     */
    public suspend fun signInWithEmail(email: String, password: String): BecalmResult<SupabaseSession>

    /**
     * Signs in with a Google ID token obtained from the Google Sign-In SDK (AUTH-002).
     *
     * @param idToken Raw JWT ID token returned by Google Sign-In.
     * @return [BecalmResult.Success] with the session on success, or a typed failure.
     */
    public suspend fun signInWithGoogle(idToken: String): BecalmResult<SupabaseSession>

    /**
     * Signs out and performs a full PIPA-compliant local data wipe (AUTH-005).
     *
     * Every wipe step is attempted regardless of individual step failures. The first
     * failure encountered is returned, but subsequent steps are never skipped.
     *
     * Use this only for the deliberate "로컬 데이터 전체 삭제" flow. For a routine sign-out
     * that preserves local Room data per the local-first spec invariant, call
     * [invalidateSession] instead.
     *
     * @return [BecalmResult.Success] if all wipe steps succeeded; the first [BecalmResult.Failure]
     *   encountered otherwise (remaining steps were still executed).
     */
    public suspend fun signOut(): BecalmResult<Unit>

    /**
     * Invalidates the current session without touching Room-persisted user data.
     *
     * Per the spec invariant "로그아웃 시 Room DB 데이터는 삭제하지 않는다", routine sign-out
     * must only clear authentication state (tokens, session store, current-user pref, worker
     * schedules keyed on the session) so that the user can sign back in and resume locally
     * cached content. This differs from [signOut], which additionally calls
     * `database.clearAllTables()` for the deliberate full PIPA wipe.
     *
     * Every step is attempted regardless of individual step failures; the first failure is
     * returned after all steps complete.
     *
     * @return [BecalmResult.Success] if all steps succeeded; the first [BecalmResult.Failure]
     *   encountered otherwise (remaining steps were still executed).
     */
    public suspend fun invalidateSession(): BecalmResult<Unit>

    /**
     * Exchanges the stored refresh token for a new session (AUTH-004 / AUTH-007).
     *
     * @return [BecalmResult.Failure] with [BecalmError.Unauthorized] when no session or blank
     *   refresh token is stored; otherwise the refreshed session or a network failure.
     */
    public suspend fun refreshSession(): BecalmResult<SupabaseSession>

    /**
     * Returns the currently persisted session, or `null` when no session is stored.
     */
    public suspend fun currentSession(): SupabaseSession?

    /**
     * Emits [AuthState] reactively: once on subscription (seeded from the session store)
     * and again on every subsequent sign-in / sign-out that mutates the persisted session.
     */
    public fun observeAuthState(): Flow<AuthState>
}

// ─── Implementation ──────────────────────────────────────────────────────────

private const val TAG = "AuthRepository"

/**
 * Production implementation of [AuthRepository].
 */
@Singleton
public class AuthRepositoryImpl @Inject constructor(
    private val authClient: SupabaseAuthClient,
    private val sessionStore: SupabaseSessionStore,
    private val tokenProvider: AuthTokenProvider,
    private val deviceKeyStore: DeviceKeyStore,
    private val syncCursorStore: SyncCursorStore,
    private val userPrefsStore: UserPrefsStore,
    private val databaseProvider: BeCalmDatabaseProvider,
    private val workScheduler: WorkScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val imapCredentialStore: ImapCredentialStore,
    // Concrete type (not the [com.becalm.android.data.remote.gmail.GoogleAuthTokenProvider]
    // interface) so the sign-out cleanup hook [GoogleAuthTokenProviderImpl.signOutCleanup]
    // is reachable. Without wiping the Gmail OAuth credential on sign-out, a second account
    // on the same device would inherit the previous user's Gmail grant on the next cold
    // start after [com.becalm.android.BecalmApplication] calls
    // [GoogleAuthTokenProviderImpl.warmUp] (cross-account data leak).
    private val googleAuthTokenProvider: GoogleAuthTokenProviderImpl,
    private val logger: Logger,
) : AuthRepository {

    // Broadcasts the latest session across save/clear boundaries so that observers of
    // [observeAuthState] re-emit after sign-in and sign-out. `null` means no session is
    // currently persisted. Seeded lazily from [sessionStore] on first collection.
    private val sessionFlow = MutableStateFlow<SupabaseSession?>(null)

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): BecalmResult<SupabaseSession> =
        authClient.signInWithEmail(email, password)
            .onSuccess { value ->
                userPrefsStore.setCurrentUserId(value.userId)
                // Bind the per-user SQLite file before any worker or repository touches
                // Room (S6-A PIPA cross-account leak defence). Idempotent on the happy path
                // when the same user signs in again.
                databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(value.userId))
                sessionFlow.value = value
                // Warm the token cache from persisted session so the first hot-path request
                // avoids a disk read (Round 6A.4).
                tokenProvider.primeCache()
            }

    override suspend fun signInWithGoogle(idToken: String): BecalmResult<SupabaseSession> =
        authClient.signInWithGoogleIdToken(idToken)
            .onSuccess { value ->
                userPrefsStore.setCurrentUserId(value.userId)
                databaseProvider.ensureOpenFor(BeCalmDatabase.deriveUserIdHash(value.userId))
                sessionFlow.value = value
                tokenProvider.primeCache()
            }

    override suspend fun signOut(): BecalmResult<Unit> {
        // PIPA wipe — every step runs regardless of prior step failures.
        // The first failure is captured and returned after all steps complete.
        // Order: cancel workers first, then clear enrichment + IMAP creds, then clear tokens.
        val preludeFailure = mutableListOf<BecalmResult.Failure>()
        val session = loadSessionForLogout(preludeFailure)

        val steps = buildList {
            add(NamedStep("cancelAllWorkers") { workScheduler.cancelAll() })
            add(NamedStep("stopContentObservers") { contentObserverBootstrap.stop() })
            if (session != null) {
                // Best-effort server-side revoke; always returns Success per authClient contract.
                add(NamedStep("serverRevoke") { authClient.signOut(session.accessToken) })
            }
            // personEnrichmentRepository.deleteAll() already returns BecalmResult<Int>;
            // wrap its throw semantics without double-wrapping the returned Result.
            add(NamedStep("personEnrichmentDeleteAll") { personEnrichmentRepository.deleteAll() })
            add(NamedStep("imapCredentialClear") { imapCredentialStore.clearAll() })
            // Wipe the Gmail OAuth credential (disk + in-memory cache) and transition the
            // OAuthTokenState to Unauthenticated so a subsequent account on the same device
            // cannot inherit the previous user's Gmail grant (PIPA cross-account leak guard).
            add(NamedStep("googleOAuthCleanup") { googleAuthTokenProvider.signOutCleanup() })
            add(NamedStep("sessionStoreClear") { sessionStore.clear() })
            // Drop the in-memory access-token cache in lockstep with the persisted session
            // so the next hot-path request re-consults storage and reflects the cleared state
            // (Round 6A.4). This is synchronous and always succeeds.
            add(NamedStep("tokenProviderInvalidate") { tokenProvider.invalidate() })
            add(NamedStep("deviceKeyClear") { deviceKeyStore.clear() })
            add(NamedStep("syncCursorClear") { syncCursorStore.clearAll() })
            add(NamedStep("userPrefsClearAll") { userPrefsStore.clearAll() })
            add(NamedStep("databaseClearAll") { databaseProvider.current().clearAllTables() })
            // Release the Room file handle so the next sign-in opens a fresh per-user file
            // without a lingering WAL lock (S6-A).
            add(NamedStep("databaseClose") { databaseProvider.close() })
        }

        val stepResult = runAllSteps("signOut", steps)

        // Broadcast the cleared state so observers transition to Unauthenticated.
        sessionFlow.value = null

        return preludeFailure.firstOrNull() ?: stepResult
    }

    override suspend fun invalidateSession(): BecalmResult<Unit> {
        // Session-only cleanup — preserves Room data per the local-first spec invariant
        // "로그아웃 시 Room DB 데이터는 삭제하지 않는다". Every step runs regardless of prior
        // step failures; the first failure is captured and returned after all steps complete.
        val preludeFailure = mutableListOf<BecalmResult.Failure>()
        val session = loadSessionForLogout(preludeFailure)

        val steps = buildList {
            add(NamedStep("cancelAllWorkers") { workScheduler.cancelAll() })
            add(NamedStep("stopContentObservers") { contentObserverBootstrap.stop() })
            if (session != null) {
                add(NamedStep("serverRevoke") { authClient.signOut(session.accessToken) })
            }
            // IMAP credentials are tied to the current account (not the raw Room event data),
            // so they are considered part of "session" and are cleared here.
            add(NamedStep("imapCredentialClear") { imapCredentialStore.clearAll() })
            // Same rationale applies to the Gmail OAuth credential: account-scoped grant,
            // cleared on every session-invalidate to prevent the next account from
            // inheriting it (cross-account leak guard).
            add(NamedStep("googleOAuthCleanup") { googleAuthTokenProvider.signOutCleanup() })
            add(NamedStep("sessionStoreClear") { sessionStore.clear() })
            // Drop the in-memory access-token cache in lockstep with the persisted session
            // so the next hot-path request re-consults storage (Round 6A.4).
            add(NamedStep("tokenProviderInvalidate") { tokenProvider.invalidate() })
            add(NamedStep("deviceKeyClear") { deviceKeyStore.clear() })
            // Clear the non-secret current-user-id mirror. Other UI preferences
            // (locale, notifications flag, onboarding completion) are intentionally preserved.
            add(NamedStep("currentUserIdClear") { userPrefsStore.setCurrentUserId(null) })
            // Release the Room file handle so the next sign-in (potentially as a
            // different user) opens the correct per-user file without a stale
            // [BeCalmDatabaseProvider] reference pointing at the prior user's DB (S6-A).
            add(NamedStep("databaseClose") { databaseProvider.close() })
        }

        // NOTE: Intentionally NOT calling databaseProvider.current().clearAllTables(),
        // personEnrichmentRepository.deleteAll(), syncCursorStore.clearAll(), or
        // userPrefsStore.clearAll() — those belong to the full PIPA wipe in [signOut].
        // The per-user DB file is preserved on disk; sign-in as the same user re-opens it.

        val stepResult = runAllSteps("invalidateSession", steps)

        // Broadcast the cleared state so observers transition to Unauthenticated.
        sessionFlow.value = null

        return preludeFailure.firstOrNull() ?: stepResult
    }

    /**
     * Loads the current persisted session for server-side revoke.
     *
     * Shared prelude for [signOut] and [invalidateSession]. Any [IOException] surfaced by
     * [SupabaseSessionStore.load] is captured into [outFailures] as a [BecalmResult.Failure]
     * so the caller can return it as the first failure if no later step also fails.
     *
     * @return the loaded session, or `null` when no session is persisted or load failed.
     */
    private suspend fun loadSessionForLogout(
        outFailures: MutableList<BecalmResult.Failure>,
    ): SupabaseSession? {
        return when (val result = runStepNamed("loadSession") { sessionStore.load() }) {
            is BecalmResult.Success -> result.value
            is BecalmResult.Failure -> {
                outFailures += result
                null
            }
        }
    }

    override suspend fun refreshSession(): BecalmResult<SupabaseSession> {
        val session = sessionStore.load()
        if (session == null || session.refreshToken.isBlank()) {
            return BecalmResult.Failure(BecalmError.Unauthorized)
        }
        return authClient.refresh(session.refreshToken)
            .onSuccess {
                // Keep the in-memory token cache in lockstep with the freshly-persisted
                // session. Invalidate first so [primeCache] actually re-reads the new
                // access token rather than returning the (now-stale) cached value
                // (Round 6A.4).
                tokenProvider.invalidate()
                tokenProvider.primeCache()
            }
    }

    override suspend fun currentSession(): SupabaseSession? = sessionStore.load()

    override fun observeAuthState(): Flow<AuthState> = sessionFlow
        .onStart {
            // Seed from the persisted store on first collection so cold starts immediately
            // see any previously saved session without waiting for a sign-in event.
            if (sessionFlow.value == null) {
                sessionStore.load()?.let { sessionFlow.value = it }
            }
        }
        .map { session ->
            if (session != null) AuthState.Authenticated(session) else AuthState.Unauthenticated
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * One named step in a multi-step logout sequence.
     *
     * Each step is identified by a human-readable [name] used in log messages so that
     * operators can correlate a failure to the exact wipe operation. [block] encapsulates
     * the actual side-effect; exceptions thrown from [block] are mapped to [BecalmResult.Failure]
     * by [runStepNamed].
     */
    private data class NamedStep(val name: String, val block: suspend () -> Any?)

    /**
     * Executes every step in [steps] in order, returning the first [BecalmResult.Failure]
     * encountered while still running the remaining steps (PIPA wipe invariant).
     *
     * If a step's block itself returns a [BecalmResult.Failure] (e.g.
     * [PersonEnrichmentRepository.deleteAll]), that failure is surfaced directly without
     * re-wrapping — avoiding a silent `Success(Failure(...))` outer/inner double-wrap bug.
     *
     * [flow] is prepended to each log tag so that mixed signOut / invalidateSession traces
     * remain distinguishable in aggregated logs.
     */
    private suspend fun runAllSteps(flow: String, steps: List<NamedStep>): BecalmResult<Unit> {
        var firstFailure: BecalmResult.Failure? = null
        for (step in steps) {
            val wrapped = runStepNamed("$flow/${step.name}") { step.block() }
            val stepResult: BecalmResult<*> = when (wrapped) {
                is BecalmResult.Failure -> wrapped
                is BecalmResult.Success -> {
                    // Unwrap one level when the step block itself returned a BecalmResult
                    // (e.g. deleteAll() returns BecalmResult<Int>), otherwise treat as success.
                    when (val inner = wrapped.value) {
                        is BecalmResult.Failure -> inner
                        else -> wrapped
                    }
                }
            }
            if (firstFailure == null && stepResult is BecalmResult.Failure) {
                firstFailure = stepResult
            }
        }
        return firstFailure ?: BecalmResult.Success(Unit)
    }

    /**
     * Executes [block] inside a try/catch, logging on success or failure.
     *
     * Maps [IOException] to [BecalmError.Io] and all other non-cancellation [Throwable]
     * to [BecalmError.Unknown]. [CancellationException]s are re-thrown via
     * [rethrowIfCancellation] so that structured concurrency cancellation propagates.
     */
    private suspend fun <T> runStepNamed(label: String, block: suspend () -> T): BecalmResult<T> = try {
        val value = block()
        logger.d(TAG, "$label completed")
        BecalmResult.Success(value)
    } catch (e: IOException) {
        logger.e(TAG, "$label IOException", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "IO error"))
    } catch (e: Throwable) {
        e.rethrowIfCancellation()
        logger.e(TAG, "$label unexpected error", e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }
}
