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
            direction = direction,
            counterpartyRaw = counterpartyRaw,
            personRef = personRef,
            title = title,
            description = description,
            quote = quote,
            sourceEventTitle = sourceEventTitle,
            sourceEventOccurredAt = sourceEventOccurredAt,
            dueDate = dueDate,
            actionState = actionState,
            sourceType = sourceType,
            sourceRef = sourceRef,
            confidence = confidence,
            createdAt = createdAt,
            updatedAt = updatedAt,
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

internal fun CommitmentDto.toEntity(userId: String): CommitmentEntity =
    CommitmentEntity(
        id = id,
        userId = userId,
        direction = direction,
        counterpartyRaw = counterpartyRaw,
        personRef = personRef,
        title = title,
        description = description,
        quote = quote,
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueDate = dueDate,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = sourceRef,
        confidence = confidence,
        syncStatus = syncStatus ?: "synced",
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
