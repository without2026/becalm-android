package com.becalm.android.worker.ingestion

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ColdSyncWorkInputs
import com.becalm.android.worker.ProcessingPauseGate
import kotlinx.datetime.Instant

internal suspend fun runServerBackedCalendarSync(
    sourceType: String,
    tag: String,
    runAttemptCount: Int,
    inputData: Data,
    authRepository: AuthRepository,
    calendarEventRepository: CalendarEventRepository,
    sourceStatusRepository: SourceStatusRepository,
    processingPauseGate: ProcessingPauseGate,
    clock: Clock,
    logger: Logger,
): Result {
    if (processingPauseGate.shouldSkip(tag)) {
        return Result.success()
    }
    logger.d(tag, "doWork started runAttempt=$runAttemptCount")

    val userId = authRepository.currentSession()?.userId
    if (userId == null) {
        logger.w(tag, "no active session — cannot sync source=$sourceType; failing without retry")
        return Result.failure()
    }

    val startedAt = clock.nowInstant()
    val lookbackDays = inputData.getInt(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS, NO_LOOKBACK)
        .takeIf { it > 0 }
    sourceStatusRepository.recordSyncStart(sourceType)

    when (val syncResult = calendarEventRepository.triggerServerSync()) {
        is BecalmResult.Success -> {
            logger.d(tag, "triggerServerSync succeeded synced=${syncResult.value.synced}")
        }
        is BecalmResult.Failure -> {
            val error = syncResult.error
            return when (error) {
                is BecalmError.RateLimited -> {
                    logger.w(
                        tag,
                        "triggerServerSync rate-limited retryAfter=${error.retryAfterSeconds}s — scheduling retry",
                    )
                    Result.retry()
                }
                is BecalmError.Unauthorized -> {
                    logger.w(tag, "triggerServerSync unauthorized — session invalid; failing")
                    sourceStatusRepository.recordSyncError(
                        sourceType,
                        "Unauthorized: session invalid",
                        clock.nowInstant(),
                    )
                    Result.failure()
                }
                is BecalmError.Network, is BecalmError.ServerError -> {
                    val msg = error.toCalendarSyncMessage()
                    logger.w(tag, "triggerServerSync transient error: $msg — scheduling retry")
                    Result.retry()
                }
                else -> {
                    val msg = error.toCalendarSyncMessage()
                    logger.e(tag, "triggerServerSync failed: $msg")
                    sourceStatusRepository.recordSyncError(
                        sourceType,
                        msg,
                        clock.nowInstant(),
                    )
                    Result.failure()
                }
            }
        }
    }

    val rangeStart = lookbackDays?.let { windowDays -> daysAgo(startedAt, windowDays) }
    val rangeEnd = lookbackDays?.let { windowDays -> daysAhead(startedAt, windowDays) }
    val refreshStats = when (
        val refreshResult = calendarEventRepository.refreshSince(
            userId = userId,
            since = null,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
        )
    ) {
        is BecalmResult.Success -> refreshResult.value
        is BecalmResult.Failure -> {
            val error = refreshResult.error
            val msg = error.toCalendarSyncMessage()
            return when (error) {
                is BecalmError.Network, is BecalmError.ServerError -> {
                    logger.w(tag, "refreshSince transient error: $msg — scheduling retry")
                    Result.retry()
                }
                else -> {
                    logger.e(tag, "refreshSince failed: $msg")
                    sourceStatusRepository.recordSyncError(
                        sourceType,
                        msg,
                        clock.nowInstant(),
                    )
                    Result.failure()
                }
            }
        }
    }

    logger.d(
        tag,
        "refreshSince complete fetched=${refreshStats.fetched} upserted=${refreshStats.upserted} hasMore=${refreshStats.hasMore}",
    )

    sourceStatusRepository.recordSyncSuccess(sourceType, clock.nowInstant())

    logger.d(
        tag,
        "doWork success upserted=${refreshStats.upserted} elapsedMs=${clock.nowInstant().toEpochMilliseconds() - startedAt.toEpochMilliseconds()}",
    )
    return Result.success()
}

private const val NO_LOOKBACK: Int = -1

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
