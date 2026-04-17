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
     */
    public suspend fun refresh(): String?
}
