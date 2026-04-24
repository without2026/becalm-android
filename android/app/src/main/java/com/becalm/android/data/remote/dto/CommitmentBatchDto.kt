package com.becalm.android.data.remote.dto

import com.becalm.android.core.util.KstInstant
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

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

    /** "action" | "schedule" | "decision" */
    @field:Json(name = "item_type") val itemType: String = "action",

    /** "give" | "take" for action rows, null otherwise. */
    @field:Json(name = "direction") val direction: String? = null,

    /** Schedule subtype for schedule rows, null otherwise. */
    @field:Json(name = "schedule_status") val scheduleStatus: String? = null,

    /** Decision subtype for decision rows, null otherwise. */
    @field:Json(name = "decision_status") val decisionStatus: String? = null,

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

    /**
     * Optional deadline timestamp (ISO-8601 timestamptz). Replaces the pre-v4 `due_date`
     * field; see data-model.yml:132-144.
     *
     * Wire format: ISO-8601 with explicit `+09:00` KST offset per
     * api-contract.yml:32. Internal storage remains UTC; the [KstInstant]
     * qualifier only alters the Moshi serialization path for this field.
     * Parsing is tolerant (accepts `Z` or `+09:00`).
     */
    @field:KstInstant @field:Json(name = "due_at") val dueAt: Instant? = null,

    /** Optional verbatim due-date expression surfaced by the LLM (VOI-003). */
    @field:Json(name = "due_hint") val dueHint: String? = null,

    /** True when [dueAt] was inferred from a fuzzy hint. Default false. */
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean = false,

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

    // ── v5 lifecycle fields (EDIT-001..008 / MAN-001..006) ──────────────────────
    //
    // These mirror the six new `commitments` columns added by Room 4→5 / Supabase
    // migration 011 (`.spec/contracts/data-model.yml:188-210`). They are sent on upload
    // so that local user state (edits, disputes, soft-deletes, supersede links) round-
    // trips to the server. `= null` / `= false` defaults keep older clients forward-
    // compatible on the response side; on the request side the server's Pydantic model
    // is expected to accept these fields and default-tolerate them until the full
    // server-side lifecycle endpoint lands.
    //
    // Plan: docs/plans/db-commitment-edit-delete-dispute-supersede.md §5.1 (6).

    /** Supabase auth.users UUID of the most recent editor. Null for unedited rows. */
    @field:Json(name = "last_edited_by") val lastEditedBy: String? = null,

    /** Timestamp of the most recent edit. Paired with [lastEditedBy]. */
    @field:Json(name = "last_edited_at") val lastEditedAt: Instant? = null,

    /** True when the user has flagged [quote] as misquoted (EDIT-005). */
    @field:Json(name = "quote_disputed") val quoteDisputed: Boolean = false,

    /** Timestamp when the dispute was first raised. Null until [quoteDisputed] flips. */
    @field:Json(name = "quote_disputed_at") val quoteDisputedAt: Instant? = null,

    /** Soft-delete marker (EDIT-006). Null = live row. Non-null = tombstoned. */
    @field:Json(name = "deleted_at") val deletedAt: Instant? = null,

    /** Supersede lineage FK (EDIT-007). Points at the prior commitment this row replaces. */
    @field:Json(name = "supersedes_commitment_id") val supersedesCommitmentId: String? = null,
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
