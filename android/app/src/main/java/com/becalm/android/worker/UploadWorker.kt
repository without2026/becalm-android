package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
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
 * Per-source flush logic lives in dedicated uploaders ([RawEventUploader],
 * [CommitmentUploader]); this worker only resolves the session, dispatches to each
 * uploader, and emits the final [androidx.work.ListenableWorker.Result].
 *
 * The uploaders are constructed internally from the worker's existing collaborators so
 * that `UploadWorkerTest`, which instantiates [UploadWorker] directly, does not need to
 * know about the split.
 *
 * ## Lifecycle per run
 * 1. Resolve [userId] from [AuthRepository.currentSession] — no session → failure.
 * 2. Flush pending raw ingestion events in 100-item batches (SYNC-001, SYNC-004).
 * 3. On success, flush pending commitments in the same envelope.
 * 4. Record sync success via [SourceStatusRepository.recordSyncSuccess].
 *
 * Either flush may return [FlushOutcome.RetryNeeded] when the server accepts zero rows on a
 * page and every failure is `retryable=true`; the worker then emits [Result.retry] and calls
 * [SourceStatusRepository.recordSyncError] instead of [SourceStatusRepository.recordSyncSuccess]
 * so the UI does not report a green chip while rows are still pending.
 *
 * ## Retry strategy
 * - **429 RateLimited** (SYNC-005): surface attempt counter in output data; WorkManager
 *   `BackoffPolicy.EXPONENTIAL` (configured at enqueue time in SP-32) handles rescheduling.
 *   [UploadBackoff.nextDelaySeconds] is included in output data for diagnostics.
 * - **401 Unauthorized**: permanent failure — user must re-authenticate.
 * - **5xx ServerError / Network**: retry up to [UploadBackoff.MAX_ATTEMPTS] times.
 * - All other [com.becalm.android.core.result.BecalmError] variants (Io, Validation, Unknown):
 *   permanent failure — do not silently swallow; let WorkManager surface the failure for
 *   observability.
 *
 * ## PII policy
 * Counts and HTTP status codes are logged. Payload bodies are never written to logcat.
 *
 * ## Scheduled by
 * SP-32 WorkScheduler enqueues this class as `enqueueUniqueWork(UniqueWorkKeys.UPLOAD, …)`.
 * SYNC-006 requires REPLACE policy so that foreground re-triggers supersede any queued run.
 *
 * ## Idempotency
 * [RawIngestionRepository.markSynced] and [CommitmentRepository.markSynced] are called after
 * each successful batch. Re-running the worker after a partial flush is safe because rows
 * already marked `synced` are excluded by `findPendingSync`.
 */
@HiltWorker
public class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted private val workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    rawIngestionRepository: RawIngestionRepository,
    commitmentRepository: CommitmentRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    private val rawEventUploader: RawEventUploader = RawEventUploader(
        rawIngestionRepository = rawIngestionRepository,
        logger = logger,
    )

    private val commitmentUploader: CommitmentUploader = CommitmentUploader(
        commitmentRepository = commitmentRepository,
        logger = logger,
    )

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

        val now = Clock.System.now()

        // ── Step 2: flush raw ingestion events ────────────────────────────────
        val rawResult = rawEventUploader.flushRawIngestion(userId, attempt)
        val rawCount = when (rawResult) {
            is FlushOutcome.Success -> rawResult.count
            is FlushOutcome.TransportRetry -> return rawResult.result
            is FlushOutcome.RetryNeeded -> return handleBatchAllRetryable("rawIngestion", attempt, now)
            is FlushOutcome.PermanentFailure -> return rawResult.result
        }

        // ── Step 3: flush commitments ─────────────────────────────────────────
        val commitResult = commitmentUploader.flushCommitments(userId, attempt)
        val commitCount = when (commitResult) {
            is FlushOutcome.Success -> commitResult.count
            is FlushOutcome.TransportRetry -> return commitResult.result
            is FlushOutcome.RetryNeeded -> return handleBatchAllRetryable("commitment", attempt, now)
            is FlushOutcome.PermanentFailure -> return commitResult.result
        }

        // ── Step 4: record success ────────────────────────────────────────────
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

    /**
     * Surfaces a batch-all-retryable flush as [Result.retry] + a sync-error record.
     *
     * Intentionally does **not** call [SourceStatusRepository.recordSyncSuccess]: the batch
     * accepted zero rows, so a green chip would misrepresent sync health. [recordSyncError]
     * keeps the UI honest while WorkManager schedules the next attempt with backoff.
     */
    private suspend fun handleBatchAllRetryable(
        domain: String,
        attempt: Int,
        now: Instant,
    ): Result {
        logger.w(TAG, "$domain batch all retryable — Result.retry() (no recordSyncSuccess)")
        sourceStatusRepository.recordSyncError(
            SOURCE_TYPE,
            "batch all retryable, backing off",
            now,
        )
        return Result.retry(retryOutput("batch_all_retryable_$domain", attempt).build())
    }

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
