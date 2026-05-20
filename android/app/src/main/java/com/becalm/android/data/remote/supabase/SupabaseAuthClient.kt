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
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Contract for all Supabase Auth operations performed by BeCalm Android.
 *
 * Implementations are responsible for:
 * 1. Delegating to the Supabase SDK.
 * 2. Converting [UserSession] → [SupabaseSession].
 * 3. Persisting refreshed sessions via [SupabaseSessionStore] after a successful refresh.
 * 4. Mapping SDK exceptions to typed [BecalmError] variants.
 *
 * Sign-in and sign-up methods return the remote session without persisting it. The repository
 * commits that session only after user-scoped local state is ready, preventing partially
 * authenticated local state.
 *
 * Hilt binding: SP-06 registers `@Binds SupabaseAuthClientImpl → SupabaseAuthClient`.
 */
public interface SupabaseAuthClient {

    /**
     * Authenticates using email and password (AUTH-001).
     *
     * On success the remote session is returned without local persistence. The repository
     * owns the transactional local commit.
     *
     * @param email The user's registered email address.
     * @param password The user's plaintext password (transmitted over TLS; never stored).
     * @return [BecalmResult.Success] with the new [SupabaseSession], or [BecalmResult.Failure]
     *   with [BecalmError.Unauthorized] on bad credentials or [BecalmError.Network] on transport error.
     */
    public suspend fun signInWithEmail(email: String, password: String): BecalmResult<SupabaseSession>

    /**
     * Creates an email/password account (AUTH-001A).
     *
     * Supabase may return a session immediately or require email confirmation first.
     * This client returns [BecalmError.Validation] with `message=email_confirmation_required`
     * for the confirmation-required branch so the UI can show a stable product string.
     */
    public suspend fun signUpWithEmail(email: String, password: String): BecalmResult<SupabaseSession>

    /**
     * Authenticates using a Google ID token obtained from the Google Sign-In SDK (AUTH-002 / AUTH-003).
     *
     * On success the remote session is returned without local persistence. The repository
     * owns the transactional local commit.
     *
     * @param idToken The raw JWT ID token returned by Google Sign-In.
     * @return [BecalmResult.Success] with the new [SupabaseSession], or [BecalmResult.Failure]
     *   with an appropriate [BecalmError] on failure.
     */
    public suspend fun signInWithGoogleIdToken(idToken: String): BecalmResult<SupabaseSession>

