package com.becalm.android.unit.core.analytics

import com.becalm.android.core.analytics.AmplitudeProductAnalyticsClient
import com.becalm.android.core.analytics.BackendProductEventsMirrorClient
import com.becalm.android.core.analytics.CompositeProductAnalyticsClient
import com.becalm.android.core.analytics.ProductAnalyticsEventQueue
import com.becalm.android.core.analytics.ProductAnalyticsContext
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.observability.ObservabilityClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeProductAnalyticsClientSpecTest {

    private val amplitude: AmplitudeProductAnalyticsClient = mockk(relaxed = true)
    private val backendMirror: BackendProductEventsMirrorClient = mockk(relaxed = true)
    private val observability: ObservabilityClient = mockk(relaxed = true)
    private val eventQueue = InMemoryProductAnalyticsEventQueue()

    @Test
    fun `invalid product event is dropped before vendor or backend calls`() = runTest {
        coEvery { backendMirror.flush(any()) } returns true
        val subject = subject(backgroundScope)

        subject.track(
            event(
                id = "invalid",
                properties = mapOf("account_email" to "tester@example.com"),
            ),
        )
        runCurrent()

        verify(exactly = 0) { amplitude.track(any()) }
        coVerify(exactly = 0) { backendMirror.flush(any()) }
        verify(exactly = 1) {
            observability.addBreadcrumb(
                "analytics",
                "product_event_dropped",
                mapOf("event_name" to ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED),
            )
        }
    }

    @Test
    fun `amplitude failure is isolated and backend mirror still flushes`() = runTest {
        every { amplitude.track(any()) } throws IllegalStateException("amplitude unavailable")
        coEvery { backendMirror.flush(any()) } returns true
        val subject = subject(backgroundScope)

        repeat(BACKEND_BATCH_SIZE) { index ->
            subject.track(event(id = "event-$index"))
        }
        runCurrent()

        verify(exactly = BACKEND_BATCH_SIZE) { amplitude.track(any()) }
        coVerify(exactly = 1) { backendMirror.flush(match { it.size == BACKEND_BATCH_SIZE }) }
        verify(atLeast = 1) {
            observability.addBreadcrumb(
                "analytics",
                "amplitude_track_failed",
                mapOf("event_name" to ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED),
            )
        }
    }

    @Test
    fun `backend mirror failure is isolated after amplitude receives events`() = runTest {
        coEvery { backendMirror.flush(any()) } throws IllegalStateException("backend unavailable")
        val subject = subject(backgroundScope)

        repeat(BACKEND_BATCH_SIZE) { index ->
            subject.track(event(id = "event-$index"))
        }
        runCurrent()

        verify(exactly = BACKEND_BATCH_SIZE) { amplitude.track(any()) }
        coVerify(exactly = 1) { backendMirror.flush(match { it.size == BACKEND_BATCH_SIZE }) }
        assertEquals(BACKEND_BATCH_SIZE, eventQueue.size)
        verify(exactly = 1) {
            observability.addBreadcrumb(
                "analytics",
                "backend_mirror_failed",
                mapOf("batch_size" to BACKEND_BATCH_SIZE.toString()),
            )
        }
    }

    @Test
    fun `backend mirror retries durable queue and removes events only after success`() = runTest {
        coEvery { backendMirror.flush(any()) } throws IllegalStateException("backend unavailable")
        val subject = subject(backgroundScope)

        repeat(BACKEND_BATCH_SIZE) { index ->
            subject.track(event(id = "event-$index"))
        }
        runCurrent()
        assertEquals(BACKEND_BATCH_SIZE, eventQueue.size)

        coEvery { backendMirror.flush(any()) } returns true
        subject.track(event(id = "event-retry-trigger"))
        runCurrent()

        assertEquals(0, eventQueue.size)
        coVerify(atLeast = 1) { backendMirror.flush(match { it.any { event -> event.eventId == "event-0" } }) }
    }

    private fun subject(applicationScope: CoroutineScope): CompositeProductAnalyticsClient =
        CompositeProductAnalyticsClient(
            amplitude = amplitude,
            backendMirror = backendMirror,
            eventQueue = eventQueue,
            observability = observability,
            analyticsContext = ProductAnalyticsContext(),
            applicationScope = applicationScope,
            telemetryEnabled = true,
        )

    private fun event(
        id: String,
        properties: Map<String, Any?> = mapOf(
            "source_type" to "gmail",
            "owner" to "backend",
            "result" to "success",
        ),
    ): ProductAnalyticsEvent =
        ProductAnalyticsEvent(
            eventId = id,
            eventName = ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED,
            occurredAt = Instant.parse("2026-05-16T00:00:00Z"),
            properties = properties,
        )

    private companion object {
        const val BACKEND_BATCH_SIZE = 20
    }

    private class InMemoryProductAnalyticsEventQueue : ProductAnalyticsEventQueue {
        private val queued = mutableListOf<ProductAnalyticsEvent>()
        val size: Int
            get() = queued.size

        override suspend fun enqueue(events: List<ProductAnalyticsEvent>) {
            queued += events
        }

        override suspend fun peek(limit: Int): List<ProductAnalyticsEvent> =
            queued.take(limit)

        override suspend fun remove(eventIds: Set<String>) {
            queued.removeAll { it.eventId in eventIds }
        }

        override suspend fun clearAll() {
            queued.clear()
        }
    }
}
