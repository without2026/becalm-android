package com.becalm.android.data.repository.internal

import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.CommitmentBatchItemDto
import com.becalm.android.data.remote.dto.CommitmentBatchPayloadDto
import com.becalm.android.data.remote.dto.CommitmentBatchResponseDto
import com.becalm.android.data.remote.dto.CommitmentDto
import com.becalm.android.data.remote.dto.FailedEventDto
import com.becalm.android.data.repository.CommitmentRepository

// ─── Commitment → Batch DTO mapping ──────────────────────────────────────────

/**
 * Client-side defence-in-depth cap mirroring api-contract.yml `max_batch_size: 100`.
 * Keep in sync with [com.becalm.android.worker.UploadWorker.BATCH_SIZE].
 */
internal const val MAX_BATCH_SIZE = 100

/**
 * Maps a [CommitmentEntity] to a [CommitmentBatchItemDto].
 *
 * The commitment's primary [CommitmentEntity.id] doubles as the `client_event_id` idempotency
 * key: it is already unique per user, the server already uses it as the row PK, and reusing
 * it guarantees that a resend after network failure never produces a duplicate row.
 *
 * Room-only tracking columns (`sync_status`, `commitment_state`) are intentionally NOT
 * serialized — the server-side `commitments` table does not carry the `commitment_state`
 * SP-36 lifecycle column, and `sync_status` is a local-only value per data-model.yml line 150.
 */
internal fun CommitmentEntity.toBatchItemDto(): CommitmentBatchItemDto =
    CommitmentBatchItemDto(
        clientEventId = id,
        commitment = CommitmentBatchPayloadDto(
            id = id,
            userId = userId,
            itemType = itemType,
            direction = direction,
            scheduleStatus = scheduleStatus,
            decisionStatus = decisionStatus,
            counterpartyRaw = counterpartyRaw,
            counterpartyRef = counterpartyRef,
            title = title,
            description = description,
            quote = quote,
            sourceEventTitle = sourceEventTitle,
            sourceEventOccurredAt = sourceEventOccurredAt,
            dueAt = dueAt,
            dueHint = dueHint,
            dueIsApproximate = dueIsApproximate,
            actionState = actionState,
            sourceType = sourceType,
            sourceRef = sourceRef,
            confidence = confidence,
            createdAt = createdAt,
            updatedAt = updatedAt,
            // v5 lifecycle fields — must round-trip on upload so local edits / disputes /
            // soft-deletes / supersede links are not silently lost when a commitment is
            // re-sent (e.g. an optimistic PATCH fell back to `sync_status='pending'` and
            // the next batch flushes the full row).
            lastEditedBy = lastEditedBy,
            lastEditedAt = lastEditedAt,
            quoteDisputed = quoteDisputed,
            quoteDisputedAt = quoteDisputedAt,
            deletedAt = deletedAt,
            supersedesCommitmentId = supersedesCommitmentId,
        ),
    )

/** Maps a wire [CommitmentBatchResponseDto] to the domain [CommitmentRepository.BatchResponse]. */
internal fun CommitmentBatchResponseDto.toDomain(): CommitmentRepository.BatchResponse =
    CommitmentRepository.BatchResponse(
        acknowledged = acknowledged,
        failed = failed.map { it.toDomain() },
    )

internal fun FailedEventDto.toDomain(): CommitmentRepository.FailedEvent =
    CommitmentRepository.FailedEvent(
        clientEventId = clientEventId,
        reason = reason,
        message = message,
        retryable = retryable,
    )

// ─── DTO → Entity mapping ─────────────────────────────────────────────────────

/**
 * Maps a wire [CommitmentDto] to a local [CommitmentEntity], merging the six v5
 * lifecycle columns (`last_edited_*`, `quote_disputed*`, `deleted_at`,
 * `supersedes_commitment_id`) against [existing] when provided so that stock Moshi
 * parsing — which cannot distinguish "server omitted the field" from "server
 * explicitly sent null/false" — does not silently erase local state.
 *
 * ### Merge semantics (per-field)
 * - `last_edited_by` / `last_edited_at` — nullable, set-once-then-mutable. Rule:
 *   if the DTO is null, fall back to [existing]. Otherwise trust the DTO. A null
 *   DTO value is assumed to mean "server omitted the field", because the append-
 *   only client-writable nature of these columns means the server never clears a
 *   once-set identifier back to null.
 * - `quote_disputed` — boolean, toggleable. Rule: trust the DTO when the paired
 *   `quote_disputed_at` timestamp is non-null (that combination proves the server
 *   has a first-class dispute view and can legitimately release the flag by
 *   sending `quote_disputed=false` while retaining the historical timestamp). If
 *   the DTO's `quote_disputed_at` is null, fall back to [existing] to cover both
 *   (a) a legacy backend that does not yet return the field and (b) a row that
 *   has genuinely never been disputed — both cases collapse to safe preservation.
 * - `quote_disputed_at` — monotonically-retained timestamp. Rule: DTO ?: existing.
 * - `deleted_at` — append-only tombstone (null → timestamp, never back per EDIT-006).
 *   Rule: DTO ?: existing. A local soft-delete that has not round-tripped survives
 *   the next refresh.
 * - `supersedes_commitment_id` — set once on EDIT-007 supersede. Rule: DTO ?: existing.
 *
 * Pass [existing] = null when the caller has not yet looked up a local row (e.g.
 * the refresh path's first insert). The merge then reduces to "accept the DTO
 * values as-is", which is correct because there is no local state to preserve.
 *
 * Non-lifecycle columns always take the wire value; the server is authoritative
 * for them.
 */
internal fun CommitmentDto.toEntity(
    userId: String,
    existing: CommitmentEntity? = null,
): CommitmentEntity =
    CommitmentEntity(
        id = id,
        userId = userId,
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        counterpartyRaw = counterpartyRaw,
        counterpartyRef = counterpartyRef,
        title = title,
        description = description,
        quote = quote,
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = dueAt,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = sourceRef,
        confidence = confidence,
        syncStatus = syncStatus ?: "synced",
        createdAt = createdAt,
        updatedAt = updatedAt,
        // Lifecycle merge — see function KDoc for per-field rationale.
        lastEditedBy = lastEditedBy ?: existing?.lastEditedBy,
        lastEditedAt = lastEditedAt ?: existing?.lastEditedAt,
        quoteDisputed = if (quoteDisputedAt != null) {
            // Server has a first-class dispute view: trust its boolean (including
            // release to false while keeping the historical timestamp).
            quoteDisputed
        } else {
            // Server omitted the timestamp: preserve whatever local knows. When
            // local has never disputed either, this collapses to `false` — no drift.
            existing?.quoteDisputed ?: false
        },
        quoteDisputedAt = quoteDisputedAt ?: existing?.quoteDisputedAt,
        deletedAt = deletedAt ?: existing?.deletedAt,
        supersedesCommitmentId = supersedesCommitmentId ?: existing?.supersedesCommitmentId,
    )
