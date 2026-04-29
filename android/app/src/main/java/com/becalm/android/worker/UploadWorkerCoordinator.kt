package com.becalm.android.worker

import androidx.work.ListenableWorker.Result
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.SourceStatusRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

internal class UploadWorkerCoordinator(
    private val authRepository: AuthRepository,
    private val rawEventUploader: RawEventUploader,
    private val commitmentUploader: CommitmentUploader,
    private val sourceStatusRepository: SourceStatusRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) {

    suspend fun run(
        attempt: Int,
        maxRetriesExceeded: Boolean,
    ): Result {
        if (maxRetriesExceeded) return Result.failure()

        val userId = authRepository.currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "no session — aborting upload attempt=$attempt")
            return Result.failure(
                retryOutput("no_session", attempt).build(),
            )
        }

        logger.d(TAG, "doWork start attempt=$attempt userId=[redacted]")
        val now = Clock.System.now()

        val rawCount = when (val rawResult = rawEventUploader.flushRawIngestion(userId, attempt)) {
            is FlushOutcome.Success -> rawResult.count
            is FlushOutcome.TransportRetry -> {
                sourceStatusRepository.recordSyncError(UploadWorker.SOURCE_TYPE, "raw upload retry", now)
                return rawResult.result
            }
            is FlushOutcome.RetryNeeded -> return handleBatchAllRetryable("rawIngestion", now)
            is FlushOutcome.PermanentFailure -> return rawResult.result
        }

        val commitmentCount = when (val commitResult = commitmentUploader.flushCommitments(userId, attempt)) {
            is FlushOutcome.Success -> commitResult.count
            is FlushOutcome.TransportRetry -> {
                sourceStatusRepository.recordSyncError(UploadWorker.SOURCE_TYPE, "commitment upload retry", now)
                return commitResult.result
            }
            is FlushOutcome.RetryNeeded -> return handleBatchAllRetryable("commitment", now)
            is FlushOutcome.PermanentFailure -> return commitResult.result
        }

        sourceStatusRepository.recordSyncSuccess(UploadWorker.SOURCE_TYPE, now)
        if (rawCount > 0 || commitmentCount > 0) {
            workScheduler.enqueuePersonInteractionIndex()
        }
        logger.i(
            TAG,
            "doWork complete rawUploaded=$rawCount commitUploaded=$commitmentCount attempt=$attempt",
        )
        return Result.success()
    }

    private suspend fun handleBatchAllRetryable(
        domain: String,
        now: Instant,
    ): Result {
        logger.w(TAG, "$domain batch all retryable — Result.retry() (no recordSyncSuccess)")
        sourceStatusRepository.recordSyncError(
            UploadWorker.SOURCE_TYPE,
            "batch all retryable, backing off",
            now,
        )
        return Result.retry()
    }

    private companion object {
        const val TAG: String = "UploadWorker"
    }
}
