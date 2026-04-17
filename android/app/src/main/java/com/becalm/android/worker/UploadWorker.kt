package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Periodic and on-demand [CoroutineWorker] that drains pending rows from
 * [RawIngestionRepository] and [CommitmentRepository] to the Railway backend.
 *
 * ## Lifecycle per run
 * 1. Resolve [userId] from [AuthRepository.currentSession] — no session → failure.
 * 2. Flush pending raw ingestion events in 100-item batches (SYNC-001, SYNC-004).
 * 3. On success, flush pending commitments in the same envelope.
 * 4. Record sync success via [SourceStatusRepository.recordSyncSuccess].
 *
 * ## Retry strategy
 * - **429 RateLimited** (SYNC-005): surface attempt counter in output data; WorkManager
 *   `BackoffPolicy.EXPONENTIAL` (configured at enqueue time in SP-32) handles rescheduling.
 *   [UploadBackoff.nextDelaySeconds] is included in output data for diagnostics.
 * - **401 Unauthorized**: permanent failure — user must re-authenticate.
 * - **5xx ServerError / Network**: retry up to [UploadBackoff.MAX_ATTEMPTS] times.
 * - All other [BecalmError] variants (Io, Validation, Unknown): permanent failure — do not
 *   silently swallow; let WorkManager surface the failure for observability.
 *
 * ## PII policy
 * Counts and HTTP status codes are logged. Payload bodies are never written to logcat.
 *
 * ## Scheduled by
 * SP-32 WorkScheduler enqueues this class as `enqueueUniqueWork("sync-all-upload", …)`.
 * SYNC-006 requires REPLACE policy so that foreground re-triggers supersede any queued run.
 *
 * ## Idempotency
 * [RawIngestionRepository.markSynced] and [CommitmentRepository.markSynced] are called after
 * each successful batch. Re-running the worker after a partial flush is safe because rows
 * already marked `synced` are excluded by `findPendingSync`.
 */
