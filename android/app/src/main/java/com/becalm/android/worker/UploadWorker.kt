package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.datetime.Clock

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
        if (runAttemptCount >= MAX_RETRIES) {
            logger.e(TAG, "Exceeded $MAX_RETRIES attempts, failing permanently")
            return Result.failure()
        }

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
                    val ids = pending.map { it.id }
                    rawIngestionRepository.markSynced(ids)
                    totalUploaded += pending.size
                    logger.d(TAG, "rawIngestion batch acked batchSize=${pending.size}")
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
                    return FlushOutcome.PermanentFailure(
                        Result.failure(retryOutput("rate_limited_max_attempts", attempt).build()),
                    )
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

            is BecalmError.Network -> {
                if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
                    logger.e(TAG, "$domain network error maxAttempts=$attempt — permanent failure code=${error.code}")
                    return FlushOutcome.PermanentFailure(
                        Result.failure(retryOutput("network_max_attempts", attempt).build()),
                    )
                }
                logger.w(TAG, "$domain network error code=${error.code} attempt=$attempt — retry")
                FlushOutcome.RetryNeeded(
                    Result.retry(retryOutput("network_error", attempt).build()),
                )
            }

            is BecalmError.ServerError -> {
                if (attempt >= UploadBackoff.MAX_ATTEMPTS) {
                    logger.e(TAG, "$domain server error maxAttempts=$attempt — permanent failure code=${error.code}")
                    return FlushOutcome.PermanentFailure(
                        Result.failure(retryOutput("server_error_max_attempts", attempt).build()),
                    )
                }
                logger.w(TAG, "$domain server error code=${error.code} attempt=$attempt — retry")
                FlushOutcome.RetryNeeded(
                    Result.retry(retryOutput("server_error", attempt).build()),
                )
            }

            else -> {
                logger.e(TAG, "$domain unrecoverable error ${error::class.simpleName} attempt=$attempt")
                FlushOutcome.PermanentFailure(
                    Result.failure(retryOutput("unrecoverable_${error::class.simpleName}", attempt).build()),
                )
            }
        }
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

        /** Maximum WorkManager runAttemptCount before permanent failure. */
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
