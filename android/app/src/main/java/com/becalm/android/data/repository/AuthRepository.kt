package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.map
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.secure.DeviceKeyStore
import com.becalm.android.data.local.secure.ImapCredentialStore
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
    private val deviceKeyStore: DeviceKeyStore,
    private val syncCursorStore: SyncCursorStore,
    private val userPrefsStore: UserPrefsStore,
    private val database: BeCalmDatabase,
    private val workScheduler: WorkScheduler,
    private val contentObserverBootstrap: ContentObserverBootstrap,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val imapCredentialStore: ImapCredentialStore,
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
            .map { it.session }
            .also { result ->
                if (result is BecalmResult.Success) {
                    userPrefsStore.setCurrentUserId(result.value.userId)
                    sessionFlow.value = result.value
                }
            }

    override suspend fun signInWithGoogle(idToken: String): BecalmResult<SupabaseSession> =
        authClient.signInWithGoogleIdToken(idToken)
            .map { it.session }
            .also { result ->
                if (result is BecalmResult.Success) {
                    userPrefsStore.setCurrentUserId(result.value.userId)
                    sessionFlow.value = result.value
                }
            }

    override suspend fun signOut(): BecalmResult<Unit> {
        // PIPA wipe — every step runs regardless of prior step failures.
        // The first failure is captured and returned after all steps complete.
        // Order: cancel workers first, then clear enrichment + IMAP creds, then clear tokens.
        var firstFailure: BecalmResult.Failure? = null

        fun recordIfFirst(result: BecalmResult<*>) {
            if (firstFailure == null && result is BecalmResult.Failure) {
                firstFailure = result
            }
        }

        // Step 1: load session to obtain access token for server-side revoke.
        val session = runStep(1) { sessionStore.load() }
            .let { result ->
                when (result) {
                    is BecalmResult.Success -> result.value
                    is BecalmResult.Failure -> {
                        recordIfFirst(result)
                        null
                    }
                }
            }

        // Step 2: cancel all WorkManager jobs so no worker runs against the stale session.
        recordIfFirst(runStep(2) { workScheduler.cancelAll() })

        // Step 3: stop content observers so no ghost callbacks fire after sign-out.
        recordIfFirst(runStep(3) { contentObserverBootstrap.stop() })

        // Step 4: best-effort server-side revoke; always returns Success per authClient contract.
        if (session != null) {
            val result = runStep(4) { authClient.signOut(session.accessToken) }
            recordIfFirst(result)
        }

        // Steps 5–11: local wipe — must all run even after an earlier failure.
        // personEnrichmentRepository.deleteAll() already returns BecalmResult<Int>;
        // call it directly rather than wrapping it in runStep to avoid double-wrapping.
        recordIfFirst(
            runCatching { personEnrichmentRepository.deleteAll() }
                .getOrElse { e ->
                    logger.e(TAG, "signOut step 5 unexpected error", e)
                    BecalmResult.Failure(BecalmError.Unknown(e))
                },
        )
        recordIfFirst(runStep(6) { imapCredentialStore.clear() })
        recordIfFirst(runStep(7) { sessionStore.clear() })
        recordIfFirst(runStep(8) { deviceKeyStore.clear() })
        recordIfFirst(runStep(9) { syncCursorStore.clearAll() })
        recordIfFirst(runStep(10) { userPrefsStore.clearAll() })
        recordIfFirst(runStep(11) { database.clearAllTables() })

        // Broadcast the cleared state so observers transition to Unauthenticated.
        sessionFlow.value = null

        return firstFailure ?: BecalmResult.Success(Unit)
    }

    override suspend fun invalidateSession(): BecalmResult<Unit> {
        // Session-only cleanup — preserves Room data per the local-first spec invariant
        // "로그아웃 시 Room DB 데이터는 삭제하지 않는다". Every step runs regardless of prior
        // step failures; the first failure is captured and returned after all steps complete.
        var firstFailure: BecalmResult.Failure? = null

        fun recordIfFirst(result: BecalmResult<*>) {
            if (firstFailure == null && result is BecalmResult.Failure) {
                firstFailure = result
            }
        }

        // Step 1: load session to obtain access token for server-side revoke.
        val session = runStep(1) { sessionStore.load() }
            .let { result ->
                when (result) {
                    is BecalmResult.Success -> result.value
                    is BecalmResult.Failure -> {
                        recordIfFirst(result)
                        null
                    }
                }
            }

        // Step 2: cancel all WorkManager jobs so no worker runs against the stale session.
        recordIfFirst(runStep(2) { workScheduler.cancelAll() })

        // Step 3: stop content observers so no ghost callbacks fire after sign-out.
        recordIfFirst(runStep(3) { contentObserverBootstrap.stop() })

        // Step 4: best-effort server-side revoke.
        if (session != null) {
            recordIfFirst(runStep(4) { authClient.signOut(session.accessToken) })
        }

        // Steps 5–7: clear auth-related local stores only.
        // IMAP credentials are tied to the current account (not the raw Room event data),
        // so they are considered part of "session" and are cleared here.
        recordIfFirst(runStep(5) { imapCredentialStore.clear() })
        recordIfFirst(runStep(6) { sessionStore.clear() })
        recordIfFirst(runStep(7) { deviceKeyStore.clear() })

        // Step 8: clear the non-secret current-user-id mirror. Other UI preferences
        // (locale, notifications flag, onboarding completion) are intentionally preserved.
        recordIfFirst(runStep(8) { userPrefsStore.setCurrentUserId(null) })

        // NOTE: Intentionally NOT calling database.clearAllTables(),
        // personEnrichmentRepository.deleteAll(), syncCursorStore.clearAll(), or
        // userPrefsStore.clearAll() — those belong to the full PIPA wipe in [signOut].

        // Broadcast the cleared state so observers transition to Unauthenticated.
        sessionFlow.value = null

        return firstFailure ?: BecalmResult.Success(Unit)
    }

    override suspend fun refreshSession(): BecalmResult<SupabaseSession> {
        val session = sessionStore.load()
        if (session == null || session.refreshToken.isBlank()) {
            return BecalmResult.Failure(BecalmError.Unauthorized)
        }
        return authClient.refresh(session.refreshToken).map { it.session }
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
     * Executes [block] inside a try/catch, logging the step number on completion or failure.
     * Maps [IOException] to [BecalmError.Io] and all other [Throwable] to [BecalmError.Unknown].
     */
    private suspend fun <T> runStep(step: Int, block: suspend () -> T): BecalmResult<T> = try {
        val value = block()
        logger.d(TAG, "signOut step $step completed")
        BecalmResult.Success(value)
    } catch (e: IOException) {
        logger.e(TAG, "signOut step $step IOException", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "IO error"))
    } catch (e: Throwable) {
        logger.e(TAG, "signOut step $step unexpected error", e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }
}
