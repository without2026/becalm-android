package com.becalm.android.worker

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.UserCorrectionRepository

internal class UserCorrectionUploader(
    private val repository: UserCorrectionRepository,
    private val logger: Logger,
) {
    suspend fun flushCorrections(userId: String, attempt: Int): FlushOutcome {
        var totalUploaded = 0
        while (true) {
            val pending = repository.findPendingSync(userId, UploadWorker.BATCH_SIZE)
            if (pending.isEmpty()) break

            when (val result = repository.uploadBatch(pending)) {
                is BecalmResult.Success -> {
                    val failedKeys = result.value.failed
                        .mapNotNull { it.id ?: it.idempotencyKey }
                        .toSet()
                    val retryableFailures = result.value.failed.filter { it.retryable }
                    val syncedIds = pending
                        .filterNot { it.id in failedKeys || it.idempotencyKey in failedKeys }
                        .map { it.id }
                    if (syncedIds.isNotEmpty()) {
                        repository.markSynced(syncedIds)
                        totalUploaded += syncedIds.size
                    }
                    result.value.failed
                        .filterNot { it.retryable }
                        .forEach { failure ->
                            pending.firstOrNull { it.id == failure.id || it.idempotencyKey == failure.idempotencyKey }
                                ?.let { repository.markFailed(it.id, failure.message ?: failure.error) }
                        }
                    if (retryableFailures.isNotEmpty() && syncedIds.isEmpty()) {
                        return FlushOutcome.RetryNeeded
                    }
                }
                is BecalmResult.Failure -> {
                    return mapErrorToOutcome(
                        logger = logger,
                        error = result.error,
                        attempt = attempt,
                        domain = "user_correction",
                    )
                }
            }
        }
        return FlushOutcome.Success(totalUploaded)
    }
}
