package com.becalm.android.unit.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.dto.ProcessingStatusResponseDto
import com.squareup.moshi.Moshi
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingStatusDtosSpecTest {

    private val moshi = Moshi.Builder()
        .addBecalmAdapters()
        .build()

    @Test
    fun `processing status response parses operational retry aggregate`() {
        val json = """
            {
              "status": "processing",
              "latest_status": "processing",
              "processing_count": 12,
              "review_required_count": 0,
              "failed_count": 0,
              "oldest_started_at": "2026-05-26T00:00:00Z",
              "retryable": true,
              "next_retry_at": "2026-05-26T00:02:30Z",
              "retry_after_seconds": 30
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(ProcessingStatusResponseDto::class.java).fromJson(json))

        assertEquals("processing", response.status)
        assertEquals("processing", response.latestStatus)
        assertEquals(12, response.processingCount)
        assertEquals(0, response.reviewRequiredCount)
        assertEquals(0, response.failedCount)
        assertEquals(Instant.parse("2026-05-26T00:00:00Z"), response.oldestStartedAt)
        assertTrue(response.retryable)
        assertEquals(Instant.parse("2026-05-26T00:02:30Z"), response.nextRetryAt)
        assertEquals(30L, response.retryAfterSeconds)
    }

    @Test
    fun `processing status response parses idle null retry fields`() {
        val json = """
            {
              "status": "idle",
              "latest_status": "idle",
              "processing_count": 0,
              "review_required_count": 0,
              "failed_count": 0,
              "oldest_started_at": null,
              "retryable": false,
              "next_retry_at": null,
              "retry_after_seconds": null
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(ProcessingStatusResponseDto::class.java).fromJson(json))

        assertEquals("idle", response.status)
        assertEquals(0, response.processingCount)
        assertNull(response.oldestStartedAt)
        assertNull(response.nextRetryAt)
        assertNull(response.retryAfterSeconds)
    }
}
