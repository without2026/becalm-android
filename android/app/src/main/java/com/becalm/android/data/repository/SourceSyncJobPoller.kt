package com.becalm.android.data.repository

import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.MailSyncResponse
import com.becalm.android.data.remote.dto.SourceSyncJobResponse
import kotlinx.coroutines.CancellationException
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
    val hasMorePages: Boolean = false,
    val backfillComplete: Boolean? = null,
)

internal sealed interface SourceSyncJobPollResult {
    data class Completed(val synced: Int) : SourceSyncJobPollResult
    data class Pending(
        val message: String,
        val retryAfterSeconds: Long?,
        val reasonCode: String? = null,
        val stage: String? = null,
        val progress: Double? = null,
        val synced: Int = 0,
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
        var statusUnavailable = false
        onSnapshot(snapshot)
        val attempts = maxPollAttempts.coerceAtLeast(1)
        repeat(attempts) { attempt ->
            when (val terminal = snapshot.terminalResultOrNull()) {
                null -> Unit
                else -> return terminal
            }

            if (attempt == attempts - 1) {
                return if (statusUnavailable) snapshot.statusUnavailablePending() else snapshot.runningPending()
            }

            val waitMs = (snapshot.retryAfterSeconds ?: 1L).coerceAtLeast(0L) * 1_000L
            if (waitMs > 0L) delayMillis(waitMs)

            val response = try {
                api.getSourceSyncJob(jobId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.w(
                    TAG,
                    "source sync job status temporarily unavailable sourceType=$sourceType jobId=$jobId error=${error::class.java.simpleName}",
                    error,
                )
                statusUnavailable = true
                return@repeat
            }
            if (!response.isSuccessful) {
                val retryable = response.code() == 404 || response.code() == 429 || response.code() in 500..599
                logger.w(
                    TAG,
                    "source sync job poll failed sourceType=$sourceType jobId=$jobId http=${response.code()} retryable=$retryable",
                )
                if (retryable) {
                    statusUnavailable = true
                    return@repeat
                }
                return SourceSyncJobPollResult.Failed("HTTP ${response.code()}", retryable = false)
            }
            val body = response.body()
            if (body == null) {
                logger.w(TAG, "source sync job poll returned empty body sourceType=$sourceType jobId=$jobId")
                statusUnavailable = true
                return@repeat
            }
            snapshot = body.toSnapshot()
            statusUnavailable = false
            onSnapshot(snapshot)
        }
        return if (statusUnavailable) snapshot.statusUnavailablePending() else snapshot.runningPending()
    }

    private fun SourceSyncJobSnapshot.runningPending(): SourceSyncJobPollResult.Pending =
        SourceSyncJobPollResult.Pending(
            message = message ?: "Provider sync still running",
            retryAfterSeconds = retryAfterSeconds,
            stage = stage,
            progress = progress,
            synced = synced,
        )

    private fun SourceSyncJobSnapshot.statusUnavailablePending(): SourceSyncJobPollResult.Pending =
        SourceSyncJobPollResult.Pending(
            message = "Source sync status is temporarily unavailable. Please retry shortly.",
            retryAfterSeconds = retryAfterSeconds ?: 1L,
            reasonCode = "source_sync_status_unavailable",
            stage = stage,
            progress = progress,
            synced = synced,
        )

    private fun SourceSyncJobSnapshot.terminalResultOrNull(): SourceSyncJobPollResult? =
        when (status?.lowercase()) {
            null,
            "",
            "pending",
            "running",
            -> null
            "retry" -> if (errorCode != null && errorCode in RETRY_WAIT_REASON_CODES) {
                SourceSyncJobPollResult.Pending(
                    message = errorMessage ?: errorCode,
                    retryAfterSeconds = retryAfterSeconds,
                    reasonCode = errorCode,
                    stage = stage,
                    progress = progress,
                    synced = synced,
                )
            } else {
                null
            }
            "succeeded" -> if (hasMorePages) {
                SourceSyncJobPollResult.Pending(
                    message = message ?: "Provider sync still importing older pages",
                    retryAfterSeconds = retryAfterSeconds,
                    reasonCode = "provider_has_more_pages",
                    stage = stage,
                    progress = progress,
                    synced = synced,
                )
            } else {
                SourceSyncJobPollResult.Completed(synced)
            }
            "needs_reauth" -> SourceSyncJobPollResult.Failed(errorMessage ?: errorCode ?: "needs_reauth", retryable = false)
            "failed",
            "cancelled",
            -> SourceSyncJobPollResult.Failed(errorMessage ?: errorCode ?: status, retryable = false)
            else -> SourceSyncJobPollResult.Pending(
                message = message ?: "Provider sync status=$status",
                retryAfterSeconds = retryAfterSeconds,
                stage = stage,
                progress = progress,
                synced = synced,
            )
        }

    private companion object {
        private const val TAG = "SourceSyncJobPoller"
        private const val DEFAULT_MAX_POLL_ATTEMPTS = 3
        private val RETRY_WAIT_REASON_CODES = setOf(
            "backpressure_delayed",
            "llm_rate_limited_retrying",
        )
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
        hasMorePages = hasMorePages,
        backfillComplete = backfillComplete,
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
        hasMorePages = hasMorePages,
        backfillComplete = backfillComplete,
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
        hasMorePages = hasMorePages,
        backfillComplete = backfillComplete,
    )
