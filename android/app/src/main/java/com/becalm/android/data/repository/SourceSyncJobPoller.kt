package com.becalm.android.data.repository

import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.SourceSyncJobResponse
import kotlinx.coroutines.delay

internal data class SourceSyncJobSnapshot(
    val jobId: String?,
    val status: String?,
    val accepted: Boolean,
    val retryAfterSeconds: Long?,
    val synced: Int,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val stage: String? = null,
    val progress: Double? = null,
    val message: String? = null,
    val syncMode: String? = null,
)

internal sealed interface SourceSyncJobPollResult {
    data class Completed(val synced: Int) : SourceSyncJobPollResult
    data class Pending(
        val message: String,
        val retryAfterSeconds: Long?,
        val reasonCode: String? = null,
        val stage: String? = null,
        val progress: Double? = null,
    ) : SourceSyncJobPollResult
    data class Failed(val message: String, val retryable: Boolean) : SourceSyncJobPollResult
}

internal class SourceSyncJobPoller(
    private val api: RailwayApi,
    private val logger: Logger,
    private val maxPollAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun awaitTerminal(
        sourceType: String,
        initial: SourceSyncJobSnapshot,
        onSnapshot: suspend (SourceSyncJobSnapshot) -> Unit = {},
    ): SourceSyncJobPollResult {
        val jobId = initial.jobId
        if (jobId.isNullOrBlank()) {
            return SourceSyncJobPollResult.Completed(initial.synced)
        }

        var snapshot = initial
        onSnapshot(snapshot)
        repeat(maxPollAttempts.coerceAtLeast(1)) { attempt ->
            when (val terminal = snapshot.terminalResultOrNull()) {
                null -> Unit
                else -> return terminal
            }

            if (attempt == maxPollAttempts.coerceAtLeast(1) - 1) {
                return SourceSyncJobPollResult.Pending(
                    message = snapshot.message ?: "Provider sync still running",
                    retryAfterSeconds = snapshot.retryAfterSeconds,
                    stage = snapshot.stage,
                    progress = snapshot.progress,
                )
            }

            val waitMs = (snapshot.retryAfterSeconds ?: 1L).coerceAtLeast(0L) * 1_000L
            if (waitMs > 0L) delayMillis(waitMs)

            val response = api.getSourceSyncJob(jobId)
            if (!response.isSuccessful) {
                val retryable = response.code() == 404 || response.code() == 429 || response.code() in 500..599
                logger.w(
                    TAG,
                    "source sync job poll failed sourceType=$sourceType jobId=$jobId http=${response.code()} retryable=$retryable",
                )
                return SourceSyncJobPollResult.Failed("HTTP ${response.code()}", retryable = retryable)
            }
            snapshot = response.body()?.toSnapshot()
                ?: return SourceSyncJobPollResult.Failed("Empty source sync job response", retryable = true)
            onSnapshot(snapshot)
        }
        return SourceSyncJobPollResult.Pending(
            message = snapshot.message ?: "Provider sync still running",
            retryAfterSeconds = snapshot.retryAfterSeconds,
            stage = snapshot.stage,
            progress = snapshot.progress,
        )
    }

    private fun SourceSyncJobSnapshot.terminalResultOrNull(): SourceSyncJobPollResult? =
        when (status?.lowercase()) {
            null,
            "",
            "pending",
            "running",
            -> null
            "retry" -> if (errorCode == "backpressure_delayed") {
                SourceSyncJobPollResult.Pending(
                    message = errorMessage ?: errorCode,
                    retryAfterSeconds = retryAfterSeconds,
                    reasonCode = errorCode,
                    stage = stage,
                    progress = progress,
                )
            } else {
                null
            }
            "succeeded" -> SourceSyncJobPollResult.Completed(synced)
            "needs_reauth" -> SourceSyncJobPollResult.Failed(errorMessage ?: errorCode ?: "needs_reauth", retryable = false)
            "failed",
            "cancelled",
            -> SourceSyncJobPollResult.Failed(errorMessage ?: errorCode ?: status, retryable = false)
            else -> SourceSyncJobPollResult.Pending(
                message = message ?: "Provider sync status=$status",
                retryAfterSeconds = retryAfterSeconds,
                stage = stage,
                progress = progress,
            )
        }

    private companion object {
        private const val TAG = "SourceSyncJobPoller"
        private const val DEFAULT_MAX_POLL_ATTEMPTS = 3
    }
}

internal fun MailSyncResponse.toSourceSyncJobSnapshot(): SourceSyncJobSnapshot =
    SourceSyncJobSnapshot(
        jobId = jobId,
        status = status,
        accepted = accepted,
        retryAfterSeconds = retryAfterSeconds,
        synced = synced,
        errorCode = errorCode,
        errorMessage = errorMessage,
        stage = stage,
        progress = progress,
        message = message,
        syncMode = syncMode,
    )

internal fun CalendarSyncResponse.toSourceSyncJobSnapshot(): SourceSyncJobSnapshot =
    SourceSyncJobSnapshot(
        jobId = jobId,
        status = status,
        accepted = accepted,
        retryAfterSeconds = retryAfterSeconds,
        synced = synced,
        errorCode = errorCode,
        errorMessage = errorMessage,
        stage = stage,
        progress = progress,
        message = message,
        syncMode = syncMode,
    )

internal fun SourceSyncJobResponse.toSnapshot(): SourceSyncJobSnapshot =
    SourceSyncJobSnapshot(
        jobId = jobId,
        status = status,
        accepted = accepted,
        retryAfterSeconds = retryAfterSeconds,
        synced = synced,
        errorCode = errorCode,
        errorMessage = errorMessage,
        stage = stage,
        progress = progress,
        message = message,
        syncMode = syncMode,
    )
