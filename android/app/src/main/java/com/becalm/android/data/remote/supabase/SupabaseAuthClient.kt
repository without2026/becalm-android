package com.becalm.android.data.remote.supabase

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.exceptions.RestException
import kotlinx.datetime.Instant
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Contract for all Supabase Auth operations performed by BeCalm Android.
 *
 * Implementations are responsible for:
 * 1. Delegating to the Supabase SDK.
 * 2. Converting [UserSession] → [SupabaseSession].
 * 3. Persisting the session via [SupabaseSessionStore] after every successful sign-in or refresh.
 * 4. Mapping SDK exceptions to typed [BecalmError] variants.
 *
 * Hilt binding: SP-06 registers `@Binds SupabaseAuthClientImpl → SupabaseAuthClient`.
 */
public interface SupabaseAuthClient {

    /**
     * Authenticates using email and password (AUTH-001).
     *
     * On success the session is persisted to [SupabaseSessionStore] before returning.
     *
     * @param email The user's registered email address.
     * @param password The user's plaintext password (transmitted over TLS; never stored).
     * @return [BecalmResult.Success] with the new [SupabaseSession], or [BecalmResult.Failure]
     *   with [BecalmError.Unauthorized] on bad credentials or [BecalmError.Network] on transport error.
     */
    public suspend fun signInWithEmail(email: String, password: String): BecalmResult<SupabaseSession>

    /**
     * Authenticates using a Google ID token obtained from the Google Sign-In SDK (AUTH-002 / AUTH-003).
     *
     * On success the session is persisted to [SupabaseSessionStore] before returning.
     *
     * @param idToken The raw JWT ID token returned by Google Sign-In.
     * @return [BecalmResult.Success] with the new [SupabaseSession], or [BecalmResult.Failure]
     *   with an appropriate [BecalmError] on failure.
     */
    public suspend fun signInWithGoogleIdToken(idToken: String): BecalmResult<SupabaseSession>

    /**
     * Exchanges a valid [refreshToken] for a new access/refresh token pair (AUTH-004 / AUTH-007).
     *
     * Called by the `AuthInterceptor` (SP-05) through `AuthTokenProvider.refresh()` when it
     * receives an HTTP 401 from the Railway backend. On success the new session is persisted.
     *
     * Because [SupabaseClientFactory] sets `autoLoadFromStorage = false` and
     * `alwaysAutoRefresh = false`, the client has no in-memory session at the point of a 401.
     * This method imports the refresh token directly into the SDK before calling the refresh
     * endpoint, ensuring the underlying Ktor request carries the correct grant.
     *
     * @param refreshToken The refresh token loaded from [SupabaseSessionStore] by the caller.
     * @return [BecalmResult.Success] with the refreshed [SupabaseSession], or [BecalmResult.Failure]
     *   with [BecalmError.Unauthorized] if the refresh token is expired/revoked.
     */
    public suspend fun refresh(refreshToken: String): BecalmResult<SupabaseSession>

    /**
     * Revokes the Supabase-side session for the given [accessToken] (AUTH-005).
     *
     * Uses `SignOutScope.LOCAL` so only the current device's session is invalidated,
     * leaving other active sessions (web, other devices) untouched.
     *
     * **Important:** This method does NOT call [SupabaseSessionStore.clear]. The broader
     * sign-out wipe (store + Room + DataStore) is orchestrated by `AuthRepository` (SP-16).
     * This is a best-effort server-side revoke; a network failure is mapped to
     * [BecalmResult.Success] so the caller can always proceed with the local wipe.
     *
     * @param accessToken The current access token to revoke. Unused by the SDK call itself
     *   (the client session is already loaded) but retained in the signature so SP-16 can
     *   pass the token it loaded from the store without a separate SDK round-trip.
     * @return Always [BecalmResult.Success] — the revoke is best-effort.
     */
    public suspend fun signOut(accessToken: String): BecalmResult<Unit>
}

// ─── Implementation ──────────────────────────────────────────────────────────

private const val TAG = "SupabaseAuthClient"

/**
 * Production implementation of [SupabaseAuthClient] backed by the Supabase Kotlin SDK 2.6.0.
 *
 * **`autoLoadFromStorage = false` rationale**: the SDK's built-in storage layer writes tokens
 * to unencrypted SharedPreferences. By disabling it and routing all persistence through
 * [sessionStore] (SP-15 `EncryptedTokenStore`), we satisfy the AUTH-004 invariant that tokens
 * are always protected by the Android Keystore.
 *
 * **`alwaysAutoRefresh = false` rationale**: proactive refresh is triggered by the
 * `AuthInterceptor` (SP-05) on HTTP 401, keeping refresh logic in one place and making it
 * testable without real clock manipulation.
 *
 * Exception mapping policy:
 * - [RestException] with status 401 → [BecalmError.Unauthorized]
 * - [RestException] with status 429 → [BecalmError.RateLimited]
 * - [RestException] with status 5xx → [BecalmError.ServerError]
 * - [RestException] other → [BecalmError.Network]
 * - [IOException] (network timeout, no connectivity) → [BecalmError.Network]
 * - Any other [Throwable] → [BecalmError.Unknown]
 */