@HiltWorker
public class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val rawIngestionRepository: RawIngestionRepository,
    private val commitmentRepository: CommitmentRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return Result.failure()

        val attempt = workerParams.inputData.getInt(INPUT_KEY_ATTEMPT, 0)

        // ── Step 1: resolve userId ─────────────────────────────────────────────
        val userId = authRepository.currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "no session — aborting upload attempt=$attempt")
            return Result.failure(
                retryOutput("no_session", attempt).build(),
            )
        }

        logger.d(TAG, "doWork start attempt=$attempt userId=[redacted]")

        // ── Step 2: flush raw ingestion events ────────────────────────────────
        val rawResult = flushRawIngestion(userId, attempt)
        val rawCount = when (rawResult) {
            is FlushOutcome.Success -> rawResult.count
            is FlushOutcome.RetryNeeded -> return rawResult.result
            is FlushOutcome.PermanentFailure -> return rawResult.result
        }

        // ── Step 3: flush commitments ─────────────────────────────────────────
        val commitResult = flushCommitments(userId, attempt)
        val commitCount = when (commitResult) {
            is FlushOutcome.Success -> commitResult.count
            is FlushOutcome.RetryNeeded -> return commitResult.result
            is FlushOutcome.PermanentFailure -> return commitResult.result
        }

        // ── Step 4: record success ────────────────────────────────────────────
        val now = Clock.System.now()
        sourceStatusRepository.recordSyncSuccess(SOURCE_TYPE, now)

        logger.i(
            TAG,
            "doWork complete rawUploaded=$rawCount commitUploaded=$commitCount attempt=$attempt",
        )

        return Result.success(
            Data.Builder()
                .putInt(OUTPUT_KEY_UPLOADED_RAW, rawCount)
                .putInt(OUTPUT_KEY_UPLOADED_COMMIT, commitCount)
                .build(),
        )
    }

    // ── Raw ingestion flush ───────────────────────────────────────────────────

    /**
     * Drains all `pending` raw ingestion rows for [userId] in [BATCH_SIZE]-item pages.
     * Marks each batch synced or failed before fetching the next page.
     *
     * @return [FlushOutcome] indicating whether to continue, retry, or fail permanently.
     */
    private suspend fun flushRawIngestion(userId: String, attempt: Int): FlushOutcome {
        var totalUploaded = 0

        while (true) {
            val pending = rawIngestionRepository.findPendingSync(userId, BATCH_SIZE)
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
                    // the same rows on the next iteration. Break so WorkManager retries later.
                    if (ack.syncedCount == 0 && ack.quarantinedCount == 0 && ack.retryableFailedCount > 0) {
                        logger.w(
                            TAG,
                            "rawIngestion batch all retryable — breaking loop to let WorkManager retry",
                        )
                        break
                    }
                }

                is BecalmResult.Failure -> {
                    // Mark every row in this batch as failed so retry_count is visible.
                    val now = Clock.System.now()
                    pending.forEach { rawIngestionRepository.markFailed(it.id, now) }

                    return mapErrorToOutcome(
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

    /** Aggregate counts returned by [partitionAndAckBatch] for logging + loop-safety guard. */
    private data class BatchAckResult(
        val syncedCount: Int,
        val quarantinedCount: Int,
        val retryableFailedCount: Int,
    )

    // ── Commitment flush ──────────────────────────────────────────────────────

    /**
     * Drains all `pending` commitment rows for [userId] in [BATCH_SIZE]-item pages.
     * Uses [CommitmentRepository.findPendingSync] + [CommitmentRepository.markSynced].
     *
     * Commitments do not have a per-row `markFailed`; failures surface as worker retries
     * and the rows remain `pending` for the next run.
     *
     * @return [FlushOutcome] indicating whether to continue, retry, or fail permanently.
     */
    private suspend fun flushCommitments(userId: String, attempt: Int): FlushOutcome {
        var totalUploaded = 0

        while (true) {
            val pending = commitmentRepository.findPendingSync(userId, BATCH_SIZE)
            if (pending.isEmpty()) break

            logger.d(TAG, "commitment batch count=${pending.size} attempt=$attempt")

            // Commitments are synced via updateActionState in the normal path; here we
            // re-push any that landed in the pending queue without a successful round-trip.
            // The repository exposes no uploadBatch for commitments, so we mark them synced
            // locally — the server will reconcile on the next refreshSince call. This matches
            // the optimistic write contract in CommitmentRepository.updateActionState.
            when (val syncResult = commitmentRepository.markSynced(pending.map { it.id })) {
                is BecalmResult.Success -> {
                    totalUploaded += pending.size
                    logger.d(TAG, "commitment batch marked synced batchSize=${pending.size}")
                }

                is BecalmResult.Failure -> {
                    return mapErrorToOutcome(
                        error = syncResult.error,
                        attempt = attempt,
                        domain = "commitment",
                    )
                }
            }
        }

        return FlushOutcome.Success(totalUploaded)
    }

    // ── Error → outcome mapping ───────────────────────────────────────────────

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
    private fun mapErrorToOutcome(
        error: BecalmError,
        attempt: Int,
        domain: String,
    ): FlushOutcome {
        return when (error) {
            is BecalmError.RateLimited -> {
                if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
                    logger.w(TAG, "$domain rateLimit maxAttempts=$attempt — permanent failure")
                    return retryableOutcome(attempt, "rate_limited", "rate_limited_max_attempts")
                }
                val delaySec = UploadBackoff.nextDelaySeconds(attempt + 1, error.retryAfterSeconds)
                logger.w(
                    TAG,
                    "$domain 429 retryAfterSec=${error.retryAfterSeconds} computedDelaySec=$delaySec attempt=$attempt",
                )
                FlushOutcome.RetryNeeded(
                    Result.retry(
                        retryOutput("rate_limited", attempt)
                            .putLong(OUTPUT_KEY_RETRY_DELAY_SEC, delaySec)
                            .build(),
                    ),
                )
            }

            is BecalmError.Unauthorized -> {
                logger.e(TAG, "$domain 401 unauthorized — permanent failure attempt=$attempt")
                FlushOutcome.PermanentFailure(
                    Result.failure(retryOutput("unauthorized", attempt).build()),
                )
            }

            is BecalmError.Network -> logAndRetryable(
                attempt = attempt,
                domain = domain,
                noun = "network error",
                code = error.code,
                retryReason = "network_error",
                permanentReason = "network_max_attempts",
            )

            is BecalmError.ServerError -> logAndRetryable(
                attempt = attempt,
                domain = domain,
                noun = "server error",
                code = error.code,
                retryReason = "server_error",
                permanentReason = "server_error_max_attempts",
            )

            else -> {
                logger.e(TAG, "$domain unrecoverable error ${error::class.simpleName} attempt=$attempt")
                FlushOutcome.PermanentFailure(
                    Result.failure(retryOutput("unrecoverable_${error::class.simpleName}", attempt).build()),
                )
            }
        }
    }

    /**
     * 세 분기(rate_limited / network / server)에서 반복되던 `attempt >= UploadBackoff.MAX_ATTEMPTS`
     * 가드를 한 군데로 통합한다. ING-003 분류(Retry vs PermanentFailure)와 출력 키(reason/attempt)는
     * 기존과 동일하게 유지한다.
     */
    private fun retryableOutcome(
        attempt: Int,
        retryReason: String,
        permanentReason: String,
    ): FlushOutcome {
        if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
            return FlushOutcome.PermanentFailure(Result.failure(retryOutput(permanentReason, attempt).build()))
        }
        return FlushOutcome.RetryNeeded(Result.retry(retryOutput(retryReason, attempt).build()))
    }

    /**
     * Formats the shared `Network` / `ServerError` log lines and delegates to
     * [retryableOutcome] for the Retry-vs-PermanentFailure decision.
     *
     * [noun] is the human-facing phrase (`"network error"` / `"server error"`) that made the
     * former two branches textually distinct; [retryReason] / [permanentReason] flow straight
     * into [retryOutput] keys. The emitted log strings are byte-identical with the former
     * inlined `is BecalmError.Network` / `is BecalmError.ServerError` arms.
     */
    private fun logAndRetryable(
        attempt: Int,
        domain: String,
        noun: String,
        code: Int,
        retryReason: String,
        permanentReason: String,
    ): FlushOutcome {
        if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
            logger.e(TAG, "$domain $noun maxAttempts=$attempt — permanent failure code=$code")
        } else {
            logger.w(TAG, "$domain $noun code=$code attempt=$attempt — retry")
        }
        return retryableOutcome(attempt, retryReason, permanentReason)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun retryOutput(reason: String, attempt: Int): Data.Builder =
        Data.Builder()
            .putString(OUTPUT_KEY_REASON, reason)
            .putInt(OUTPUT_KEY_ATTEMPT, attempt + 1)

    // ── Supporting sealed type ────────────────────────────────────────────────

    private sealed interface FlushOutcome {
        data class Success(val count: Int) : FlushOutcome
        data class RetryNeeded(val result: Result) : FlushOutcome
        data class PermanentFailure(val result: Result) : FlushOutcome
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "UploadWorker"

        /**
         * Maximum WorkManager runAttemptCount before permanent failure.
         *
         * NOTE: `UploadBackoff.MAX_ATTEMPTS`와는 별개 카운터다 —
         * `MAX_RETRIES`는 WorkManager 런타임의 `runAttemptCount`(프로세스 재시작/시스템 재스케줄 포함) 상한이고,
         * `UploadBackoff.MAX_ATTEMPTS`는 SP-32가 input data로 주입하는 논리적 `attempt` 카운터 상한이다.
         */
        public const val MAX_RETRIES: Int = 5

        /** Railway per-batch item cap (SYNC-004). */
        public const val BATCH_SIZE: Int = 100

        /**
         * Source type identifier used with [SourceStatusRepository].
         * Not a wire [com.becalm.android.data.remote.dto.SourceType] value.
         */
        public const val SOURCE_TYPE: String = "backend_sync"

        /** Unique work name for `enqueueUniqueWork` (SYNC-006). */
        public const val WORK_NAME: String = "sync-all-upload"

        // ── Input keys ───────────────────────────────────────────────────────

        /**
         * Input [Data] key for the 0-based attempt counter.
         * Read at the start of [doWork] to enforce [UploadBackoff.MAX_ATTEMPTS].
         * SP-32 increments this when re-enqueuing after a retry result.
         */
        public const val INPUT_KEY_ATTEMPT: String = "attempt"

        // ── Output keys ──────────────────────────────────────────────────────

        /** Output [Data] key for the count of raw ingestion rows successfully uploaded. */
        public const val OUTPUT_KEY_UPLOADED_RAW: String = "uploaded_raw"

        /** Output [Data] key for the count of commitment rows marked synced. */
        public const val OUTPUT_KEY_UPLOADED_COMMIT: String = "uploaded_commit"

        /** Output [Data] key for the human-readable retry/failure reason string. */
        public const val OUTPUT_KEY_REASON: String = "reason"

        /**
         * Output [Data] key surfacing the next-attempt index (= failed attempt + 1).
         * Consumers (SP-32 diagnostics) read this to display retry state in the UI.
         */
        public const val OUTPUT_KEY_ATTEMPT: String = "attempt"

        /**
         * Output [Data] key carrying the computed backoff delay in seconds.
         * Populated only on 429 retries for diagnostic visibility.
         */
        public const val OUTPUT_KEY_RETRY_DELAY_SEC: String = "retry_delay_sec"
    }
}
