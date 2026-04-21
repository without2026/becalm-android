package com.becalm.android.data.remote.dto

import com.becalm.android.core.util.KstInstant
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import kotlinx.datetime.Instant

/**
 * Represents a single commitment as returned by Railway.
 *
 * Used as items in:
 * - GET /v1/commitments → [PaginatedCommitmentsResponse.data]
 * - GET /v1/commitments/{id} → { data: CommitmentDto }
 * - PATCH /v1/commitments/{id} → { data: CommitmentDto }
 * - GET /v1/persons/{person_id}/commitments → [PersonCommitmentsResponse.data]
 *
 * Mirrors the `commitments` Supabase table (data-model.yml). The `sync_status`
 * column is included in GET responses per api-contract.yml comment ("includes sync_status").
 *
 * @property direction Valid values: "give" | "take"
 * @property actionState Valid values: "pending" | "reminded" | "followed_up" | "completed"
 * @property sourceType Valid values: see [SourceType] constants.
 * @property syncStatus Valid values (Room-side enum): "pending" | "synced" | "failed"
 */
@JsonClass(generateAdapter = true)
public data class CommitmentDto(
    /** Supabase-assigned UUID primary key. */
    @field:Json(name = "id") val id: String,

    /** Supabase auth.users UUID of the owning user. */
    @field:Json(name = "user_id") val userId: String,

    /**
     * Commitment direction from the authenticated user's perspective.
     * Valid values: "give" (user owes counterparty) | "take" (counterparty owes user).
     */
    @field:Json(name = "direction") val direction: String,

    /**
     * Raw uncanonized counterparty identifier as extracted from the source event.
     * May be a phone number, email address, or display name.
     */
    @field:Json(name = "counterparty_raw") val counterpartyRaw: String? = null,

    /**
     * Canonicalized counterparty identifier (same precedence rule as raw_ingestion_events).
     * Null when counterparty is not identifiable.
     */
    @field:Json(name = "person_ref") val personRef: String? = null,

    /** Short title of the commitment, as extracted by the LLM. */
    @field:Json(name = "title") val title: String,

    /** Optional longer description. */
    @field:Json(name = "description") val description: String? = null,

    /**
     * Verbatim text fragment from the source event from which this commitment was extracted.
     * Legally sensitive — never summarized or modified. Treated as evidentiary record.
     */
    @field:Json(name = "quote") val quote: String,

    /**
     * Denormalized event title from the source [RawIngestionEventDto].
     * For display in CommitmentCard. May be null if source had no title.
     */
    @field:Json(name = "source_event_title") val sourceEventTitle: String? = null,

    /**
     * Timestamp of the source event (not extraction time).
     * Required for attribution display in CommitmentCard.
     */
    @field:Json(name = "source_event_occurred_at") val sourceEventOccurredAt: Instant,

    /**
     * Optional deadline timestamp (ISO-8601 timestamptz). Null when no due date was
     * extracted or set. Replaces the pre-v4 `due_date` field; see data-model.yml:132-144.
     *
     * Wire format: ISO-8601 with explicit `+09:00` KST offset per
     * api-contract.yml:32. Storage is UTC; the [KstInstant] qualifier only
     * affects Moshi serialization. Parsing is tolerant (accepts `Z` or
     * `+09:00`) so server echoes in either form round-trip correctly.
     */
    @field:KstInstant @field:Json(name = "due_at") val dueAt: Instant? = null,

    /**
     * Optional verbatim due-date expression surfaced by the LLM (e.g. "다음주"). Null
     * when absent. See VOI-003.
     */
    @field:Json(name = "due_hint") val dueHint: String? = null,

    /**
     * True when [dueAt] was inferred from a fuzzy hint rather than an explicit calendar
     * reference. Default false.
     */
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean = false,

    /**
     * User's follow-through action state. Updated via PATCH /v1/commitments/{id}.
     * Valid values: "pending" | "reminded" | "followed_up" | "completed"
     */
    @field:Json(name = "action_state") val actionState: String,

    /**
     * Source type of the originating raw ingestion event.
     * Valid values: see [SourceType] constants.
     */
    @field:Json(name = "source_type") val sourceType: String,

    /** Source-system reference linking back to the originating raw event. */
    @field:Json(name = "source_ref") val sourceRef: String? = null,

    /**
     * LLM confidence score for this extraction, in [0.0, 1.0].
     * Higher is more confident.
     */
    @field:Json(name = "confidence") val confidence: Double,

    /**
     * Railway/Supabase sync status.
     * Valid values: "pending" | "synced" | "failed"
     * Included in GET responses per api-contract.yml.
     */
    @field:Json(name = "sync_status") val syncStatus: String? = null,

    /** Server-assigned creation timestamp. */
    @field:Json(name = "created_at") val createdAt: Instant,

    /** Server-assigned last-update timestamp. */
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

/**
 * Paginated list response for GET /v1/commitments and GET /v1/persons/{id}/commitments.
 *
 * Wire format: { data: Commitment[], cursor: string, has_more: boolean }
 *
 * Pass [cursor] as the `cursor` query parameter on the next request when [hasMore] is true.
 */
@JsonClass(generateAdapter = true)
public data class PaginatedCommitmentsResponse(
    @field:Json(name = "data") val data: List<CommitmentDto>,

    /**
     * Opaque pagination cursor. Pass as `cursor` query param on next request.
     * Value is undefined when [hasMore] is false; still included in response per contract.
     */
    @field:Json(name = "cursor") val cursor: String,

    /** True when additional pages exist beyond this response. */
    @field:Json(name = "has_more") val hasMore: Boolean,
)

/**
 * Wrapper for GET /v1/commitments/{id} and PATCH /v1/commitments/{id} single-item responses.
 *
 * Wire format: { data: Commitment }
 */
@JsonClass(generateAdapter = true)
public data class SingleCommitmentResponse(
    @field:Json(name = "data") val data: CommitmentDto,
)

/**
 * Request body for PATCH /v1/commitments/{id}.
 *
 * The contract defines exactly one updatable field: [actionState].
 * Due date updates are not declared in api-contract.yml for this endpoint.
 *
 * Valid [actionState] values: "pending" | "reminded" | "followed_up" | "completed"
 * Invalid values return HTTP 422.
 */
@JsonClass(generateAdapter = true)
public data class PatchCommitmentRequest(
    /**
     * Updated action state for this commitment.
     * Valid values: "pending" | "reminded" | "followed_up" | "completed"
     */
    @field:Json(name = "action_state") val actionState: String,
)
