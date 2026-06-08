package com.becalm.android.unit.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.dto.ProductEventDto
import com.becalm.android.data.remote.dto.ProductEventsBatchRequest
import com.becalm.android.data.remote.dto.ProductEventsBatchResponse
import com.squareup.moshi.Moshi
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsDtosSpecTest {

    private val moshi = Moshi.Builder()
        .addBecalmAdapters()
        .build()

    @Test
    fun `product events fail-open response remains parseable`() {
        val json = """
            {
              "acknowledged": 100,
              "persisted": false,
              "error": "analytics_persistence_failed"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(ProductEventsBatchResponse::class.java).fromJson(json))

        assertEquals(100, response.acknowledged)
    }

    @Test
    fun `product events request serializes one ten hundred rows`() {
        val adapter = moshi.adapter(ProductEventsBatchRequest::class.java)

        listOf(1, 10, 100).forEach { scale ->
            val request = ProductEventsBatchRequest(
                events = (0 until scale).map { index ->
                    ProductEventDto(
                        eventId = "event-$index",
                        eventName = "session_started",
                        occurredAt = Instant.parse("2026-05-14T10:00:00Z"),
                        sessionId = "session-1",
                        source = "android",
                        properties = mapOf("surface" to "today", "index" to index),
                    )
                },
            )

            val json = adapter.toJson(request)
            val parsed = requireNotNull(adapter.fromJson(json))

            assertTrue(json.contains("\"event_id\":\"event-0\""))
            assertEquals(scale, parsed.events.size)
        }
    }
}
