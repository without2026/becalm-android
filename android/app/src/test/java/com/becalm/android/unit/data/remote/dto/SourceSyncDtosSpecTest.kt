package com.becalm.android.unit.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.dto.CalendarOAuthStartResponse
import com.becalm.android.data.remote.dto.CalendarOAuthStatusResponse
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.MailOAuthStartResponse
import com.becalm.android.data.remote.dto.MailOAuthStatusResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.RawIngestionEventsResponse
import com.becalm.android.data.remote.dto.SourceSyncJobResponse
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSyncDtosSpecTest {

    private val moshi = Moshi.Builder()
        .addBecalmAdapters()
        .build()

    @Test
    fun `oauth start responses parse provider authorization url and state`() {
        val calendarJson = """
            {
              "provider": "google_calendar",
              "authorization_url": "https://accounts.google.com/o/oauth2/v2/auth?client_id=test",
              "redirect_uri": "https://api.becalm.test/v1/oauth/calendar/google_calendar:callback",
              "state": "signed-calendar-state"
            }
        """.trimIndent()
        val mailJson = """
            {
              "provider": "gmail",
              "authorization_url": "https://accounts.google.com/o/oauth2/v2/auth?client_id=test",
              "redirect_uri": "https://api.becalm.test/v1/oauth/mail/gmail:callback",
              "state": "signed-mail-state"
            }
        """.trimIndent()

        val calendar = requireNotNull(moshi.adapter(CalendarOAuthStartResponse::class.java).fromJson(calendarJson))
        val mail = requireNotNull(moshi.adapter(MailOAuthStartResponse::class.java).fromJson(mailJson))

        assertEquals("google_calendar", calendar.provider)
        assertEquals("signed-calendar-state", calendar.state)
        assertEquals("gmail", mail.provider)
        assertEquals("signed-mail-state", mail.state)
    }

    @Test
    fun `oauth status responses parse one ten hundred source connections`() {
        listOf(1, 10, 100).forEach { scale ->
            val rows = (0 until scale).joinToString(",") { index ->
                """
                    {
                      "id": "source-connection-$index",
                      "user_id": "user-1",
                      "provider": "google",
                      "capability": "mail",
                      "account_identifier": "person$index@example.com",
                      "account_display_name": "Person $index",
                      "ownership": "self",
                      "status": "connected",
                      "linked_self_anchor_id": "anchor-$index",
                      "last_error": null,
                      "deleted_at": null
                    }
                """.trimIndent()
            }
            val calendarJson = """
                {
                  "provider": "google_calendar",
                  "connected": true,
                  "account_email": "tester@example.com",
                  "display_name": "Tester",
                  "connections": [$rows]
                }
            """.trimIndent()
            val mailJson = """
                {
                  "provider": "gmail",
                  "connected": true,
                  "account_email": "tester@example.com",
                  "display_name": "Tester",
                  "connections": [$rows]
                }
            """.trimIndent()

            val calendar = requireNotNull(moshi.adapter(CalendarOAuthStatusResponse::class.java).fromJson(calendarJson))
            val mail = requireNotNull(moshi.adapter(MailOAuthStatusResponse::class.java).fromJson(mailJson))

            assertTrue(calendar.connected)
            assertEquals(scale, calendar.connections.size)
            assertEquals("source-connection-0", calendar.connections.first().id)
            assertEquals(scale, mail.connections.size)
            assertEquals("person0@example.com", mail.connections.first().accountIdentifier)
        }
    }

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

    @Test
    fun `raw ingestion mirror response tolerates nullable email header booleans`() {
        val json = """
            {
              "data": [
                {
                  "id": "source-event-1",
                  "client_event_id": "client-event-1",
                  "source_type": "gmail",
                  "source_ref": "gmail-message-1",
                  "event_title": "Follow up",
                  "event_snippet": "Please send the proposal.",
                  "timestamp": "2026-06-04T09:00:00Z",
                  "has_list_unsubscribe": null,
                  "has_list_id": null,
                  "auto_submitted": null,
                  "bulk_precedence": null
                }
              ],
              "cursor": "cursor-1",
              "has_more": false
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(RawIngestionEventsResponse::class.java).fromJson(json))

        assertEquals(1, response.data.size)
        assertEquals(null, response.data.first().hasListUnsubscribe)
        assertEquals("source-event-1", response.data.first().id)
    }
}
