package com.becalm.android.worker.ingestion

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.CalendarRelationRefresh
import com.becalm.android.worker.ColdSyncWorkInputs
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.WorkerRunGuard
import kotlinx.datetime.Instant

internal suspend fun runServerBackedCalendarSync(
    sourceType: String,
    tag: String,
    runAttemptCount: Int,
    inputData: Data,
    authRepository: AuthRepository,
    calendarEventRepository: CalendarEventRepository,
    commitmentRepository: CommitmentRepository,
    sourceEventParticipantRepository: SourceEventParticipantRepository,
    commitmentParticipantRepository: CommitmentParticipantRepository,
    sourceStatusRepository: SourceStatusRepository,
    workScheduler: WorkScheduler,
    processingPauseGate: ProcessingPauseGate,
    clock: Clock,
    logger: Logger,
): Result {
    WorkerRunGuard(
        tag = tag,
        runAttemptCount = runAttemptCount,
        maxRetries = MAX_RETRIES,
        processingPauseGate = processingPauseGate,
        logger = logger,
    ).terminalResultOrNull()?.let { return it }
    logger.d(tag, "doWork started runAttempt=$runAttemptCount")

    val userId = authRepository.currentSession()?.userId
    if (userId == null) {
        logger.w(tag, "no active session — cannot sync source=$sourceType; failing without retry")
        return Result.failure()
    }

    val startedAt = clock.nowInstant()
    val lookbackDays = inputData.getInt(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS, NO_LOOKBACK)
        .takeIf { it > 0 }
    val rangeStart = lookbackDays?.let { windowDays -> daysAgo(startedAt, windowDays) }
    val rangeEnd = lookbackDays?.let { windowDays -> daysAhead(startedAt, windowDays) }

    val syncOutcome = ServerBackedSourceSyncRunner(
        calendarEventRepository = calendarEventRepository,
        commitmentRepository = commitmentRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        sourceStatusRepository = sourceStatusRepository,
        workScheduler = workScheduler,
        clock = clock,
        logger = logger,
        tag = tag,
    ).run(
        userId = userId,
        request = ServerBackedSourceSyncRequest(
            sourceType = sourceType,
            refreshPlan = SourceRelationRefreshPlan(
                sourceType = sourceType,
                calendarRefresh = CalendarRelationRefresh(
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                ),
            ),
            refreshFailureMessage = { error -> error.toCalendarSyncMessage() },
            refreshFailureRetryable = { error ->
                error is BecalmError.Network || error is BecalmError.ServerError
            },
            trigger = {
                when (val syncResult = calendarEventRepository.triggerServerSync()) {
                    is BecalmResult.Success -> {
                        logger.d(tag, "triggerServerSync succeeded synced=${syncResult.value.synced}")
                        ServerBackedTriggerResult.Success()
                    }
                    is BecalmResult.Failure -> {
                        val error = syncResult.error
                        val message = if (error is BecalmError.Unauthorized) {
                            "Unauthorized: session invalid"
                        } else {
                            error.toCalendarSyncMessage()
                        }
                        logger.w(tag, "triggerServerSync failed: $message")
                        ServerBackedTriggerResult.Failure(
                            message = message,
                            retryable = error is BecalmError.RateLimited ||
                                error is BecalmError.Network ||
                                error is BecalmError.ServerError,
                        )
                    }
                }
            },
        ),
    )

    if (syncOutcome == ServerBackedSourceSyncResult.SUCCESS) {
        logger.d(
            tag,
            "doWork success elapsedMs=${clock.nowInstant().toEpochMilliseconds() - startedAt.toEpochMilliseconds()}",
        )
    }
    return syncOutcome.toWorkerResult()
}

private const val NO_LOOKBACK: Int = -1
private const val MAX_RETRIES: Int = 5

private fun daysAgo(now: Instant, days: Int): Instant =
    Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - days * 86_400_000L)

private fun daysAhead(now: Instant, days: Int): Instant =
    Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + days * 86_400_000L)

private fun BecalmError.toCalendarSyncMessage(): String = when (this) {
    is BecalmError.Network -> "Network error HTTP $code: $message"
    is BecalmError.Unauthorized -> "Unauthorized"
    is BecalmError.RateLimited -> "Rate limited (retryAfter=${retryAfterSeconds}s)"
    is BecalmError.ServerError -> "Server error HTTP $code"
    is BecalmError.Validation -> "Validation error field=$field: $message"
    is BecalmError.Io -> "IO error: $message"
    is BecalmError.Permission -> "Permission denied: $permission"
    is BecalmError.NotFound -> "Not found: $resource"
    is BecalmError.Cancelled -> "Cancelled"
    is BecalmError.ExtractorUnavailable -> "Extractor unavailable: reason=$reason"
    is BecalmError.Unknown -> "Unknown: ${throwable.message}"
}
