package com.becalm.android.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.Logger

internal class WorkerRunGuard(
    private val tag: String,
    private val runAttemptCount: Int,
    private val maxRetries: Int?,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) {
    suspend fun terminalResultOrNull(): ListenableWorker.Result? {
        if (processingPauseGate.shouldSkip(tag)) {
            return ListenableWorker.Result.success()
        }
        val retryLimit = maxRetries ?: return null
        if (runAttemptCount >= retryLimit) {
            logger.e(tag, "Exceeded $retryLimit attempts, failing permanently")
            return ListenableWorker.Result.failure()
        }
        return null
    }
}
