package com.becalm.android.unit.core.analytics

import com.becalm.android.core.analytics.BackendProductEventsMirrorClient
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ProductEventsBatchResponse
import com.becalm.android.data.remote.interceptor.AuthInterceptor
import com.becalm.android.data.remote.interceptor.AuthTokenProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class BackendProductEventsMirrorClientSpecTest {

    private val railwayApi: RailwayApi = mockk(relaxed = true)
    private val authTokenProvider: AuthTokenProvider = mockk(relaxed = true)

    @Test
    fun `flush drops pre-auth events without calling protected backend endpoint`() = runTest {
        every { authTokenProvider.currentAccessToken() } returns null

        val result = subject().flush(listOf(event()))

        assertTrue(result)
        coVerify(exactly = 0) { railwayApi.batchProductEvents(any()) }
    }

    @Test
    fun `flush drops debug local-only events without calling protected backend endpoint`() = runTest {
        every { authTokenProvider.currentAccessToken() } returns
            "header.payload.${AuthInterceptor.DEBUG_LOCAL_ONLY_TOKEN_SIGNATURE}"

        val result = subject().flush(listOf(event()))

        assertTrue(result)
        coVerify(exactly = 0) { railwayApi.batchProductEvents(any()) }
    }

    @Test
    fun `flush mirrors events when a backend usable bearer token exists`() = runTest {
        every { authTokenProvider.currentAccessToken() } returns "real-access-token"
        coEvery { railwayApi.batchProductEvents(any()) } returns
            Response.success(ProductEventsBatchResponse(acknowledged = 1))

        val result = subject().flush(listOf(event()))

        assertTrue(result)
        coVerify(exactly = 1) { railwayApi.batchProductEvents(any()) }
    }

    private fun subject(): BackendProductEventsMirrorClient =
        BackendProductEventsMirrorClient(
            railwayApi = railwayApi,
            authTokenProvider = authTokenProvider,
        )

    private fun event(): ProductAnalyticsEvent =
        ProductAnalyticsEvent(
            eventId = "event-1",
            eventName = ProductAnalyticsEvents.SESSION_STARTED,
            occurredAt = Instant.parse("2026-06-04T00:00:00Z"),
            sessionId = "session-1",
            properties = mapOf("entry_source" to "direct"),
        )
}
