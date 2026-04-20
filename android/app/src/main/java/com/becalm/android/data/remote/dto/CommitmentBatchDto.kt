package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Single item in a [CommitmentBatchRequestDto] for POST /v1/commitments:batch.
 *
 * Wire shape (api-contract.yml lines 185-208):
 * ```
 * {
 *   client_event_id: uuid,       // Idempotency key per item
 *   commitment: Commitment       // full Commitment payload per data-model.yml
 * }
 * ```
 *
 * The server deduplicates on `(user_id, client_event_id)`. Android reuses the commitment's
 * row-level `id` (Supabase-assigned UUID primary key) as the [clientEventId] because:
 * 1. The `id` is already unique per user + commitment.
 * 2. PATCH /v1/commitments/{id} already established this UUID as the stable identity.
 * Re-sending the same row after a transient failure MUST NOT create a duplicate — using the
 * primary key guarantees this without a separate idempotency column in Room.
 */
@JsonClass(generateAdapter = true)
public data class CommitmentBatchItemDto(
    /** Client-generated UUID v4 idempotency key; Android uses the commitment's primary id. */
    @field:Json(name = "client_event_id") val clientEventId: String,

    /** Full commitment payload mirroring the `Commitment` wire type. */
    @field:Json(name = "commitment") val commitment: CommitmentBatchPayloadDto,
)

/**
 * The embedded `commitment` payload inside a [CommitmentBatchItemDto].
 *
 * Mirrors the `Commitment` shared type referenced by api-contract.yml line 191-196 and the
 * `commitments` table columns in data-model.yml (lines 88-171). snake_case field names via
 * Moshi `@field:Json` so the wire format matches Railway's Pydantic schema exactly.
 *
 * Field ordering follows data-model.yml column order for reviewer diff legibility.
 */
@JsonClass(generateAdapter = true)
public data class CommitmentBatchPayloadDto(
    /** Supabase-assigned UUID primary key. */
    @field:Json(name = "id") val id: String,

    /** Supabase auth.users UUID of the owning user. Echoed in the body for server-side audit. */
    @field:Json(name = "user_id") val userId: String,

    /** "give" | "take" */
    @field:Json(name = "direction") val direction: String,

    /** Raw uncanonized counterparty identifier; may be phone / email / display name. */
    @field:Json(name = "counterparty_raw") val counterpartyRaw: String? = null,

    /** Canonicalized counterparty identifier; null when unidentifiable. */
    @field:Json(name = "person_ref") val personRef: String? = null,

    /** Short title as extracted by the LLM. */
    @field:Json(name = "title") val title: String,

    /** Optional longer description. */
    @field:Json(name = "description") val description: String? = null,

    /** Verbatim quote from the source event; never summarized or edited. */
    @field:Json(name = "quote") val quote: String,

    /** Denormalized event_title of the source raw event for display. */
    @field:Json(name = "source_event_title") val sourceEventTitle: String? = null,

    /** Timestamp of the source event (not extraction time). */
    @field:Json(name = "source_event_occurred_at") val sourceEventOccurredAt: Instant,

    /** Optional deadline date. */
    @field:Json(name = "due_date") val dueDate: LocalDate? = null,

    /** "pending" | "reminded" | "followed_up" | "completed" */
    @field:Json(name = "action_state") val actionState: String,

    /** Source type of the originating raw event (see [SourceType] constants). */
    @field:Json(name = "source_type") val sourceType: String,

    /** Source-system reference linking back to the originating raw event. */
    @field:Json(name = "source_ref") val sourceRef: String? = null,

    /** LLM confidence score in [0.0, 1.0]. */
    @field:Json(name = "confidence") val confidence: Double,

    /** Server-assigned creation timestamp. Included for audit — server may override. */
    @field:Json(name = "created_at") val createdAt: Instant,

    /** Server-assigned last-update timestamp. Included for audit — server may override. */
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

/**
 * Request body for POST /v1/commitments:batch.
 *
 * Constraints (api-contract.yml):
 * - max 100 items per batch (HTTP 413 if exceeded)
 * - max 1 MiB body (HTTP 413 if exceeded)
 *
 * Spec refs: CMT-005, CMT-006, CMT-007, SYNC-001.
 */
@JsonClass(generateAdapter = true)
public data class CommitmentBatchRequestDto(
    @field:Json(name = "commitments") val commitments: List<CommitmentBatchItemDto>,
)

/**
 * Response body for a successful POST /v1/commitments:batch (HTTP 200).
 *
 * Wire format mirrors the raw_ingestion_events:batch response (api-contract.yml line 200):
 *   { acknowledged: int, failed: FailedEvent[] }
 *
 * Reuses [FailedEventDto] from `IngestionDtos.kt` — the wire type is the shared `FailedEvent`
 * declared at the top of api-contract.yml, so duplicating it would create drift risk.
 */
@JsonClass(generateAdapter = true)
public data class CommitmentBatchResponseDto(
    /** Count of commitments successfully inserted or deduplicated (idempotent resend). */
    @field:Json(name = "acknowledged") val acknowledged: Int,

    /** Commitments that could not be processed; may be empty. */
    @field:Json(name = "failed") val failed: List<FailedEventDto>,
)
