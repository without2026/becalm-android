package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.onSuccess
import com.becalm.android.core.analytics.NoopProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsAttributionStore
import com.becalm.android.core.analytics.ProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEventQueue
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.auth.ProcessRestarter
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.BeCalmDatabaseProvider
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.OAuthCredentialStore
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.supabase.SupabaseAuthClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.remote.supabase.SupabaseSessionStore
import com.becalm.android.worker.ContentObserverBootstrap
import com.becalm.android.worker.AuthenticatedRuntimeBootstrap
import com.becalm.android.worker.WorkScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
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
     * Creates an email/password account (AUTH-001A).
     *
     * On providers that return a session immediately, this follows the same local
     * post-auth state path as [signInWithEmail]. If email confirmation is required,
     * the remote client returns a validation failure and no local auth state is mutated.
     */
    public suspend fun signUpWithEmail(email: String, password: String): BecalmResult<SupabaseSession>

    /** Sends a Supabase SMS OTP to an E.164-normalized phone number. */
    public suspend fun requestPhoneOtp(phoneE164: String): BecalmResult<Unit>

    /** Verifies a Supabase SMS OTP and commits the returned session locally. */
    public suspend fun verifyPhoneOtp(phoneE164: String, token: String): BecalmResult<SupabaseSession>

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
    private val authClientProvider: Provider<SupabaseAuthClient>,
    private val sessionStore: SupabaseSessionStore,
    private val tokenProvider: AuthTokenProvider,
    private val deviceKeyStore: DeviceKeyStore,
    private val syncCursorStore: SyncCursorStore,
    private val userPrefsStore: UserPrefsStore,
    private val databaseProvider: BeCalmDatabaseProvider,
    private val workScheduler: WorkScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val imapCredentialStore: ImapCredentialStore,
    private val oauthCredentialStore: OAuthCredentialStore,
    private val processRestarter: ProcessRestarter,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val runtimeBootstrap: AuthenticatedRuntimeBootstrap,
    private val productAnalyticsEventQueue: ProductAnalyticsEventQueue,
    private val productAnalyticsAttributionStore: ProductAnalyticsAttributionStore,
    private val productAnalytics: ProductAnalyticsClient = NoopProductAnalyticsClient(),
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val logger: Logger,
) : AuthRepository {

    private val cleanupPlanner: AuthSessionCleanupPlanner = AuthSessionCleanupPlanner(
        authClientProvider = authClientProvider,
        sessionStore = sessionStore,
        tokenProvider = tokenProvider,
        deviceKeyStore = deviceKeyStore,
        syncCursorStore = syncCursorStore,
        userPrefsStore = userPrefsStore,
        databaseProvider = databaseProvider,
        workScheduler = workScheduler,
        contentObserverBootstrap = contentObserverBootstrap,
        personEnrichmentRepository = personEnrichmentRepository,
        sourceArtifactRepository = sourceArtifactRepository,
        imapCredentialStore = imapCredentialStore,
        oauthCredentialStore = oauthCredentialStore,
        sourceStatusRepository = sourceStatusRepository,
        processingStatusRepository = processingStatusRepository,
        runtimeBootstrap = runtimeBootstrap,
        productAnalyticsEventQueue = productAnalyticsEventQueue,
        productAnalyticsAttributionStore = productAnalyticsAttributionStore,
        ioDispatcher = ioDispatcher,
    )

    // Broadcasts the latest session across save/clear boundaries so that observers of
    // [observeAuthState] re-emit after sign-in and sign-out. `null` means no session is
    // currently persisted. Seeded lazily from [sessionStore] on first collection.
    private val sessionFlow = MutableStateFlow<SupabaseSession?>(null)

    private val authClient: SupabaseAuthClient
        get() = authClientProvider.get()

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): BecalmResult<SupabaseSession> =
        authClient.signInWithEmail(email, password).commitSignInState()

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): BecalmResult<SupabaseSession> =
        authClient.signUpWithEmail(email, password).commitSignInState()

    override suspend fun signInWithGoogle(idToken: String): BecalmResult<SupabaseSession> =
        authClient.signInWithGoogleIdToken(idToken).commitSignInState()

    override suspend fun requestPhoneOtp(phoneE164: String): BecalmResult<Unit> =
        authClient.requestPhoneOtp(phoneE164)

    override suspend fun verifyPhoneOtp(
        phoneE164: String,
        token: String,
    ): BecalmResult<SupabaseSession> =
        authClient.verifyPhoneOtp(phoneE164, token).commitSignInState()

    /**
     * Shared post-authentication state update for [signInWithEmail] / [signInWithGoogle].
     *
     * - Records the Supabase user id so [UserPrefsStore]'s user-scoped keys resolve
     *   to the right namespace (AUTH-008).
     * - Binds the per-user SQLite file (S6-A PIPA cross-account leak defence).
     * - Persists the encrypted Supabase session only after user-scoped local state
     *   has committed. This prevents a long-lived "session exists, but current user
     *   and Room scope are incomplete" split-brain state.
     * - Handles an in-process **account swap** by switching [BeCalmDatabaseProvider]
     *   to the new user-scoped file. DAO injections are lazy proxies over the provider,
     *   so singleton repositories resolve the current database on every DAO call instead
     *   of keeping a closed prior-user handle.
     * - Emits the session on [sessionFlow] and primes the auth token cache for the
     *   first hot-path request.
     *
     * The swap branch returns normally after [BeCalmDatabaseProvider] has opened
     * the new user scope; callers must not rely on a process restart to refresh DAOs.
     */
    private suspend fun BecalmResult<SupabaseSession>.commitSignInState(): BecalmResult<SupabaseSession> =
        when (this) {
            is BecalmResult.Failure -> this
            is BecalmResult.Success -> when (val localCommit = applySignInState(value)) {
                is BecalmResult.Success -> this
                is BecalmResult.Failure -> localCommit
            }
        }

    private suspend fun applySignInState(session: SupabaseSession): BecalmResult<Unit> {
        val priorSession = sessionFlow.value ?: sessionStore.load()
        val priorUserId = userPrefsStore.observeCurrentUserId().firstOrNull()
        return try {
            if (session.userId.isBlank()) {
                return BecalmResult.Failure(BecalmError.Validation(field = "user_id", message = "blank_user_id"))
            }
            val newHash = BeCalmDatabase.deriveUserIdHash(session.userId)
            val priorHash = databaseProvider.currentUserIdHash()
            userPrefsStore.setCurrentUserId(session.userId)
            databaseProvider.ensureOpenFor(newHash)
            sessionStore.save(session)
            sessionFlow.value = session
            tokenProvider.primeCache()
            productAnalytics.setUserScope(session.userId)
            if (priorHash != null && priorHash != newHash) {
                logger.i(TAG, "account swap completed in-process with user-scoped database swap")
            }
            BecalmResult.Success(Unit)
        } catch (e: IOException) {
            rollbackFailedSignIn(priorSession = priorSession, priorUserId = priorUserId)
            logger.e(TAG, "post-auth local commit IOException", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "IO error"))
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            rollbackFailedSignIn(priorSession = priorSession, priorUserId = priorUserId)
            logger.e(TAG, "post-auth local commit failed", e)
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    private suspend fun rollbackFailedSignIn(
        priorSession: SupabaseSession?,
        priorUserId: String?,
    ) {
        runCatching {
            if (priorSession != null) {
                sessionStore.save(priorSession)
                sessionFlow.value = priorSession
            } else {
                sessionStore.clear()
                sessionFlow.value = null
            }
        }.onFailure { logger.e(TAG, "post-auth rollback session restore failed", it) }
        runCatching { userPrefsStore.setCurrentUserId(priorUserId) }
            .onFailure { logger.e(TAG, "post-auth rollback currentUserId restore failed", it) }
        runCatching { tokenProvider.invalidate() }
            .onFailure { logger.e(TAG, "post-auth rollback token invalidation failed", it) }
        runCatching { productAnalytics.setUserScope(priorSession?.userId) }
            .onFailure { logger.e(TAG, "post-auth rollback analytics scope restore failed", it) }
    }

    override suspend fun signOut(): BecalmResult<Unit> {
        val preludeFailure = mutableListOf<BecalmResult.Failure>()
        val session = loadSessionForLogout(preludeFailure)
        val steps = cleanupPlanner.buildSignOutSteps(session)
        val stepResult = AuthRepositoryRunner.runAllSteps("signOut", steps, logger)

        // Broadcast the cleared state so observers transition to Unauthenticated.
        sessionFlow.value = null
        productAnalytics.resetUserScope()

        return preludeFailure.firstOrNull() ?: stepResult
    }

    override suspend fun invalidateSession(): BecalmResult<Unit> {
        val preludeFailure = mutableListOf<BecalmResult.Failure>()
        val session = loadSessionForLogout(preludeFailure)
        val steps = cleanupPlanner.buildInvalidateSessionSteps(session)

        // NOTE: Intentionally NOT calling databaseProvider.current().clearAllTables(),
        // personEnrichmentRepository.deleteAll(), or userPrefsStore.clearAll() — those
        // belong to the full PIPA wipe in [signOut]. Volatile cursors/status are cleared
        // at the auth boundary because they can otherwise suppress the next account's sync.
        // The per-user DB file is preserved on disk; sign-in as the same user re-opens it.
        //
        // Also intentionally NOT closing the [BeCalmDatabaseProvider]: the
        // spec AUTH-005/AUTH-008 pair requires "same account re-login restores local
        // data" to work without a process restart. Because `@Singleton` repositories
        // captured their DAO references at first injection, closing the underlying
        // [BeCalmDatabase] here would leave those references pointing at a dead file
        // handle — a correctness regression on the most common routine sign-out →
        // same-account sign-in path. Closing is reserved for the full-wipe [signOut]
        // flow (which runs `clearAllTables` first so the per-user DB file is empty
        // by the time the handle releases) plus the account-swap path where the
        // user will restart the app anyway (alpha contract; see
        // [BeCalmDatabaseProvider]).

        val stepResult = AuthRepositoryRunner.runAllSteps("invalidateSession", steps, logger)

        // Broadcast the cleared state so observers transition to Unauthenticated.
        sessionFlow.value = null
        productAnalytics.resetUserScope()

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
        return when (val result = AuthRepositoryRunner.runStepNamed("loadSession", logger) { sessionStore.load() }) {
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
        return authClient.refresh(session)
            .onSuccess {
                // Keep the in-memory token cache in lockstep with the freshly-persisted
                // session. Invalidate first so [primeCache] actually re-reads the new
                // access token rather than returning the (now-stale) cached value
                // (Round 6A.4).
                tokenProvider.invalidate()
                tokenProvider.primeCache()
            }
    }

    override suspend fun currentSession(): SupabaseSession? =
        sessionFlow.value ?: sessionStore.load()?.also { sessionFlow.value = it }

    override fun observeAuthState(): Flow<AuthState> = merge(
        sessionFlow,
        sessionStore.observe()
            .filter { session -> session == null }
            .map {
                sessionFlow.value = null
                null
            },
    )
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

}
