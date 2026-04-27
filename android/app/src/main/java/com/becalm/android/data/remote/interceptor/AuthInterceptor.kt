package com.becalm.android.data.remote.interceptor

import com.becalm.android.data.auth.AuthFailureSessionInvalidator
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
 *    c. If refresh reports [AuthTokenProvider.RefreshResult.Failed], the buffered 401 is
 *       returned unchanged — the failure was transient, so local auth state is preserved.
 *    d. If refresh reports [AuthTokenProvider.RefreshResult.Unauthenticated], the buffered
 *       401 is returned and the local session is collapsed to signed-out via
 *       [authFailureSessionInvalidator].
 *    e. If refresh reports [AuthTokenProvider.RefreshResult.Refreshed], the buffered 401
 *       response is closed, the request is rebuilt with the new bearer token, and executed
 *       exactly **once**. If that retry also returns 401, local auth state is invalidated.
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
    private val authFailureSessionInvalidator: AuthFailureSessionInvalidator,
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
        response.close()

        // Pass the token we attached to the failing request so the provider can detect
        // "cache already advanced past this 401" and coalesce duplicate refreshes.
        when (val refresh = runBlocking { authTokenProvider.refresh(token) }) {
            is AuthTokenProvider.RefreshResult.Failed -> return bufferedResponse
            is AuthTokenProvider.RefreshResult.Unauthenticated -> {
                runCatching {
                    runBlocking { authFailureSessionInvalidator.invalidate() }
                }
                return bufferedResponse
            }
            is AuthTokenProvider.RefreshResult.Refreshed -> {
                bufferedResponse.close()
                val retryRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer ${refresh.accessToken}")
                    .build()
                val retryResponse = chain.proceed(retryRequest)
                if (retryResponse.code == 401) {
                    runCatching {
                        runBlocking { authFailureSessionInvalidator.invalidate() }
                    }
                }
                return retryResponse
            }
        }
    }
}
