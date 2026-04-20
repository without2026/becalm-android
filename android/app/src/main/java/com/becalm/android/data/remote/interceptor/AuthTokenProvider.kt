package com.becalm.android.data.remote.interceptor

/**
 * Source of the Supabase JWT used to authenticate Railway API calls.
 *
 * This interface decouples the OkHttp interceptor layer from the concrete token
 * storage implementation (EncryptedSharedPreferences) owned by SP-06 (`NetworkModule`).
 *
 * Two access patterns are required:
 * - [currentAccessToken]: a synchronous, non-suspend accessor invoked on the OkHttp
 *   dispatcher thread to attach the `Authorization: Bearer` header.
 * - [refresh]: a suspending function invoked by [com.becalm.android.data.remote.interceptor.AuthInterceptor]
 *   via `runBlocking` on receipt of an HTTP 401 response (AUTH-007). The concrete
 *   implementation calls Supabase Auth `POST /auth/v1/token?grant_type=refresh_token`
 *   and persists the new tokens to EncryptedSharedPreferences.
 *
 * The concrete implementation `DefaultAuthTokenProvider` lives in SP-06 `NetworkModule.kt`
 * as Hilt DI glue. Do not place an implementation in this file.
 */
public interface AuthTokenProvider {

    /**
     * Returns the current access token without suspending.
     *
     * Called on the OkHttp dispatcher thread; must not block for network I/O.
     * Returns `null` when no session exists (user not signed in).
     */
    public fun currentAccessToken(): String?

    /**
     * Attempts to refresh the session and returns the new access token.
     *
     * Called via `runBlocking` inside [com.becalm.android.data.remote.interceptor.AuthInterceptor]
     * on an HTTP 401 response (AUTH-007). Returns the new access token on success,
     * or `null` if the refresh itself fails (expired refresh token, network error, etc.).
     * When `null` is returned the interceptor propagates the original 401 to the caller.
     *
     * Implementations must deduplicate concurrent calls: N simultaneous invocations during
     * a thundering-herd of 401s must trigger exactly one upstream refresh request. All
     * waiters observe the result of that single refresh.
     */
    public suspend fun refresh(): String?

    /**
     * Loads the access token from persistent storage into the in-memory cache, returning
     * the warmed value (or `null` if no session is persisted).
     *
     * Intended to be called after a fresh sign-in or a successful refresh so the next
     * [currentAccessToken] call does not hit disk. Safe to call repeatedly; concurrent
     * calls coalesce to one disk read.
     */
    public suspend fun primeCache(): String?

    /**
     * Clears the in-memory token cache. Must be invoked by the authentication wipe
     * sequence (sign-out / invalidate-session) so the next [currentAccessToken] read
     * re-consults storage rather than returning a stale token.
     *
     * Does not touch persistent storage — the caller is responsible for clearing
     * [com.becalm.android.data.remote.supabase.SupabaseSessionStore].
     */
    public fun invalidate()
}