    /**
     * Exchanges [currentSession]'s refresh token for a new access/refresh token pair
     * (AUTH-004 / AUTH-007).
     *
     * Called by the `AuthInterceptor` (SP-05) through `AuthTokenProvider.refresh(previousAccessToken)`
     * when it receives an HTTP 401 from the Railway backend. On success the new session is persisted.
     *
     * Because [SupabaseClientFactory] sets `autoLoadFromStorage = false` and
     * `alwaysAutoRefresh = false`, the client has no in-memory session at the point of a 401.
     * This method imports the refresh token directly into the SDK before calling the refresh
     * endpoint, ensuring the underlying Ktor request carries the correct grant.
     *
     * @param currentSession The session loaded from [SupabaseSessionStore] by the caller.
     *   Its user id and email are used as an integrity fallback if the SDK refresh response
     *   omits user metadata.
     * @return [BecalmResult.Success] with the refreshed [SupabaseSession], or [BecalmResult.Failure]
     *   with [BecalmError.Unauthorized] if the refresh token is expired/revoked.
     */
    public suspend fun refresh(currentSession: SupabaseSession): BecalmResult<SupabaseSession>

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
 * - Google ID-token auth provider disabled → [BecalmError.Validation] with
 *   `message=google_provider_disabled` so the UI does not mislabel setup errors as
 *   user network failures.
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
    ): BecalmResult<SupabaseSession> = runCatchingAuth(
        tag = "signInWithEmail",
        restExceptionMapper = ::mapEmailSignInRestException,
    ) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        requireCurrentSession()
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ): BecalmResult<SupabaseSession> = runCatchingAuth(
        tag = "signUpWithEmail",
        restExceptionMapper = ::mapEmailSignUpRestException,
    ) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        val rawSession = client.auth.currentSessionOrNull()
            ?: throw EmailConfirmationRequiredException()
        rawSession.toSupabaseSession()
    }

    override suspend fun signInWithGoogleIdToken(
        idToken: String,
    ): BecalmResult<SupabaseSession> = runCatchingAuth(
        tag = "signInWithGoogleIdToken",
        restExceptionMapper = ::mapGoogleSignInRestException,
    ) {
        client.auth.signInWith(IDToken) {
            provider = Google
            this.idToken = idToken
        }
        requireCurrentSession()
    }

    override suspend fun refresh(
        currentSession: SupabaseSession,
    ): BecalmResult<SupabaseSession> = runCatchingAuth(
        tag = "refresh",
        restExceptionMapper = ::mapDefaultRestException,
    ) {
        // supabase-kt 2.6.0: with autoLoadFromStorage=false the client holds no in-memory
        // session after process restart or a cold 401. We must import a minimal UserSession
        // so that refreshCurrentSession() knows which refresh token to exchange.
        //
        // UserSession requires at least a non-null refreshToken; the other fields are
        // placeholders that are immediately replaced by the refreshed values.
        val placeholder = UserSession(
            accessToken = "",
            refreshToken = currentSession.refreshToken,
            expiresIn = 0L,
            tokenType = "bearer",
            user = null,
        )
        client.auth.importSession(placeholder)
        client.auth.refreshCurrentSession()

        val session = requireCurrentSession(
            fallbackUserId = currentSession.userId,
            fallbackEmail = currentSession.email,
        )
        if (session.userId != currentSession.userId) {
            error("Supabase refresh returned a different user id")
        }
        sessionStore.save(session)
        session
    }

    override suspend fun signOut(accessToken: String): BecalmResult<Unit> {
        // Best-effort: a network failure during sign-out must not block the local wipe
        // orchestrated by AuthRepository (SP-16).
        return try {
            client.auth.importSession(
                UserSession(
                    accessToken = accessToken,
                    refreshToken = "",
                    expiresIn = 0L,
                    tokenType = "bearer",
                    user = null,
                )
            )
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
    private fun requireCurrentSession(
        fallbackUserId: String? = null,
        fallbackEmail: String? = null,
    ): SupabaseSession {
        val raw = checkNotNull(client.auth.currentSessionOrNull()) {
            "Supabase SDK returned no session after a successful auth operation — " +
                "this is a SDK contract violation."
        }
        return raw.toSupabaseSession(
            fallbackUserId = fallbackUserId,
            fallbackEmail = fallbackEmail,
        )
    }

    /**
     * Wraps a suspending [block] in a structured try/catch that maps every known exception
     * type to a typed [BecalmError] and logs the failure via [logger].
     *
     * @param tag Short label used in the log message (e.g. `"signInWithEmail"`).
     */
    private suspend fun <T> runCatchingAuth(
        tag: String,
        restExceptionMapper: (RestException) -> BecalmError,
        block: suspend () -> T,
    ): BecalmResult<T> = try {
        BecalmResult.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: RestException) {
        val error = restExceptionMapper(e)
        logger.e(TAG, "[$tag] RestException ${e.statusCode}: ${e.message}")
        BecalmResult.Failure(error)
    } catch (e: IOException) {
        logger.e(TAG, "[$tag] IOException: ${e.message}")
        BecalmResult.Failure(BecalmError.Network(code = -1, message = e.message ?: "Network error"))
    } catch (e: EmailConfirmationRequiredException) {
        logger.d(TAG, "[$tag] email confirmation required")
        BecalmResult.Failure(BecalmError.Validation(field = "email", message = e.message ?: "email_confirmation_required"))
    } catch (e: Exception) {
        logger.e(TAG, "[$tag] Unexpected: ${e.message}")
        BecalmResult.Failure(BecalmError.Unknown(e))
    }

    private fun mapEmailSignInRestException(e: RestException): BecalmError = when (e.statusCode) {
        400, 401 -> if (isEmailNotConfirmed(e.authDiagnosticText())) {
            BecalmError.Validation(field = "email", message = "email_not_confirmed")
        } else {
            BecalmError.Unauthorized
        }
        else -> mapDefaultRestException(e)
    }

    private fun mapEmailSignUpRestException(e: RestException): BecalmError = when (e.statusCode) {
        400, 401, 422 -> mapEmailSignUpValidationError(e.statusCode, e.authDiagnosticText())
        else -> mapDefaultRestException(e)
    }

    private fun mapGoogleSignInRestException(e: RestException): BecalmError {
        val diagnostic = e.authDiagnosticText()
        return if (e.statusCode == 400 && diagnostic.contains("Provider", ignoreCase = true) &&
            diagnostic.contains("not enabled", ignoreCase = true)
        ) {
            BecalmError.Validation(field = "auth_provider", message = "google_provider_disabled")
        } else {
            mapDefaultRestException(e)
        }
    }

    private fun mapDefaultRestException(e: RestException): BecalmError = when (e.statusCode) {
        401 -> BecalmError.Unauthorized
        429 -> BecalmError.RateLimited(retryAfterSeconds = null)
        in 500..599 -> BecalmError.ServerError(code = e.statusCode, body = e.message)
        else -> BecalmError.Network(code = e.statusCode, message = e.message ?: "Auth error")
    }
}

private class EmailConfirmationRequiredException : Exception("email_confirmation_required")

private fun RestException.authDiagnosticText(): String =
    listOfNotNull(error, description, message)
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n")

internal fun mapEmailSignUpValidationError(statusCode: Int, message: String?): BecalmError.Validation {
    val normalized = message.orEmpty().lowercase()
    return when {
        normalized.contains("already registered") ||
            normalized.contains("already been registered") ||
            normalized.contains("user already exists") ||
            normalized.contains("user_already_exists") ||
            normalized.contains("email_exists") ||
            normalized.contains("email already exists") ||
            normalized.contains("email address has already") ||
            normalized.contains("identity already exists") ||
            normalized.contains("duplicate key") ->
            BecalmError.Validation(field = "email", message = "email_already_registered")

        normalized.contains("weak password") ||
            normalized.contains("password should") ||
            normalized.contains("password must") ->
            BecalmError.Validation(field = "password", message = "weak_password")

        normalized.contains("signup disabled") ||
            normalized.contains("signups disabled") ||
            normalized.contains("signups not allowed") ->
            BecalmError.Validation(field = "auth", message = "signup_disabled")

        statusCode == 401 ->
            BecalmError.Validation(field = "auth", message = "signup_disabled")

        else ->
            BecalmError.Validation(field = "email", message = "signup_failed")
    }
}

private fun isEmailNotConfirmed(message: String): Boolean {
    val normalized = message.lowercase()
    return normalized.contains("email not confirmed") ||
        normalized.contains("email_not_confirmed") ||
        normalized.contains("email confirmation") && normalized.contains("required")
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
private fun UserSession.toSupabaseSession(
    fallbackUserId: String? = null,
    fallbackEmail: String? = null,
): SupabaseSession {
    val expiresAt = Instant.fromEpochMilliseconds(
        System.currentTimeMillis() + (expiresIn * 1_000L)
    )
    val resolvedUserId = user?.id?.takeIf { it.isNotBlank() } ?: fallbackUserId.orEmpty()
    check(resolvedUserId.isNotBlank()) {
        "Supabase session is missing user id"
    }
    return SupabaseSession(
        accessToken = accessToken,
        refreshToken = refreshToken ?: "",
        userId = resolvedUserId,
        email = user?.email?.takeIf { it.isNotBlank() } ?: fallbackEmail.orEmpty(),
        expiresAt = expiresAt,
    )
}
