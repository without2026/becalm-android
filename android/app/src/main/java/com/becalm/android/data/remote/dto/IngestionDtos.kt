package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import kotlinx.datetime.Instant

/**
 * Represents a single raw ingestion event sent to Railway.
 *
 * Used as items in [BatchUploadRequest.events] for POST /v1/raw_ingestion_events:batch.
 * Mirrors the `raw_ingestion_events` Supabase table (data-model.yml), excluding the
 * Room-only tracking columns: `sync_status`, `retry_count`, `last_attempt_at`.
 *
 * Note: `id` is included as the server-side primary key. For new events, the
 * server may assign it via gen_random_uuid(); clients MAY send it pre-populated
 * with their own UUID v4. `client_event_id` is the idempotency key.
 *
 * @property sourceType Valid values: see [SourceType] constants.
 */
@JsonClass(generateAdapter = true)
public data class RawIngestionEventDto(
    /** Server-assigned UUID primary key. May be null on upload (server generates). */
    @field:Json(name = "id") val id: String? = null,

    /**
     * Client-generated UUID v4 idempotency key.
     * Railway deduplicates on (user_id, client_event_id) UNIQUE constraint.
     * Duplicate submissions receive 200 without a new INSERT.
     */
    @field:Json(name = "client_event_id") val clientEventId: String,

    /**
     * Supabase auth.users UUID of the owning user.
     * Not serialized: the server derives user_id from the Bearer token (api-contract.yml).
     * Retained as a property for internal wiring (Room row, logging, idempotency scoping).
     */
    @Json(ignore = true) val userId: String = "",

    /**
     * Source of this event. Valid values: [SourceType.VOICE], [SourceType.GMAIL],
     * [SourceType.OUTLOOK_MAIL], [SourceType.NAVER_IMAP], [SourceType.DAUM_IMAP],
     * [SourceType.GOOGLE_CALENDAR], [SourceType.OUTLOOK_CALENDAR].
     */
    @field:Json(name = "source_type") val sourceType: String,

    /**
     * Source-system reference: email message_id, voice file URI, calendar event ID, etc.
     * Null if no stable external reference exists.
     */
    @field:Json(name = "source_ref") val sourceRef: String? = null,

    /**
     * Canonicalized counterparty identifier.
     * Precedence: E.164 phone > lowercase email > normalized display name.
     * Null for events with no identifiable counterparty (self-dictated notes).
     */
    @field:Json(name = "person_ref") val personRef: String? = null,

    /**
     * Voice: MediaStore TITLE; email: subject; calendar: event title.
     * Populated at ingestion time.
     */
    @field:Json(name = "event_title") val eventTitle: String? = null,

    /**
     * Voice: first ~200 chars of transcript (after STT); email: first 200 chars of body.
     * Null for calendar events.
     */
    @field:Json(name = "event_snippet") val eventSnippet: String? = null,

    /** Voice only: MediaStore DURATION / 1000. Null for non-voice sources. */
    @field:Json(name = "duration_seconds") val durationSeconds: Int? = null,

    /** Calendar only: event location string. Null for non-calendar sources. */
    @field:Json(name = "location") val location: String? = null,

    /**
     * EMAIL-001 direction hint: `INBOX` or `SENT` for email source types
     * (`gmail` / `outlook_mail` / `naver_imap` / `daum_imap`). Null for every other
     * source type (`voice`, `google_calendar`, `outlook_calendar`, etc.).
     *
     * Forwarded to Railway so that the server-side `person_ref` resolver in the
     * extraction pipeline can replay the same `INBOX → From` / `SENT → To[0]`
     * decision the client already made locally — this keeps server re-derivation
     * idempotent with [com.becalm.android.data.local.db.entity.RawIngestionEventEntity.folder].
     * Spec: `.spec/email-pipeline.spec.yml:15-18`.
     */
    @field:Json(name = "folder") val folder: String? = null,

    /**
     * Number of commitments extracted from this event by the LLM pipeline.
     * Defaults to 0 at upload time; updated after extraction runs.
     */
    @field:Json(name = "commitments_extracted_count") val commitmentsExtractedCount: Int? = null,

    /** ISO 8601 timestamp of when the event occurred (not upload time). */
    @field:Json(name = "timestamp") val timestamp: Instant,
)

/**
 * Request body for POST /v1/raw_ingestion_events:batch.
 *
 * Constraints (api-contract.yml):
 * - max 100 events per batch (HTTP 413 if exceeded)
 * - max 1 MiB body (HTTP 413 if exceeded)
 */
@JsonClass(generateAdapter = true)
public data class BatchUploadRequest(
    @field:Json(name = "events") val events: List<RawIngestionEventDto>,
)

/**
 * Response body for a successful POST /v1/raw_ingestion_events:batch (HTTP 200).
 *
 * Wire format (api-contract.yml):
 *   { acknowledged: int, failed: FailedEvent[] }
 *
 * Partial success is normal: [acknowledged] counts inserted/deduped events;
 * [failed] lists events that were individually rejected. Check [FailedEventDto.retryable]
 * to decide whether to re-enqueue or quarantine.
 */
@JsonClass(generateAdapter = true)
public data class BatchUploadResponse(
    /** Count of events successfully inserted or deduplicated (idempotent resend). */
    @field:Json(name = "acknowledged") val acknowledged: Int,

    /** Events that could not be processed. May be empty. */
    @field:Json(name = "failed") val failed: List<FailedEventDto>,
)

/**
 * Per-event failure detail within [BatchUploadResponse.failed].
 *
 * Mirrors the `FailedEvent` shared type in api-contract.yml.
 *
 * @property reason Valid values: "schema_invalid" | "source_type_unknown" |
 *   "timestamp_parse_error" | "internal_error"
 * @property retryable When true the client should re-enqueue; when false the
 *   event should be moved to a quarantine store and not retried.
 */
@JsonClass(generateAdapter = true)
public data class FailedEventDto(
    /** The [RawIngestionEventDto.clientEventId] of the failing event. */
    @field:Json(name = "client_event_id") val clientEventId: String,

    /**
     * Machine-readable reason code.
     * Valid values: "schema_invalid" | "source_type_unknown" |
     * "timestamp_parse_error" | "internal_error"
     */
    @field:Json(name = "reason") val reason: String,

    /** Human-readable explanation suitable for logging. */
    @field:Json(name = "message") val message: String,

    /**
     * When true the client should re-enqueue this event for retry.
     * When false the event should be quarantined and not retried.
     */
    @field:Json(name = "retryable") val retryable: Boolean,
)
