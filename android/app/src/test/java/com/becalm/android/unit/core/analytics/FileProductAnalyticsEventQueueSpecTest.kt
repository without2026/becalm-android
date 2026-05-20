package com.becalm.android.unit.core.analytics

import com.becalm.android.core.analytics.FileProductAnalyticsEventQueue
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import com.becalm.android.core.util.addBecalmAdapters
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProductAnalyticsEventQueueSpecTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `queued events survive a new queue instance until removed after ack`() = runTest {
        val file = File(temporaryFolder.root, "analytics/product-events.jsonl")
        val first = queue(file)
        first.enqueue(listOf(event("event-1"), event("event-2")))

        val restored = queue(file)
        assertEquals(listOf("event-1", "event-2"), restored.peek(limit = 20).map { it.eventId })

        restored.remove(setOf("event-1"))

        val afterAck = queue(file)
        assertEquals(listOf("event-2"), afterAck.peek(limit = 20).map { it.eventId })
    }

    @Test
    fun `queue keeps the newest events when bounded capacity is exceeded`() = runTest {
        val file = File(temporaryFolder.root, "analytics/product-events.jsonl")
        val subject = queue(file, maxQueuedEvents = 3)

        subject.enqueue(
            listOf(
                event("event-1"),
                event("event-2"),
                event("event-3"),
                event("event-4"),
            ),
        )
        subject.enqueue(listOf(event("event-5")))

        assertEquals(listOf("event-3", "event-4", "event-5"), subject.peek(limit = 20).map { it.eventId })
    }

    private fun queue(
        file: File,
        maxQueuedEvents: Int = 1_000,
    ): FileProductAnalyticsEventQueue =
        FileProductAnalyticsEventQueue(
            queueFile = file,
            moshi = Moshi.Builder()
                .addBecalmAdapters()
                .add(KotlinJsonAdapterFactory())
                .build(),
            ioDispatcher = Dispatchers.Unconfined,
            maxQueuedEvents = maxQueuedEvents,
        )

    private fun event(id: String): ProductAnalyticsEvent =
        ProductAnalyticsEvent(
            eventId = id,
            eventName = ProductAnalyticsEvents.SOURCE_SYNC_COMPLETED,
            occurredAt = Instant.parse("2026-05-18T00:00:00Z"),
            properties = mapOf("source_type" to "gmail", "result" to "success"),
        )
}
