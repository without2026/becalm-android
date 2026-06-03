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
 * Room-only tracking columns ([syncStatus], [retryCount], [lastAttemptAt], [lastError])
 * are never uploaded to Railway or Supabase; they exist solely to drive the
 * local sync queue managed by the ingestion WorkManager pipeline.
 *
 * SP-13 (BeCalmDatabase) supplies the TypeConverter that maps [Instant] ↔ [Long]
 * (epoch milliseconds). Do not add inline converters here.
 *
 * Indices defined per spec (`data-model.yml § raw_ingestion_events.indexes`):
 * - `idx_raw_events_user_sync` — supports [RawIngestionEventDao.findPendingForUpload]
 * - `idx_raw_events_user_time` — supports chronological timeline queries
 * - `idx_raw_events_user_counterparty_time` — supports counterparty-scoped local queries
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
 * Dedup on `(user_id, client_event_id)` is enforced both locally and at
 * Railway/Supabase via a UNIQUE constraint. Local enforcement is required because
 * IMAP and MediaStore workers intentionally re-discover rows on cursor rebuilds and
 * one-second overlap scans.
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
            name = "idx_raw_events_user_counterparty_time",
            value = ["user_id", "counterparty_ref", "timestamp"],
        ),
        Index(
            name = "ux_raw_events_user_client_event",
            value = ["user_id", "client_event_id"],
            unique = true,
        ),
        Index(
            name = "idx_raw_events_user_source_conversation_time",
            value = ["user_id", "source_type", "conversation_ref", "timestamp"],
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
     * Client-generated UUID idempotency key. Fresh ad-hoc callers use UUID v4;
     * source adapters may use deterministic name-based UUIDs derived from provider
     * identifiers.
     * Railway deduplicates on (user_id, client_event_id) UNIQUE constraint.
     * Queried by [RawIngestionEventDao.findByClientEventId] before every insert.
     */
    @ColumnInfo(name = "client_event_id")
    val clientEventId: String,

    /**
     * Source of this event.
     * Valid values: voice | call_recording | meeting | message_screenshot |
     * gmail | outlook_mail | naver_imap | daum_imap | google_calendar | outlook_calendar.
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
    @ColumnInfo(name = "counterparty_ref")
    val counterpartyRef: String? = null,

    /**
     * Voice/meeting/message_screenshot: file display name;
     * email: subject; calendar: event title.
     * Populated at ingestion time. Null if the source does not produce a title.
     */
    @ColumnInfo(name = "event_title")
    val eventTitle: String? = null,

    /**
     * Voice: first ~200 chars of the first extracted voice item's [quote] field, populated after
     * [com.becalm.android.worker.VoiceUploadWorker] receives a successful Railway response.
     * Transcript itself is never persisted (voice-pipeline.spec.yml v2 invariant) — the quote
     * is the only persisted text for voice events. Null when no items were extracted.
     * Email: body_plain[:200] → Jsoup(html).text()[:200] → subject[:200], whitespace collapsed.
     * See [com.becalm.android.domain.email.EmailSnippetBuilder.buildSnippet] & spec EMAIL-003.
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
     * Provider or derived thread identifier for email/message-like sources.
     *
     * This lets local completion matching distinguish "the counterparty completed a
     * previous item in the same thread" from unrelated messages with the same person.
     */
    @ColumnInfo(name = "conversation_ref")
    val conversationRef: String? = null,

    /**
     * EMAIL-001 direction hint: `INBOX` or `SENT` for email source types
     * (`gmail` / `outlook_mail` / `naver_imap` / `daum_imap`). Null for all other
     * sources (`voice`, `google_calendar`, `outlook_calendar`, etc.).
     *
     * The folder label reaches Room via the current ingestion owner and is mirrored here
     * so that downstream consumers (SyncWorker, UI timelines) can read the hint
     * without joining against [EmailBodyEntity]. EMAIL-002 uses the hint to pick the
     * correct participant column when deriving [counterpartyRef]: `INBOX` → `From`,
     * `SENT` → first `To[0]`. Spec: `.spec/email-pipeline.spec.yml:15-18`.
     *
     * Nullable at the schema level because `ALTER TABLE ADD COLUMN` on SQLite forbids
     * `NOT NULL` without a DEFAULT. Application-layer invariant: email ingestion owners
     * MUST always populate this column; non-email sources MUST leave it null.
     */
    @ColumnInfo(name = "folder")
    val folder: String? = null,

    /**
     * Count of persisted trackable items extracted from this event by the LLM pipeline.
     *
     * For voice this now includes `action | schedule | decision` rows persisted into the
     * expanded `commitments` table. The column name is retained for backward compatibility
     * with existing Room/UI surfaces and backend wire format.
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
     * - "awaiting_consent"  — device-owned extraction sources. pipa_third_party_consent=false at worker
     *                         run time; upload blocked until consent is granted (VOI-004).
     *                         Transitions to "pending" only after consent and per-file
     *                         processing confirmation are both present.
     * - "detected_pending_confirmation" — local audio file was discovered, but the user has
     *                         not approved CLOVA/STT processing for that file.
     * - "skipped_by_user"    — user dismissed a detected local audio file without processing it.
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",

    /**
     * Room-only user approval timestamp for billable local audio processing.
     *
     * Local audio files may be discovered automatically, but upload/STT/diarization must not
     * start until this field is non-null. Manual imports set it at the explicit import action.
     */
    @ColumnInfo(name = "processing_confirmed_at")
    val processingConfirmedAt: Instant? = null,

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

    /**
     * Room-only machine-readable failure reason for the most recent terminal upload/extraction
     * failure. Preserves Railway `BatchUploadResponse.failed.reason` so failed rows can be
     * audited and repair jobs can distinguish legacy unknown failures from deterministic
     * server rejections.
     */
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
)
