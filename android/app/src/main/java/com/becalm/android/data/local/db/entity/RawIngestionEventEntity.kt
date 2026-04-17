package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Room entity mirroring the `raw_ingestion_events` Supabase table.
 *
 * Columns are a 1-to-1 mapping of `data-model.yml § raw_ingestion_events`.
 * Three Room-only tracking columns ([syncStatus], [retryCount], [lastAttemptAt])
 * are never uploaded to Railway or Supabase; they exist solely to drive the
 * local sync queue managed by the ingestion WorkManager pipeline.
 *
 * SP-13 (BeCalmDatabase) supplies the TypeConverter that maps [Instant] ↔ [Long]
 * (epoch milliseconds). Do not add inline converters here.
 *
 * Indices defined per spec (`data-model.yml § raw_ingestion_events.indexes`):
 * - `idx_raw_events_user_sync` — supports [RawIngestionEventDao.findPendingForUpload]
 * - `idx_raw_events_user_time` — supports chronological timeline queries
 * - `idx_raw_events_user_person_time` — supports PersonDetailScreen timeline query
 *
 * Logical foreign key (not enforced by Room):
 * - `user_id` → `auth.users.id` (many-to-one, on_delete: cascade) per
 *   `data-model.yml § relationships`. `auth.users` is a Supabase Auth-managed table
 *   (see `data-model.yml § migration_notes`) and is not a Room entity, so a Room
 *   `@ForeignKey` annotation is not possible. Referential integrity is enforced at
 *   the Supabase/Railway tier; the Android client treats `user_id` as an opaque
 *   server-owned UUID.
 *
 * Idempotency note:
 * Dedup on `(user_id, client_event_id)` is enforced at Railway/Supabase via a UNIQUE
 * constraint; the local Room layer relies on the read-then-insert guard in
 * [RawIngestionEventDao.findByClientEventId] and does NOT declare a local UNIQUE
 * index (out of spec — `data-model.yml § raw_ingestion_events.indexes` lists only
 * the three btree indexes above).
 */
@Entity(
    tableName = "raw_ingestion_events",
    indices = [
        Index(
            name = "idx_raw_events_user_sync",
            value = ["user_id", "sync_status"],
        ),
        Index(
            name = "idx_raw_events_user_time",
            value = ["user_id", "timestamp"],
        ),
        Index(
            name = "idx_raw_events_user_person_time",
            value = ["user_id", "person_ref", "timestamp"],
        ),
    ],
)
public data class RawIngestionEventEntity(

    /**
     * Server-assigned UUID primary key (gen_random_uuid() on Supabase).
     * Clients pre-populate this with a UUID v4 before inserting locally so that
     * the same value is forwarded to Railway in the batch upload payload.
     */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Supabase auth.users UUID of the owning user. */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * Client-generated UUID v4 idempotency key.
     * Railway deduplicates on (user_id, client_event_id) UNIQUE constraint.
     * Queried by [RawIngestionEventDao.findByClientEventId] before every insert.
     */
    @ColumnInfo(name = "client_event_id")
    val clientEventId: String,

    /**
     * Source of this event.
     * Valid values: voice | gmail | outlook_mail | naver_imap | daum_imap |
     * google_calendar | outlook_calendar.
     */
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    /**
     * Source-system reference: email message_id, voice file URI, calendar event ID, etc.
     * Null if no stable external reference exists for this source type.
     */
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,

    /**
     * Canonicalized counterparty identifier.
     * Precedence: E.164 phone > lowercase email > normalized display name.
     * Null for events with no identifiable counterparty (e.g., self-dictated notes).
     * Null values appear under the "Unassigned" group in the UI.
     */
    @ColumnInfo(name = "person_ref")
    val personRef: String? = null,

    /**
     * Voice: MediaStore TITLE; email: subject; calendar: event title.
     * Populated at ingestion time. Null if the source does not produce a title.
     */
    @ColumnInfo(name = "event_title")
    val eventTitle: String? = null,

    /**
     * Voice: first ~200 chars of the first extracted commitment's [quote] field, populated after
     * [com.becalm.android.worker.VoiceUploadWorker] receives a successful Railway response.
     * Transcript itself is never persisted (voice-pipeline.spec.yml v2 invariant) — the quote
     * is the only persisted text for voice events. Null when no commitments were extracted.
     * Email: first 200 chars of body.
     * Null for calendar events.
     */
    @ColumnInfo(name = "event_snippet")
    val eventSnippet: String? = null,

    /**
     * Voice only: MediaStore DURATION / 1000.
     * Null for all non-voice source types.
     */
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int? = null,

    /**
     * Calendar only: event location string.
     * Null for all non-calendar source types.
     */
    @ColumnInfo(name = "location")
    val location: String? = null,

    /**
     * Number of commitments extracted from this event by the LLM pipeline.
     * Defaults to 0 at ingestion time; updated after the extraction worker runs.
     */
    @ColumnInfo(name = "commitments_extracted_count")
    val commitmentsExtractedCount: Int = 0,

    /**
     * Timestamp of when the event occurred (not upload or extraction time).
     * Stored as epoch milliseconds via the BeCalmDatabase TypeConverter.
     */
    @ColumnInfo(name = "timestamp")
    val timestamp: Instant,

    /**
     * Room-only sync state machine column. Never uploaded to Railway or Supabase.
     * Valid values:
     * - "pending"           — ready for Railway upload.
     * - "synced"            — successfully uploaded to Railway/Supabase.
     * - "failed"            — upload exhausted max retries; event quarantined.
     * - "awaiting_consent"  — voice source only. pipa_third_party_consent=false at worker
     *                         run time; upload blocked until consent is granted (VOI-004).
     *                         Transitions to "pending" when [com.becalm.android.data.local.db.dao.RawIngestionEventDao.releaseAwaitingConsentVoice] is called.
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",

    /**
     * Room-only retry counter. Never uploaded to Railway or Supabase.
     * Incremented by [RawIngestionEventDao.markFailed] on each failed attempt.
     * The sync worker reads this to apply exponential back-off and to decide
     * when to quarantine a persistently failing event.
     */
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    /**
     * Room-only timestamp of the most recent upload attempt. Never uploaded.
     * Set by [RawIngestionEventDao.markFailed] on each attempt.
     * Null until the first upload attempt is made.
     */
    @ColumnInfo(name = "last_attempt_at")
    val lastAttemptAt: Instant? = null,
)
