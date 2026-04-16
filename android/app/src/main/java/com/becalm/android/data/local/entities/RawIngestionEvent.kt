package com.becalm.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// spec: data-model — raw_ingestion_events Room entity
// Mirrors Supabase raw_ingestion_events table.
// sync_status / retry_count / last_attempt_at are Room-only tracking columns — not uploaded to Supabase.

@Entity(tableName = "raw_ingestion_events")
data class RawIngestionEvent(
    // spec: ING-011, ING-015 — client-generated UUID v4 idempotency key
    @PrimaryKey
    @ColumnInfo(name = "client_event_id")
    val clientEventId: String = UUID.randomUUID().toString(),

    // spec: ING-001..ING-010 — source types
    // enum: voice | gmail | outlook_mail | naver_imap | daum_imap | google_calendar | outlook_calendar
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    // External identifier: file URI (voice), message_id (email), calendar event id
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,

    // spec: data-model — person_ref precedence: E.164 phone > lowercase email > normalized display_name
    // NULL for events with no identifiable counterparty (unassigned section in UI)
    @ColumnInfo(name = "person_ref")
    val personRef: String? = null,

    // voice: MediaStore TITLE / email: subject / calendar: event title
    @ColumnInfo(name = "event_title")
    val eventTitle: String? = null,

    // voice: first ~200 chars of transcript (populated after STT completes via VOI-001)
    // email: first 200 chars of body
    @ColumnInfo(name = "event_snippet")
    val eventSnippet: String? = null,

    // voice only: MediaStore DURATION / 1000 — null for non-voice
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int? = null,

    // calendar only — null for non-calendar
    @ColumnInfo(name = "location")
    val location: String? = null,

    // spec: VOI-002 — updated after LLM extraction pipeline completes
    @ColumnInfo(name = "commitments_extracted_count")
    val commitmentsExtractedCount: Int = 0,

    // Event timestamp (ISO 8601 epoch millis stored as Long)
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    // spec: ING-002, ING-003 — Room-side sync tracking (NOT uploaded to Supabase)
    // enum: pending | synced | failed | quarantined
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Long? = null
) {
    object SyncStatus {
        const val PENDING = "pending"
        const val SYNCED = "synced"
        const val FAILED = "failed"
        const val QUARANTINED = "quarantined"
    }

    object SourceType {
        const val VOICE = "voice"
        const val GMAIL = "gmail"
        const val OUTLOOK_MAIL = "outlook_mail"
        const val NAVER_IMAP = "naver_imap"
        const val DAUM_IMAP = "daum_imap"
        const val GOOGLE_CALENDAR = "google_calendar"
        const val OUTLOOK_CALENDAR = "outlook_calendar"
    }
}
