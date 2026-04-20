package com.becalm.android.worker

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.repository.CommitmentRepository

/**
 * Commitment flush path of [UploadWorker].
 *
 * Drains all `pending` commitment rows for the signed-in user in [UploadWorker.BATCH_SIZE]-item
 * pages, partitions each batch response, and translates transport errors into [FlushOutcome]
 * decisions via the shared [mapErrorToOutcome] helper (defined alongside [RawEventUploader]).
 * Body is byte-identical with the original inlined implementation; tests exercise
 * `UploadWorker.doWork()` only.
 *
 * Constructed by [UploadWorker] from its own collaborators; not Hilt-injected.
 */
internal class CommitmentUploader(
    private val commitmentRepository: CommitmentRepository,
    private val logger: Logger,
) {

    /**
     * Drains all `pending` commitment rows for [userId] in [UploadWorker.BATCH_SIZE]-item pages.
     *
     * For each page:
     * 1. Fetch up to [UploadWorker.BATCH_SIZE] rows via [CommitmentRepository.findPendingSync]
     *    (respects api-contract.yml `max_batch_size: 100`).
     * 2. POST them via [CommitmentRepository.uploadBatch] → `/v1/commitments:batch`.
     * 3. Partition the response: ack → [CommitmentRepository.markSynced];
     *    `retryable=true` failures → leave `pending` (next run retries);
     *    `retryable=false` failures → quarantine via [CommitmentRepository.markFailed].
     * 4. On network / 5xx error → leave all rows `pending`, surface the [FlushOutcome]
     *    so WorkManager reschedules the worker (SYNC-001 + SYNC-003 semantics).
     *
     * The batch size cap (100) covers api-contract.yml `max_batch_size`; a 1 MiB body cap
     * is enforced client-side by the ceiling + per-row reality (commitment payload is
     * dominated by quote + title which rarely exceed a few KiB, so 100 rows stays well
     * below 1 MiB in practice; the repository's HTTP 413 mapping is the safety net).
     *
     * @return [FlushOutcome] indicating whether to continue, retry, or fail permanently.
     */
    suspend fun flushCommitments(userId: String, attempt: Int): FlushOutcome {
        var totalUploaded = 0

        while (true) {
            val pending = commitmentRepository.findPendingSync(userId, UploadWorker.BATCH_SIZE)
            if (pending.isEmpty()) break

            logger.d(TAG, "commitment batch count=${pending.size} attempt=$attempt")

            when (val uploadResult = commitmentRepository.uploadBatch(pending)) {
                is BecalmResult.Success -> {
                    val ack = partitionAndAckCommitmentBatch(pending, uploadResult.value)
                    totalUploaded += ack.syncedCount
                    logger.d(
                        TAG,
                        "commitment batch acked batchSize=${pending.size} " +
                            "synced=${ack.syncedCount} quarantined=${ack.quarantinedCount} " +
                            "retryable=${ack.retryableFailedCount}",
                    )

                    // Guard against infinite loop: if the whole page came back as retryable
                    // failures (none synced, none quarantined), findPendingSync would return
                    // the same rows on the next iteration. Return RetryNeeded so UploadWorker
                    // surfaces Result.retry() (not success) — pending rows stay pending and
                    // WorkManager schedules the next attempt with backoff.
                    if (ack.syncedCount == 0 && ack.quarantinedCount == 0 && ack.retryableFailedCount > 0) {
                        logger.w(
                            TAG,
                            "commitment batch all retryable — returning RetryNeeded to let WorkManager retry",
                        )
                        return FlushOutcome.RetryNeeded
                    }
                }

                is BecalmResult.Failure -> {
                    // Transport-level failure: leave all rows as 'pending' so the next
                    // worker run retries them. Map the error to a retry vs permanent
                    // decision via the shared helper.
                    return mapErrorToOutcome(
                        logger = logger,
                        error = uploadResult.error,
                        attempt = attempt,
                        domain = "commitment",
                    )
                }
            }
        }

        return FlushOutcome.Success(totalUploaded)
    }

    /**
     * Partitions a commitment batch response into synced / quarantined / retryable buckets
     * and persists the outcome.
     *
     * Parallel to [RawEventUploader]'s `partitionAndAckBatch` but operates on domain
     * [CommitmentRepository.BatchResponse] + [CommitmentEntity] types instead of DTOs.
     */
    private suspend fun partitionAndAckCommitmentBatch(
        pending: List<CommitmentEntity>,
        response: CommitmentRepository.BatchResponse,
    ): BatchAckResult {
        val failedByClientId = response.failed.associateBy { it.clientEventId }
        val syncedIds = mutableListOf<String>()
        var quarantinedCount = 0
        var retryableFailedCount = 0
        for (entity in pending) {
            // Commitments reuse their primary id as client_event_id per CommitmentRepositoryImpl.toBatchItemDto.
            val failure = failedByClientId[entity.id]
            when {
                failure == null -> syncedIds.add(entity.id)
                !failure.retryable -> {
                    // Server has deterministically rejected this row (e.g. schema_invalid).
                    // Park as 'failed' so findPendingSync stops picking it up.
                    commitmentRepository.markFailed(entity.id)
                    quarantinedCount++
                }
                else -> {
                    // Retryable: leave as 'pending' (do not markSynced) so next run re-uploads.
                    retryableFailedCount++
                }
            }
        }
        if (syncedIds.isNotEmpty()) {
            commitmentRepository.markSynced(syncedIds)
        }
        return BatchAckResult(
            syncedCount = syncedIds.size,
            quarantinedCount = quarantinedCount,
            retryableFailedCount = retryableFailedCount,
        )
    }

    private companion object {
        private const val TAG = "UploadWorker"
    }
}
