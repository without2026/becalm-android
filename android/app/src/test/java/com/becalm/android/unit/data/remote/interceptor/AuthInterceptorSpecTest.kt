package com.becalm.android.unit.data.remote.interceptor

import com.becalm.android.data.auth.AuthFailureSessionInvalidator
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit

class AuthInterceptorSpecTest {

    private val authTokenProvider: AuthTokenProvider = mockk(relaxed = true)
    private val invalidator: AuthFailureSessionInvalidator = mockk(relaxed = true)

    @Test
    fun `non railway host bypasses auth injection and refresh`() {
        every { authTokenProvider.currentAccessToken() } returns "token-1"

        val interceptor = buildInterceptor()
        val chain = FakeChain(
            request = request("https://supabase.example.com/v1/resource"),
            responses = mutableListOf(response(code = 200, body = "ok")),
        )

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        assertNull(chain.proceededRequests.single().header("Authorization"))
        coVerify(exactly = 0) { authTokenProvider.refresh(any()) }
    }

    @Test
    fun `railway request attaches current bearer token`() {
        every { authTokenProvider.currentAccessToken() } returns "token-1"

        val interceptor = buildInterceptor()
        val chain = FakeChain(
            request = request("https://railway.example.com/v1/resource"),
            responses = mutableListOf(response(code = 200, body = "ok")),
        )

        interceptor.intercept(chain)

        assertEquals("Bearer token-1", chain.proceededRequests.single().header("Authorization"))
        coVerify(exactly = 0) { authTokenProvider.refresh(any()) }
    }

    @Test
    fun `401 with transient refresh failure returns original buffered response and preserves session`() {
        every { authTokenProvider.currentAccessToken() } returns "expired-token"
        coEvery { authTokenProvider.refresh("expired-token") } returns AuthTokenProvider.RefreshResult.Failed

        val interceptor = buildInterceptor()
        val chain = FakeChain(
            request = request("https://railway.example.com/v1/resource"),
            responses = mutableListOf(response(code = 401, body = "expired")),
        )

        val result = interceptor.intercept(chain)

        assertEquals(401, result.code)
        assertEquals("expired", result.body!!.string())
        assertEquals(1, chain.proceededRequests.size)
        coVerify(exactly = 0) { invalidator.invalidate() }
    }

    @Test
    fun `401 with unauthenticated refresh invalidates session and returns original 401`() {
        every { authTokenProvider.currentAccessToken() } returns "expired-token"
        coEvery { authTokenProvider.refresh("expired-token") } returns
            AuthTokenProvider.RefreshResult.Unauthenticated

        val interceptor = buildInterceptor()
        val chain = FakeChain(
            request = request("https://railway.example.com/v1/resource"),
            responses = mutableListOf(response(code = 401, body = "expired")),
        )

        val result = interceptor.intercept(chain)

        assertEquals(401, result.code)
        assertEquals("expired", result.body!!.string())
        assertEquals(1, chain.proceededRequests.size)
        coVerify(exactly = 1) { invalidator.invalidate() }
    }

    @Test
    fun `AUTH-004 and AUTH-007 refresh expired bearer and retry railway request exactly once`() {
        every { authTokenProvider.currentAccessToken() } returns "expired-token"
        coEvery { authTokenProvider.refresh("expired-token") } returns
            AuthTokenProvider.RefreshResult.Refreshed("fresh-token")

        val interceptor = buildInterceptor()
        val chain = FakeChain(
            request = request("https://railway.example.com/v1/resource"),
            responses = mutableListOf(
                response(code = 401, body = "expired"),
                response(code = 200, body = "ok"),
            ),
        )

        val result = interceptor.intercept(chain)

        assertEquals(200, result.code)
        assertEquals("Bearer expired-token", chain.proceededRequests[0].header("Authorization"))
        assertEquals("Bearer fresh-token", chain.proceededRequests[1].header("Authorization"))
        coVerify(exactly = 1) { authTokenProvider.refresh("expired-token") }
        coVerify(exactly = 0) { invalidator.invalidate() }
    }

    @Test
    fun `AUTH-007 closes the first 401 response before retrying on the same call`() {
        every { authTokenProvider.currentAccessToken() } returns "expired-token"
        coEvery { authTokenProvider.refresh("expired-token") } returns
            AuthTokenProvider.RefreshResult.Refreshed("fresh-token")

        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val url = server.url("/v1/resource")
            val interceptor = AuthInterceptor(
                authTokenProvider = authTokenProvider,
                authFailureSessionInvalidator = invalidator,
                railwayHost = url.host,
            )
            val client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build()

            client.newCall(Request.Builder().url(url).build()).execute().use { result ->
                assertEquals(200, result.code)
                assertEquals("ok", result.body!!.string())
            }

            assertEquals("Bearer expired-token", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
            coVerify(exactly = 1) { authTokenProvider.refresh("expired-token") }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `AUTH-007 second 401 after a single refresh invalidates session without looping`() {
        every { authTokenProvider.currentAccessToken() } returns "expired-token"
        coEvery { authTokenProvider.refresh("expired-token") } returns
            AuthTokenProvider.RefreshResult.Refreshed("fresh-token")

        val interceptor = buildInterceptor()
        val chain = FakeChain(
            request = request("https://railway.example.com/v1/resource"),
            responses = mutableListOf(
                response(code = 401, body = "expired"),
                response(code = 401, body = "still-expired"),
            ),
        )

        val result = interceptor.intercept(chain)

        assertEquals(401, result.code)
        assertEquals("still-expired", result.body!!.string())
        assertEquals(2, chain.proceededRequests.size)
        coVerify(exactly = 1) { authTokenProvider.refresh("expired-token") }
        coVerify(exactly = 1) { invalidator.invalidate() }
    }

    private fun buildInterceptor(): AuthInterceptor = AuthInterceptor(
        authTokenProvider = authTokenProvider,
        authFailureSessionInvalidator = invalidator,
        railwayHost = "railway.example.com",
    )

    private fun request(url: String): Request = Request.Builder().url(url.toHttpUrl()).build()

    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(request("https://railway.example.com/v1/resource"))
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private class FakeChain(
        private val request: Request,
        val responses: MutableList<Response>,
    ) : Interceptor.Chain {
        val proceededRequests: MutableList<Request> = mutableListOf()

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceededRequests += request
            return responses.removeAt(0).newBuilder().request(request).build()
        }

        override fun connection() = null
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis(): Int = 0
        override fun readTimeoutMillis(): Int = 0
        override fun writeTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
