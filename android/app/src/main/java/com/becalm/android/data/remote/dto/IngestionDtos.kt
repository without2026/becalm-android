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
     * Client-generated UUID idempotency key. Some adapters use deterministic
     * name-based UUIDs derived from source-system ids.
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
     * Voice: first ~200 chars of transcript (after STT).
     * Email: body_plain[:200] → Jsoup(html).text()[:200] → subject[:200], whitespace collapsed.
     * See [com.becalm.android.domain.email.EmailSnippetBuilder.buildSnippet] & spec EMAIL-003.
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

    /**
     * Email-only extraction context sent to Railway / Vertex Gemini. This field is not stored
     * in `raw_ingestion_events`; the backend consumes it during the batch request to extract
     * action / schedule / decision items from Naver, Daum, Gmail, and Outlook mail.
     */
    @field:Json(name = "email_body_plain") val emailBodyPlain: String? = null,

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
 * Paginated response for GET /v1/raw_ingestion_events.
 *
 * Used to mirror backend-managed raw mail events (Gmail / Outlook Mail) back into
 * the local Room cache after Railway performs OAuth-provider sync.
 */
@JsonClass(generateAdapter = true)
public data class RawIngestionEventsResponse(
    @field:Json(name = "data") val data: List<RawIngestionEventDto>,
    @field:Json(name = "cursor") val cursor: String,
    @field:Json(name = "has_more") val hasMore: Boolean,
)

/**
 * Person candidate emitted by backend AI extraction for backend-managed sources.
 *
 * Voice/call candidates are stored directly from the `TranscribeExtractResponse`.
 * Gmail/Outlook candidates are generated on Railway during backend-managed mail sync
 * and mirrored through this DTO into the same local Room table.
 */
@JsonClass(generateAdapter = true)
public data class SourcePersonCandidateDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "source_type") val sourceType: String,
    @field:Json(name = "source_ref") val sourceRef: String,
    @field:Json(name = "candidate_ref") val candidateRef: String,
    @field:Json(name = "role") val role: String,
    @field:Json(name = "name") val name: String? = null,
    @field:Json(name = "email") val email: String? = null,
    @field:Json(name = "phone") val phone: String? = null,
    @field:Json(name = "organization") val organization: String? = null,
    @field:Json(name = "evidence") val evidence: String? = null,
    @field:Json(name = "confidence") val confidence: Double = 0.0,
    @field:Json(name = "created_at") val createdAt: Instant,
)

/**
 * Paginated response for GET /v1/source_person_candidates.
 *
 * Mirrored into Room so all source types feed the same PersonInteractionIndexWorker.
 */
@JsonClass(generateAdapter = true)
public data class SourcePersonCandidatesResponse(
    @field:Json(name = "data") val data: List<SourcePersonCandidateDto>,
    @field:Json(name = "cursor") val cursor: String,
    @field:Json(name = "has_more") val hasMore: Boolean,
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
