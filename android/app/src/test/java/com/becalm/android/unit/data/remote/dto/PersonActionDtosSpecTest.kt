package com.becalm.android.unit.data.remote.dto

import com.becalm.android.core.util.addBecalmAdapters
import com.becalm.android.data.remote.dto.CalendarWriteJobResponseDto
import com.becalm.android.data.remote.dto.PersonActionEvidenceOriginalResponseDto
import com.becalm.android.data.remote.dto.PersonActionFeedResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersonActionDtosSpecTest {

    private val moshi = Moshi.Builder()
        .addBecalmAdapters()
        .build()

    @Test
    fun `person action feed response parses evidence backed future generation context`() {
        val json = """
            {
              "data": [
                {
                  "id": "pa-1",
                  "user_id": "user-1",
                  "person_id": "person-1",
                  "person_display_name": "김도현",
                  "surfaces": ["person", "commitment"],
                  "action_kind": "reply",
                  "status": "active",
                  "title": "김도현 · 수정 계약서 회신",
                  "primary_verb": "회신",
                  "short_reason": "근거: 6/1 통화",
                  "commitment_id": "commitment-1",
                  "source_event_id": "source-1",
                  "source_type": "call_recording",
                  "source_ref": "call-1",
                  "due_at": "2026-06-04T03:00:00Z",
                  "due_hint": "tomorrow",
                  "urgency_score": 91.0,
                  "importance_score": 70.0,
                  "confidence": 0.91,
                  "reason_codes": ["source:call_recording", "direction:give"],
                  "evidence_refs": [
                    {
                      "kind": "commitment",
                      "id": "commitment-1",
                      "source_ref": "call-1",
                      "occurred_at": "2026-06-03T01:00:00Z",
                      "label": "6/1 통화",
                      "quote": "이번 주 안에 보내드릴게요."
                    },
                    {
                      "kind": "source_event",
                      "id": "source-1",
                      "source_ref": "call-1",
                      "occurred_at": "2026-06-03T01:00:00Z",
                      "label": "6/1 통화",
                      "quote": "이번 주 안에 보내드릴게요."
                    }
                  ],
                  "input_watermark": "2026-06-03T02:00:00Z",
                  "input_watermarks": [
                    {
                      "input_kind": "commitment",
                      "high_watermark": "2026-06-03T02:00:00Z",
                      "row_count": 1,
                      "digest": "commitment-digest"
                    },
                    {
                      "input_kind": "source_event",
                      "high_watermark": "2026-06-03T01:00:00Z",
                      "row_count": 1,
                      "digest": "source-digest"
                    }
                  ],
                  "computed_at": "2026-06-03T03:00:00Z",
                  "updated_at": "2026-06-03T03:00:00Z"
                }
              ],
              "deleted_ids": [],
              "cursor": "",
              "has_more": false,
              "server_watermark": "2026-06-03T03:00:00Z",
              "input_watermarks": [],
              "recompute_state": "caught_up"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(PersonActionFeedResponseDto::class.java).fromJson(json))
        val action = response.data.single()

        assertEquals("pa-1", action.id)
        assertEquals("김도현", action.personDisplayName)
        assertEquals("근거: 6/1 통화", action.shortReason)
        assertEquals("source-1", action.sourceEventId)
        assertEquals(listOf("source:call_recording", "direction:give"), action.reasonCodes)
        assertEquals(listOf("commitment", "source_event"), action.evidenceRefs.map { it.kind })
        assertEquals("이번 주 안에 보내드릴게요.", action.evidenceRefs[1].quote)
        assertEquals(listOf("commitment", "source_event"), action.inputWatermarks.map { it.inputKind })
    }

    @Test
    fun `person action feed response parses calendar provider write metadata`() {
        val json = """
            {
              "data": [
                {
                  "id": "pa-schedule-1",
                  "user_id": "user-1",
                  "surfaces": ["schedule"],
                  "action_kind": "add_to_calendar",
                  "status": "active",
                  "title": "캘린더 후보",
                  "primary_verb": "후보 확인",
                  "short_reason": "메일에는 있는데 캘린더에는 없습니다.",
                  "commitment_id": "commitment-1",
                  "source_type": "gmail",
                  "source_ref": "mail-1",
                  "urgency_score": 91.0,
                  "importance_score": 82.0,
                  "confidence": 0.88,
                  "reason_codes": ["calendar_missing"],
                  "evidence_refs": [
                    {
                      "kind": "schedule_link",
                      "id": "schedule-link-1",
                      "label": "메일 일정 후보"
                    }
                  ],
                  "input_watermark": "2026-06-03T02:00:00Z",
                  "computed_at": "2026-06-03T03:00:00Z",
                  "updated_at": "2026-06-03T03:00:00Z",
                  "provider_write": {
                    "kind": "add_to_calendar",
                    "state": "ready",
                    "provider": "google_calendar",
                    "source_connection_id": "conn-calendar-write",
                    "schedule_event_link_id": "schedule-link-1",
                    "available_connections": [
                      {
                        "provider": "google_calendar",
                        "source_connection_id": "conn-calendar-write",
                        "account_display_name": "Work Calendar"
                      }
                    ]
                  }
                }
              ],
              "deleted_ids": [],
              "cursor": "",
              "has_more": false,
              "server_watermark": "2026-06-03T03:00:00Z",
              "input_watermarks": [],
              "recompute_state": "caught_up"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(PersonActionFeedResponseDto::class.java).fromJson(json))
        val providerWrite = requireNotNull(response.data.single().providerWrite)

        assertEquals("ready", providerWrite.state)
        assertEquals("google_calendar", providerWrite.provider)
        assertEquals("conn-calendar-write", providerWrite.sourceConnectionId)
        assertEquals("schedule-link-1", providerWrite.scheduleEventLinkId)
        assertEquals("Work Calendar", providerWrite.availableConnections.single().accountDisplayName)
    }

    @Test
    fun `calendar write job response parses direct status contract`() {
        val json = """
            {
              "job_id": "job-1",
              "status": "needs_reauth",
              "accepted": true,
              "retry_after_seconds": 2,
              "provider": "google_calendar",
              "write_kind": "insert_event",
              "action_item_id": "pa-schedule-1",
              "schedule_event_link_id": "schedule-link-1",
              "source_connection_id": "conn-calendar-write",
              "provider_event_id": null,
              "calendar_event_id": null,
              "attempts": 1,
              "error_code": "provider_auth_expired",
              "error_message": "calendar reconnect required",
              "next_attempt_at": null,
              "client_action": "connect_calendar"
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(CalendarWriteJobResponseDto::class.java).fromJson(json))

        assertEquals("job-1", response.jobId)
        assertEquals("needs_reauth", response.status)
        assertEquals(true, response.accepted)
        assertEquals("google_calendar", response.provider)
        assertEquals("schedule-link-1", response.scheduleEventLinkId)
        assertEquals("connect_calendar", response.clientAction)
    }

    @Test
    fun `evidence original response parses bounded audio transcript payload`() {
        val json = """
            {
              "data": {
                "action_item_id": "pa-1",
                "action_status": "active",
                "evidence": {
                  "kind": "source_event",
                  "id": "source-audio-1",
                  "source_ref": "local-recording-1",
                  "label": "Meeting recording",
                  "quote": "SPEAKER_01: send terms"
                },
                "original": {
                  "kind": "source_event",
                  "id": "source-audio-1",
                  "original_available": true,
                  "status": "metadata_resolved",
                  "source_type": "meeting",
                  "event_kind": "meeting",
                  "source_ref": "local-recording-1",
                  "client_event_id": "client-audio-1",
                  "title": "Pricing meeting",
                  "snippet": "SPEAKER_01: send terms",
                  "occurred_at": "2026-06-03T01:00:00Z",
                  "duration_seconds": 180,
                  "raw_body_included": false,
                  "transcript_available": true,
                  "transcript_status": "transcript_resolved",
                  "transcript_provider": "clova",
                  "transcript_source_type": "meeting",
                  "transcript_raw_event_id": "source-audio-1",
                  "transcript_text": "SPEAKER_01: Please send the revised terms tomorrow.",
                  "transcript_segments": [
                    {
                      "speaker": "SPEAKER_01",
                      "start": 1.2,
                      "end": 4.8,
                      "text": "Please send the revised terms tomorrow."
                    }
                  ],
                  "transcript_billable_seconds": 180,
                  "transcript_updated_at": "2026-06-03T01:06:00Z"
                },
                "resolved_at": "2026-06-03T04:05:00Z"
              }
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(PersonActionEvidenceOriginalResponseDto::class.java).fromJson(json))
        val original = response.data.original

        assertEquals("pa-1", response.data.actionItemId)
        assertEquals("source_event", response.data.evidence.kind)
        assertEquals("source-audio-1", original.id)
        assertFalse(original.rawBodyIncluded ?: true)
        assertEquals(true, original.transcriptAvailable)
        assertEquals("transcript_resolved", original.transcriptStatus)
        assertEquals("clova", original.transcriptProvider)
        assertEquals("SPEAKER_01: Please send the revised terms tomorrow.", original.transcriptText)
        assertEquals(1, original.transcriptSegments.size)
        assertEquals("SPEAKER_01", original.transcriptSegments.single().speaker)
        assertEquals(1.2, original.transcriptSegments.single().start ?: -1.0, 0.0)
        assertEquals(180, original.transcriptBillableSeconds)
        assertNotNull(response.data.resolvedAt)
    }

    @Test
    fun `evidence original response parses source status without raw provider error`() {
        val json = """
            {
              "data": {
                "action_item_id": "pa-source",
                "action_status": "active",
                "evidence": {
                  "kind": "source_status",
                  "id": "connection-1",
                  "label": "Work Gmail"
                },
                "original": {
                  "kind": "source_status",
                  "id": "connection-1",
                  "original_available": true,
                  "provider": "google",
                  "capability": "mail",
                  "account_display_name": "Work Gmail",
                  "account_identifier_hint": "wo***@example.test",
                  "status": "needs_reauth",
                  "last_sync_at": "2026-06-01T00:00:00Z",
                  "last_error_present": true,
                  "updated_at": "2026-06-03T02:00:00Z"
                },
                "resolved_at": "2026-06-03T04:05:00Z"
              }
            }
        """.trimIndent()

        val response = requireNotNull(moshi.adapter(PersonActionEvidenceOriginalResponseDto::class.java).fromJson(json))
        val original = response.data.original

        assertEquals("source_status", original.kind)
        assertEquals("wo***@example.test", original.accountIdentifierHint)
        assertEquals(true, original.lastErrorPresent)
        assertNotNull(original.lastSyncAt)
        assertNull(original.transcriptText)
    }
}
