package com.becalm.android.unit.data.remote.dto

import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.SourceSyncJobResponse
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSyncDtosSpecTest {

    private val moshi = Moshi.Builder().build()

    @Test
    fun `mail sync response parses durable job fields while preserving synced`() {
        val json = """
            {
              "synced": 0,
              "job_id": "job-mail-1",
              "status": "pending",
              "accepted": true,
              "retry_after_seconds": 10,
              "stage": "queued",
              "progress": 0.05,
              "message": "Preparing activation preview",
              "sync_mode": "activation_preview",
              "error_code": "backpressure_delayed",
              "error_message": "Source sync accepted but delayed because worker backlog is high"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(MailSyncResponse::class.java).fromJson(json))

        assertEquals(0, response.synced)
        assertEquals("job-mail-1", response.jobId)
        assertEquals("pending", response.status)
        assertTrue(response.accepted)
        assertEquals(10L, response.retryAfterSeconds)
        assertEquals("queued", response.stage)
        assertEquals(0.05, response.progress ?: -1.0, 0.0001)
        assertEquals("Preparing activation preview", response.message)
        assertEquals("activation_preview", response.syncMode)
        assertEquals("backpressure_delayed", response.errorCode)
        assertEquals("Source sync accepted but delayed because worker backlog is high", response.errorMessage)
    }

    @Test
    fun `calendar sync response parses durable job fields while preserving synced`() {
        val json = """
            {
              "synced": 0,
              "job_id": "job-calendar-1",
              "status": "pending",
              "accepted": true,
              "retry_after_seconds": 10,
              "error_code": "backpressure_delayed",
              "error_message": "Source sync accepted but delayed because worker backlog is high"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(CalendarSyncResponse::class.java).fromJson(json))

        assertEquals(0, response.synced)
        assertEquals("job-calendar-1", response.jobId)
        assertEquals("pending", response.status)
        assertTrue(response.accepted)
        assertEquals(10L, response.retryAfterSeconds)
        assertEquals("backpressure_delayed", response.errorCode)
        assertEquals("Source sync accepted but delayed because worker backlog is high", response.errorMessage)
    }

    @Test
    fun `source sync job response parses polling status`() {
        val json = """
            {
              "job_id": "job-1",
              "status": "running",
              "accepted": true,
              "retry_after_seconds": 10,
              "synced": 2,
              "provider": "gmail",
              "capability": "mail",
              "source_connection_id": "conn-1",
              "attempts": 1,
              "stage": "extracting",
              "progress": 0.55,
              "message": "메일 속 약속 후보를 확인하고 있습니다",
              "sync_mode": "activation_preview"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(SourceSyncJobResponse::class.java).fromJson(json))

        assertEquals("job-1", response.jobId)
        assertEquals("running", response.status)
        assertTrue(response.accepted)
        assertEquals(10L, response.retryAfterSeconds)
        assertEquals(2, response.synced)
        assertEquals("gmail", response.provider)
        assertEquals("mail", response.capability)
        assertEquals("conn-1", response.sourceConnectionId)
        assertEquals(1, response.attempts)
        assertEquals("extracting", response.stage)
        assertEquals(0.55, response.progress ?: -1.0, 0.0001)
        assertEquals("메일 속 약속 후보를 확인하고 있습니다", response.message)
        assertEquals("activation_preview", response.syncMode)
    }
}
