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
     * ## Refresh coalescing
     * Under a burst of parallel 401s the implementation is expected to serialize refresh
     * calls and short-circuit later callers when the in-memory cache has already advanced
     * past [previousAccessToken]. Supabase rotates refresh tokens on every refresh call, so
     * N parallel refresh attempts with the same refresh token would cause N-1 to fail at
     * the server; the implementation uses [previousAccessToken] to detect "the 401 I'm
     * recovering from is already stale" and return the newer cached token without hitting
     * the server.
     *
     * @param previousAccessToken The token value the caller attached to the request that
     *   returned 401. Used only for the coalescing double-check; never sent to Supabase.
     *   Pass an empty string when the caller had no token (first call with no session).
     */
    public suspend fun refresh(previousAccessToken: String): String?

    /**
     * Warms the in-memory access-token cache from persisted storage so the first
     * hot-path request avoids a disk read (Round 6A.4).
     *
     * Default implementation is a no-op because [DefaultAuthTokenProvider] already
     * observes [com.becalm.android.data.remote.supabase.SupabaseSessionStore.observe]
     * and auto-populates the cache on session save. Callers invoke this after sign-in
     * to make the warming intent explicit at the call site.
     */
    public suspend fun primeCache() {
        // Default: observer-based providers auto-prime on session save.
    }

    /**
     * Drops the in-memory access-token cache so the next hot-path request re-consults
     * storage (Round 6A.4).
     *
     * Default implementation is a no-op because [DefaultAuthTokenProvider] observes
     * [com.becalm.android.data.remote.supabase.SupabaseSessionStore.observe] and
     * auto-clears the cache on session clear. Callers invoke this after sign-out or
     * session invalidation to make the invalidation intent explicit.
     */
    public fun invalidate() {
        // Default: observer-based providers auto-invalidate on session clear.
    }
}
