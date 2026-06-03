package com.becalm.android.worker.ingestion

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceSyncJobPollResult
import com.becalm.android.data.repository.SourceSyncJobPoller
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.data.repository.toSnapshot
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
    api: RailwayApi,
    calendarEventRepository: CalendarEventRepository,
    commitmentRepository: CommitmentRepository,
    sourceConnectionRepository: SourceConnectionRepository,
    sourceEventParticipantRepository: SourceEventParticipantRepository,
    commitmentParticipantRepository: CommitmentParticipantRepository,
    userCorrectionRepository: UserCorrectionRepository? = null,
    sourceStatusRepository: SourceStatusRepository,
    syncCursorStore: SyncCursorStore,
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
        userCorrectionRepository = userCorrectionRepository,
        sourceStatusRepository = sourceStatusRepository,
        syncCursorStore = syncCursorStore,
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
                resetMirrorCursorBeforeRefresh = true,
            ),
            refreshFailureMessage = { error -> error.toCalendarSyncMessage() },
            refreshFailureRetryable = { error ->
                error is BecalmError.Network || error is BecalmError.ServerError
            },
            trigger = {
                triggerConnectionScopedCalendarSync(
                    userId = userId,
                    sourceType = sourceType,
                    api = api,
                    sourceConnectionRepository = sourceConnectionRepository,
                    logger = logger,
                    tag = tag,
                )
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

private suspend fun triggerConnectionScopedCalendarSync(
    userId: String,
    sourceType: String,
    api: RailwayApi,
    sourceConnectionRepository: SourceConnectionRepository,
    logger: Logger,
    tag: String,
): ServerBackedTriggerResult {
    val connections = when (val refresh = sourceConnectionRepository.refresh(userId)) {
        is BecalmResult.Success -> refresh.value.syncableCalendarConnectionsFor(sourceType)
        is BecalmResult.Failure -> {
            val message = refresh.error.toCalendarSyncMessage()
            logger.w(tag, "source connection refresh failed before calendar sync: $message")
            return ServerBackedTriggerResult.Failure(
                message = message,
                retryable = refresh.error is BecalmError.Network || refresh.error is BecalmError.ServerError,
            )
        }
    }
    if (connections.isEmpty()) {
        return ServerBackedTriggerResult.Failure("No source connection for $sourceType", retryable = false)
    }

    val jobs = mutableListOf<Pair<SourceConnectionEntity, com.becalm.android.data.remote.dto.SourceSyncJobResponse>>()
    for (connection in connections) {
        val response = api.syncSourceConnection(connection.id)
        if (!response.isSuccessful) {
            val message = "HTTP ${response.code()}"
            return ServerBackedTriggerResult.Failure(
                message = message,
                retryable = response.code() == 429 || response.code() in 500..599,
            )
        }
        val body = response.body()
            ?: return ServerBackedTriggerResult.Failure("Empty response", retryable = true)
        jobs += connection to body
    }

    var synced = 0
    for ((connection, body) in jobs) {
        when (
            val pollResult = SourceSyncJobPoller(
                api = api,
                logger = logger,
            ).awaitTerminal(sourceType, body.toSnapshot())
        ) {
            is SourceSyncJobPollResult.Completed -> {
                synced += pollResult.synced
                logger.d(tag, "calendar connection sync success source=$sourceType connectionId=${connection.id} synced=${pollResult.synced}")
            }
            is SourceSyncJobPollResult.Pending -> return ServerBackedTriggerResult.Pending(
                message = pollResult.message,
                retryAfterSeconds = pollResult.retryAfterSeconds,
                reasonCode = pollResult.reasonCode,
                syncedCount = pollResult.synced,
            )
            is SourceSyncJobPollResult.Failed -> return ServerBackedTriggerResult.Failure(
                message = pollResult.message,
                retryable = pollResult.retryable,
            )
        }
    }
    return ServerBackedTriggerResult.Success(syncedCount = synced)
}

private fun List<SourceConnectionEntity>.syncableCalendarConnectionsFor(sourceType: String): List<SourceConnectionEntity> =
    filter { connection ->
        connection.status != "disconnected" && connection.toCalendarSourceType() == sourceType
    }

private fun SourceConnectionEntity.toCalendarSourceType(): String? =
    when {
        provider == "google" && capability == "calendar" -> com.becalm.android.data.remote.dto.SourceType.GOOGLE_CALENDAR
        provider == "outlook" && capability == "calendar" -> com.becalm.android.data.remote.dto.SourceType.OUTLOOK_CALENDAR
        else -> null
    }

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
