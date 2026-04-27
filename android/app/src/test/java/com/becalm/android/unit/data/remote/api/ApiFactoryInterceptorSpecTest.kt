package com.becalm.android.unit.data.remote.api

import com.becalm.android.data.auth.AuthFailureSessionInvalidator
import com.becalm.android.data.remote.api.ApiFactory
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import com.becalm.android.data.remote.interceptor.IdempotencyKeyProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiFactoryInterceptorSpecTest {

    @Test
    fun `SYNC-002 reuses idempotency key across retry interceptor attempts`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500).setBody("transient"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val idempotencyProvider = CountingIdempotencyKeyProvider()
            val client = ApiFactory.createOkHttpClient(
                authProvider = StaticAuthTokenProvider(),
                authFailureSessionInvalidator = NoOpAuthFailureSessionInvalidator,
                idempotencyProvider = idempotencyProvider,
                railwayHost = server.url("/").host,
                isDebug = false,
            )

            client.newCall(idempotentPost(server))
                .execute()
                .use { response ->
                    assertEquals(200, response.code)
                }

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertNull(first.getHeader("X-BeCalm-Idempotent"))
            assertNull(second.getHeader("X-BeCalm-Idempotent"))
            assertEquals("idem-1", first.getHeader("Idempotency-Key"))
            assertEquals("idem-1", second.getHeader("Idempotency-Key"))
            assertEquals(1, idempotencyProvider.calls)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `SYNC-002 reuses idempotency key across auth refresh retry`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("expired"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val idempotencyProvider = CountingIdempotencyKeyProvider()
            val client = ApiFactory.createOkHttpClient(
                authProvider = RefreshingAuthTokenProvider(),
                authFailureSessionInvalidator = NoOpAuthFailureSessionInvalidator,
                idempotencyProvider = idempotencyProvider,
                railwayHost = server.url("/").host,
                isDebug = false,
            )

            client.newCall(idempotentPost(server))
                .execute()
                .use { response ->
                    assertEquals(200, response.code)
                }

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("idem-1", first.getHeader("Idempotency-Key"))
            assertEquals("idem-1", second.getHeader("Idempotency-Key"))
            assertEquals("Bearer expired-token", first.getHeader("Authorization"))
            assertEquals("Bearer fresh-token", second.getHeader("Authorization"))
            assertEquals(1, idempotencyProvider.calls)
        } finally {
            server.shutdown()
        }
    }

    private fun idempotentPost(server: MockWebServer): Request =
        Request.Builder()
            .url(server.url("/v1/raw_ingestion_events:batch"))
            .header("X-BeCalm-Idempotent", "1")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

    private class CountingIdempotencyKeyProvider : IdempotencyKeyProvider {
        var calls: Int = 0
            private set

        override fun generate(): String {
            calls += 1
            return "idem-$calls"
        }
    }

    private open class StaticAuthTokenProvider : AuthTokenProvider {
        override fun currentAccessToken(): String = "expired-token"

        override suspend fun refresh(previousAccessToken: String): AuthTokenProvider.RefreshResult =
            AuthTokenProvider.RefreshResult.Failed
    }

    private class RefreshingAuthTokenProvider : StaticAuthTokenProvider() {
        override suspend fun refresh(previousAccessToken: String): AuthTokenProvider.RefreshResult =
            AuthTokenProvider.RefreshResult.Refreshed("fresh-token")
    }

    private object NoOpAuthFailureSessionInvalidator : AuthFailureSessionInvalidator {
        override suspend fun invalidate() = Unit
    }
}
