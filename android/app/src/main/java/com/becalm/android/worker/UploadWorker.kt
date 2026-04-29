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
import javax.inject.Provider

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
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val rawIngestionRepositoryProvider: Provider<RawIngestionRepository>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        authRepository: AuthRepository,
        rawIngestionRepository: RawIngestionRepository,
        commitmentRepository: CommitmentRepository,
        sourceStatusRepository: SourceStatusRepository,
        workScheduler: WorkScheduler,
        processingPauseGate: ProcessingPauseGate,
        logger: Logger,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        authRepositoryProvider = Provider { authRepository },
        rawIngestionRepositoryProvider = Provider { rawIngestionRepository },
        commitmentRepositoryProvider = Provider { commitmentRepository },
        sourceStatusRepositoryProvider = Provider { sourceStatusRepository },
        workSchedulerProvider = Provider { workScheduler },
        processingPauseGate = processingPauseGate,
        logger = logger,
    )

    public override suspend fun doWork(): Result {
        if (processingPauseGate.shouldSkip(TAG)) {
            return Result.success()
        }
        val attempt = workerParams.inputData.getInt(INPUT_KEY_ATTEMPT, 0)
        return createCoordinator().run(
            attempt = attempt,
            maxRetriesExceeded = hasExceededMaxRetries(logger, TAG, MAX_RETRIES),
        )
    }

    private fun createCoordinator(): UploadWorkerCoordinator =
        UploadWorkerCoordinator(
            authRepository = authRepositoryProvider.get(),
            rawEventUploader = RawEventUploader(
                rawIngestionRepository = rawIngestionRepositoryProvider.get(),
                logger = logger,
            ),
            commitmentUploader = CommitmentUploader(
                commitmentRepository = commitmentRepositoryProvider.get(),
                logger = logger,
            ),
            sourceStatusRepository = sourceStatusRepositoryProvider.get(),
            workScheduler = workSchedulerProvider.get(),
            logger = logger,
        )

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
