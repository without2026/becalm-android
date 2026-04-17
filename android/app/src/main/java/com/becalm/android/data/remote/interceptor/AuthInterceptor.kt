package com.becalm.android.data.remote.interceptor

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp [Interceptor] that enforces the AUTH-007 token lifecycle on all Railway API calls.
 *
 * Behaviour (per spec AUTH-007):
 *
 * 1. **Host guard** — if `request.url.host` does not equal [railwayHost] the request is
 *    forwarded unchanged. This ensures Supabase Auth calls (`/auth/v1/token`,
 *    `/auth/v1/logout`) bypass this interceptor entirely.
 *
 * 2. **Attach bearer token** — for Railway requests, the current access token is read via
 *    [AuthTokenProvider.currentAccessToken] (synchronous, no suspension) and attached as
 *    `Authorization: Bearer <token>`. If no token is available an empty string is used so
 *    the header is always present; the server returns 401 which triggers step 3.
 *
 * 3. **401 refresh-and-retry** — on receiving an HTTP 401 response:
 *    a. [AuthTokenProvider.refresh] is invoked via [runBlocking] on the current OkHttp
 *       dispatcher thread (refresh performs network I/O to Supabase Auth).
 *    b. If [refresh] returns `null` (refresh failed or no session), the original 401
 *       response is returned **intact** to the caller (body is NOT closed). No retry is
 *       attempted. The caller receives a fully-readable 401 response so that Retrofit can
 *       surface it to higher-layer error handlers (R1-01 fix).
 *    c. If [refresh] returns a non-null token, the original 401 response is closed to
 *       free the connection, the original request is rebuilt with the new
 *       `Authorization: Bearer <newToken>` header, and executed exactly **once**.
 *       The second response is returned unchanged — no further 401 retry occurs.
 *
 * 4. **IOException propagation** — IOExceptions from the network are never swallowed;
 *    they propagate to the caller for handling by [RetryInterceptor].
 *
 * No Hilt annotations are present; SP-06 (`NetworkModule`) constructs and wires this class.
 *
 * @param authTokenProvider Token source and refresh delegate.
 * @param railwayHost Hostname of the Railway backend (e.g. `"becalm-api.railway.app"`).
 *   Requests to any other host are forwarded unchanged.
 */
public class AuthInterceptor(
    private val authTokenProvider: AuthTokenProvider,
    private val railwayHost: String,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Step 1: bypass for non-Railway hosts (Supabase Auth, etc.)
        if (originalRequest.url.host != railwayHost) {
            return chain.proceed(originalRequest)
        }

        // Step 2: attach current access token
        val token = authTokenProvider.currentAccessToken().orEmpty()
        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()

        val response = chain.proceed(authenticatedRequest)

        // Step 3: handle 401 with single refresh-and-retry
        if (response.code != 401) {
            return response
        }

        // Attempt token refresh. Do NOT close the response before the refresh call —
        // if refresh fails (returns null) we must return the original 401 intact so the
        // caller can read its body (R1-01 fix: avoids returning a closed Response).
        val newToken = runBlocking { authTokenProvider.refresh() }
        if (newToken == null) {
            // Refresh failed — return the original 401 response with body still open.
            return response
        }

        // Refresh succeeded: close the 401 response to release the connection, then retry once.
        response.close()
        val retryRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
        return chain.proceed(retryRequest)
    }
}
