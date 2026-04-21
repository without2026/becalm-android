package com.becalm.android.worker

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.repository.RawIngestionRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Raw ingestion flush path of [UploadWorker].
 *
 * Drains all `pending` raw ingestion rows for the signed-in user in [UploadWorker.BATCH_SIZE]-item
 * pages, partitions each batch response, and translates transport errors into [FlushOutcome]
 * decisions. Every call site and log string is byte-identical with the original inlined
 * implementation; tests exercise the worker's `doWork()` only, so this extraction is invisible
 * to the existing `UploadWorkerTest`.
 *
 * Constructed by [UploadWorker] from its own collaborators; not Hilt-injected.
 */
internal class RawEventUploader(
    private val rawIngestionRepository: RawIngestionRepository,
    private val logger: Logger,
) {

    /**
     * Drains all `pending` raw ingestion rows for [userId] in [UploadWorker.BATCH_SIZE]-item pages.
     * Marks each batch synced or failed before fetching the next page.
     *
     * @return [FlushOutcome] indicating whether to continue, retry, or fail permanently.
     */
    suspend fun flushRawIngestion(userId: String, attempt: Int): FlushOutcome {
        var totalUploaded = 0

        while (true) {
            val pending = rawIngestionRepository.findPendingSync(userId, UploadWorker.BATCH_SIZE)
            if (pending.isEmpty()) break

            logger.d(TAG, "rawIngestion batch count=${pending.size} attempt=$attempt")

            when (val uploadResult = rawIngestionRepository.uploadBatch(pending)) {
                is BecalmResult.Success -> {
                    val ack = partitionAndAckBatch(pending, uploadResult.value, Clock.System.now())
                    totalUploaded += ack.syncedCount
                    logger.d(
                        TAG,
                        "rawIngestion batch acked batchSize=${pending.size} " +
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
                            "rawIngestion batch all retryable — returning RetryNeeded to let WorkManager retry",
                        )
                        return FlushOutcome.RetryNeeded
                    }
                }

                is BecalmResult.Failure -> {
                    // Mark every row in this batch as failed so retry_count is visible.
                    val now = Clock.System.now()
                    pending.forEach { rawIngestionRepository.markFailed(it.id, now) }

                    return mapErrorToOutcome(
                        logger = logger,
                        error = uploadResult.error,
                        attempt = attempt,
                        domain = "rawIngestion",
                    )
                }
            }
        }

        return FlushOutcome.Success(totalUploaded)
    }

    /**
     * 배치 응답의 부분 실패(response.failed)를 3-way로 분류하고 DB 반영까지 한 번에 끝낸다.
     * `failure == null` → synced IDs에 적재, `retryable=false` → markFailed(quarantine),
     * `retryable=true` → pending 유지(다음 run 재시도). synced가 1건 이상이면 markSynced 일괄 호출.
     *
     * 원본 인라인 로직과 동일하게 동작한다 — 배치 순회 순서, markFailed per-event 호출, 그리고
     * syncedIds 비어있을 때 markSynced를 생략하는 가드를 그대로 유지한다.
     */
    private suspend fun partitionAndAckBatch(
        pending: List<RawIngestionEventEntity>,
        response: BatchUploadResponse,
        now: Instant,
    ): BatchAckResult {
        val failedByClientId = response.failed.associateBy { it.clientEventId }
        val syncedIds = mutableListOf<String>()
        var quarantinedCount = 0
        var retryableFailedCount = 0
        for (event in pending) {
            val failure = failedByClientId[event.clientEventId]
            when {
                failure == null -> syncedIds.add(event.id)
                !failure.retryable -> {
                    // Persist quarantine: server has deterministically rejected this event.
                    // TODO: Add a dedicated `updateQuarantineStatus(id, reason)` DAO method
                    //   so the quarantine reason code from the server is preserved.
                    //   Today we reuse markFailed which only records sync_status="failed".
                    rawIngestionRepository.markFailed(event.id, now)
                    quarantinedCount++
                }
                else -> {
                    // Retryable: leave pending (do not markSynced) so next run re-uploads.
                    retryableFailedCount++
                }
            }
        }
        if (syncedIds.isNotEmpty()) {
            rawIngestionRepository.markSynced(syncedIds)
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

// ── Shared cross-uploader types and helpers ──────────────────────────────────
// Kept top-level + `internal` so [CommitmentUploader] can reuse them without a third file.

/** Aggregate counts returned by partition-and-ack helpers for logging + loop-safety guard. */
internal data class BatchAckResult(
    val syncedCount: Int,
    val quarantinedCount: Int,
    val retryableFailedCount: Int,
)

internal sealed interface FlushOutcome {
    data class Success(val count: Int) : FlushOutcome

    /**
     * Transport-level retry produced by [mapErrorToOutcome] (network / 5xx / 429).
     * Carries a pre-built [Result.retry] with diagnostic output data so [UploadWorker]
     * can forward it verbatim.
     */
    data class TransportRetry(val result: Result) : FlushOutcome

    /**
     * The server accepted zero rows on this page and every failure is `retryable: true`.
     * WorkManager must retry (not succeed) so backoff fires; otherwise the worker is stuck
     * reporting success while pending rows accumulate.
     *
     * No [Result] is carried: the worker decides how to surface it (Result.retry +
     * recordSyncError with a "batch_all_retryable" reason) so the decision lives in one
     * place instead of two uploaders.
     */
    data object RetryNeeded : FlushOutcome

    data class PermanentFailure(val result: Result) : FlushOutcome
}

/**
 * Maps a [BecalmError] to the appropriate [FlushOutcome], enforcing the
 * [UploadBackoff.MAX_ATTEMPTS] ceiling for retryable errors.
 *
 * Rules:
 * - [BecalmError.RateLimited] → retry with delay hint in output (SYNC-005).
 * - [BecalmError.Unauthorized] → permanent failure (re-auth required).
 * - [BecalmError.Network] / [BecalmError.ServerError] → retry up to [UploadBackoff.MAX_ATTEMPTS].
 * - Everything else → permanent failure (Io/Validation/Unknown).
 */
internal fun mapErrorToOutcome(
    logger: Logger,
    error: BecalmError,
    attempt: Int,
    domain: String,
): FlushOutcome {
    return when (error) {
        is BecalmError.RateLimited -> {
            if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
                logger.w(SHARED_TAG, "$domain rateLimit maxAttempts=$attempt — permanent failure")
                return FlushOutcome.PermanentFailure(
                    Result.failure(retryOutput("rate_limited_max_attempts", attempt).build()),
                )
            }
            val delaySec = UploadBackoff.nextDelaySeconds(attempt + 1, error.retryAfterSeconds)
            logger.w(
                SHARED_TAG,
                "$domain 429 retryAfterSec=${error.retryAfterSeconds} computedDelaySec=$delaySec attempt=$attempt",
            )
            // Diagnostic output data ("rate_limited" reason, computed delaySec) is logged
            // above; WorkManager's Result.retry() does not carry output data per the
            // ListenableWorker.Result API. UploadBackoff.nextDelaySeconds is surfaced via
            // the log line so operators can correlate a retry with the computed backoff.
            FlushOutcome.TransportRetry(Result.retry())
        }

        is BecalmError.Unauthorized -> {
            logger.e(SHARED_TAG, "$domain 401 unauthorized — permanent failure attempt=$attempt")
            FlushOutcome.PermanentFailure(
                Result.failure(retryOutput("unauthorized", attempt).build()),
            )
        }

        is BecalmError.Network -> logAndRetryable(
            logger = logger,
            attempt = attempt,
            domain = domain,
            noun = "network error",
            code = error.code,
            retryReason = "network_error",
            permanentReason = "network_max_attempts",
        )

        is BecalmError.ServerError -> logAndRetryable(
            logger = logger,
            attempt = attempt,
            domain = domain,
            noun = "server error",
            code = error.code,
            retryReason = "server_error",
            permanentReason = "server_error_max_attempts",
        )

        else -> {
            logger.e(SHARED_TAG, "$domain unrecoverable error ${error::class.simpleName} attempt=$attempt")
            FlushOutcome.PermanentFailure(
                Result.failure(retryOutput("unrecoverable_${error::class.simpleName}", attempt).build()),
            )
        }
    }
}

/**
 * Formats the shared `Network` / `ServerError` log lines and returns the matching
 * Retry-vs-PermanentFailure [FlushOutcome] in one place.
 *
 * Unifies the `attempt >= UploadBackoff.MAX_ATTEMPTS` guard that was previously duplicated
 * across the Network/ServerError branches. [noun] is the human-facing phrase
 * (`"network error"` / `"server error"`) that made the former two branches textually
 * distinct; [retryReason] / [permanentReason] flow straight into [retryOutput] keys.
 * The emitted log strings are byte-identical with the former inlined
 * `is BecalmError.Network` / `is BecalmError.ServerError` arms.
 */
private fun logAndRetryable(
    logger: Logger,
    attempt: Int,
    domain: String,
    noun: String,
    code: Int,
    retryReason: String,
    permanentReason: String,
): FlushOutcome {
    if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
        logger.e(SHARED_TAG, "$domain $noun maxAttempts=$attempt — permanent failure code=$code")
        return FlushOutcome.PermanentFailure(Result.failure(retryOutput(permanentReason, attempt).build()))
    }
    // Retry reason / attempt counter are captured in the log line above; WorkManager's
    // Result.retry() does not accept output data, so retain the diagnostic context in the
    // log line rather than in output Data.
    logger.w(SHARED_TAG, "$domain $noun code=$code attempt=$attempt reason=$retryReason — retry")
    return FlushOutcome.TransportRetry(Result.retry())
}

internal fun retryOutput(reason: String, attempt: Int): Data.Builder =
    Data.Builder()
        .putString(UploadWorker.OUTPUT_KEY_REASON, reason)
        .putInt(UploadWorker.OUTPUT_KEY_ATTEMPT, attempt + 1)

private const val SHARED_TAG = "UploadWorker"