@Singleton
public class SupabaseAuthClientImpl @Inject constructor(
    private val client: SupabaseClient,
    private val sessionStore: SupabaseSessionStore,
    private val logger: Logger,
) : SupabaseAuthClient {

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ): BecalmResult<SupabaseSession> = runCatchingAuth("signInWithEmail") {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        val session = requireCurrentSession()
        sessionStore.save(session)
        session
    }

    override suspend fun signInWithGoogleIdToken(
        idToken: String,
    ): BecalmResult<SupabaseSession> = runCatchingAuth("signInWithGoogleIdToken") {
        client.auth.signInWith(IDToken) {
            provider = Google
            this.idToken = idToken
        }
        val session = requireCurrentSession()
        sessionStore.save(session)
        session
    }

    override suspend fun refresh(
        refreshToken: String,
    ): BecalmResult<SupabaseSession> = runCatchingAuth("refresh") {
        // supabase-kt 2.6.0: with autoLoadFromStorage=false the client holds no in-memory
        // session after process restart or a cold 401. We must import a minimal UserSession
        // so that refreshCurrentSession() knows which refresh token to exchange.
        //
        // UserSession requires at least a non-null refreshToken; the other fields are
        // placeholders that are immediately replaced by the refreshed values.
        val placeholder = UserSession(
            accessToken = "",
            refreshToken = refreshToken,
            expiresIn = 0L,
            tokenType = "bearer",
            user = null,
        )
        client.auth.importSession(placeholder)
        client.auth.refreshCurrentSession()

        val session = requireCurrentSession()
        sessionStore.save(session)
        session
    }

    override suspend fun signOut(accessToken: String): BecalmResult<Unit> {
        // Best-effort: a network failure during sign-out must not block the local wipe
        // orchestrated by AuthRepository (SP-16).
        return try {
            client.auth.signOut(scope = SignOutScope.LOCAL)
            logger.d(TAG, "server sign-out succeeded")
            BecalmResult.Success(Unit)
        } catch (e: RestException) {
            logger.w(TAG, "server sign-out failed (${e.statusCode}) — continuing local wipe")
            BecalmResult.Success(Unit)
        } catch (e: IOException) {
            logger.w(TAG, "sign-out network error — continuing local wipe: ${e.message}")
            BecalmResult.Success(Unit)
        } catch (e: Exception) {
            logger.w(TAG, "sign-out unexpected error — continuing local wipe: ${e.message}")
            BecalmResult.Success(Unit)
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Reads the current in-memory session from the Supabase client and converts it to a
     * [SupabaseSession], throwing [IllegalStateException] if the SDK holds no session after
     * a successful sign-in (which would be a SDK contract violation).
     */
    private fun requireCurrentSession(): SupabaseSession {
        val raw = checkNotNull(client.auth.currentSessionOrNull()) {
            "Supabase SDK returned no session after a successful auth operation — " +
                "this is a SDK contract violation."
        }
        return raw.toSupabaseSession()
    }

    /**
     * Wraps a suspending [block] in a structured try/catch that maps every known exception
     * type to a typed [BecalmError] and logs the failure via [logger].
     *
     * @param tag Short label used in the log message (e.g. `"signInWithEmail"`).
     */
    private suspend fun <T> runCatchingAuth(
        tag: String,
        block: suspend () -> T,
    ): BecalmResult<T> = try {
        BecalmResult.Success(block())
    } catch (e: RestException) {
        val error = mapRestException(e)
        logger.e(TAG, "[$tag] RestException ${e.statusCode}: ${e.message}")
        BecalmResult.Failure(error)
    } catch (e: IOException) {
        logger.e(TAG, "[$tag] IOException: ${e.message}")
        BecalmResult.Failure(BecalmError.Network(code = -1, message = e.message ?: "Network error"))
    } catch (e: Exception) {
        logger.e(TAG, "[$tag] Unexpected: ${e.message}")
        BecalmResult.Failure(BecalmError.Unknown(e))
    }

    private fun mapRestException(e: RestException): BecalmError = when (e.statusCode) {
        401 -> BecalmError.Unauthorized
        429 -> BecalmError.RateLimited(retryAfterSeconds = null)
        in 500..599 -> BecalmError.ServerError(code = e.statusCode, body = e.message)
        else -> BecalmError.Network(code = e.statusCode, message = e.message ?: "Auth error")
    }
}

// ─── Extension ───────────────────────────────────────────────────────────────

/**
 * Converts a supabase-kt [UserSession] to the BeCalm domain [SupabaseSession].
 *
 * `expiresAt` is derived from `expiresIn` (seconds from now) rather than an absolute
 * timestamp field because supabase-kt 2.6.0 exposes only the relative TTL.
 * The `AuthInterceptor` (SP-05) should treat this as a soft expiry hint and
 * always attempt refresh on 401 regardless of the cached value.
 */
private fun UserSession.toSupabaseSession(): SupabaseSession {
    val expiresAt = Instant.fromEpochMilliseconds(
        System.currentTimeMillis() + (expiresIn * 1_000L)
    )
    return SupabaseSession(
        accessToken = accessToken,
        refreshToken = refreshToken ?: "",
        userId = user?.id ?: "",
        email = user?.email ?: "",
        expiresAt = expiresAt,
    )
}
