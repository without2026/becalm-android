package com.becalm.android.data.remote.interceptor

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

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
 *    a. The 401 response body is buffered into memory via `body.bytes()` and the response
 *       is rebuilt with `toResponseBody(contentType)` so the caller can always read it
 *       (R1-01 fix: guarantees the returned Response is never consumed/closed).
 *    b. [AuthTokenProvider.refresh] is invoked via [runBlocking] on the current OkHttp
 *       dispatcher thread (refresh performs network I/O to Supabase Auth).
 *    c. If [refresh] returns `null` (refresh failed or no session), the buffered 401
 *       response is returned to the caller. No retry is attempted. Retrofit can surface
 *       the body to higher-layer error handlers.
 *    d. If [refresh] returns a non-null token, the buffered 401 response is closed to
 *       free resources, the original request is rebuilt with the new
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

        // Buffer the 401 body into memory and rebuild the response so the caller can always
        // read it, even if we consumed bytes during refresh handling (R1-01 fix).
        val bodyContentType = response.body?.contentType()
        val bodyBytes = response.body?.bytes() ?: ByteArray(0)
        val bufferedResponse = response.newBuilder()
            .body(bodyBytes.toResponseBody(bodyContentType))
            .build()

        val newToken = runBlocking { authTokenProvider.refresh() }
        if (newToken == null) {
            return bufferedResponse
        }

        bufferedResponse.close()
        val retryRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
        return chain.proceed(retryRequest)
    }
}
