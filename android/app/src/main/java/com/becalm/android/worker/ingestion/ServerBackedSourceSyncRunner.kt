package com.becalm.android.worker.ingestion

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.WorkScheduler

internal data class ServerBackedSourceSyncRequest(
    val sourceType: String,
    val scanMessage: String? = null,
    val geminiMessage: String? = null,
    val syncedMessage: String? = null,
    val recordSyncSuccessBeforeRefresh: Boolean = false,
    val refreshFailureMessage: (BecalmError) -> String = { "Relation refresh failed" },
    val refreshFailureRetryable: (BecalmError) -> Boolean = { true },
    val refreshPlan: SourceRelationRefreshPlan,
    val trigger: suspend () -> ServerBackedTriggerResult,
)

internal sealed interface ServerBackedTriggerResult {
    data class Success(val syncedCount: Int? = null) : ServerBackedTriggerResult
    data class Failure(val message: String, val retryable: Boolean) : ServerBackedTriggerResult
}

internal enum class ServerBackedSourceSyncResult {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal fun ServerBackedSourceSyncResult.toWorkerResult(): ListenableWorker.Result = when (this) {
    ServerBackedSourceSyncResult.SUCCESS -> ListenableWorker.Result.success()
    ServerBackedSourceSyncResult.RETRY -> ListenableWorker.Result.retry()
    ServerBackedSourceSyncResult.FAILURE -> ListenableWorker.Result.failure()
}

internal class ServerBackedSourceSyncRunner(
    private val rawIngestionRepository: RawIngestionRepository? = null,
    private val calendarEventRepository: CalendarEventRepository? = null,
    private val commitmentRepository: CommitmentRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository? = null,
    private val workScheduler: WorkScheduler,
    private val clock: Clock,
    private val logger: Logger,
    private val tag: String,
) {
    suspend fun run(
        userId: String,
        request: ServerBackedSourceSyncRequest,
    ): ServerBackedSourceSyncResult {
        val startedAt = clock.nowInstant()
        sourceStatusRepository.recordSyncStart(request.sourceType)
        request.scanMessage?.let {
            processingStatusRepository?.recordScanning(request.sourceType, it)
        }
        request.geminiMessage?.let {
            processingStatusRepository?.recordGemini(request.sourceType, it)
        }

        when (val triggerResult = request.trigger()) {
            is ServerBackedTriggerResult.Failure -> {
                sourceStatusRepository.recordSyncError(request.sourceType, triggerResult.message, clock.nowInstant())
                processingStatusRepository?.recordError(request.sourceType, triggerResult.message)
                logger.w(tag, "backend sync failed source=${request.sourceType} message=${triggerResult.message}")
                return if (triggerResult.retryable) {
                    ServerBackedSourceSyncResult.RETRY
                } else {
                    ServerBackedSourceSyncResult.FAILURE
                }
            }
            is ServerBackedTriggerResult.Success -> {
                triggerResult.syncedCount?.let { count ->
                    processingStatusRepository?.recordSynced(
                        sourceType = request.sourceType,
                        itemCount = count,
                        message = request.syncedMessage,
                    )
                }
                if (request.recordSyncSuccessBeforeRefresh) {
                    sourceStatusRepository.recordSyncSuccess(request.sourceType, clock.nowInstant())
                }
            }
        }

        val refreshStats = when (
            val refresh = SourceRelationRefreshCoordinator(
                rawIngestionRepository = rawIngestionRepository,
                calendarEventRepository = calendarEventRepository,
                commitmentRepository = commitmentRepository,
                sourceEventParticipantRepository = sourceEventParticipantRepository,
                commitmentParticipantRepository = commitmentParticipantRepository,
                workScheduler = workScheduler,
                logger = logger,
            ).refresh(
                userId = userId,
                plan = request.refreshPlan,
            )
        ) {
            is BecalmResult.Success -> refresh.value
            is BecalmResult.Failure -> {
                val message = request.refreshFailureMessage(refresh.error)
                sourceStatusRepository.recordSyncError(request.sourceType, message, clock.nowInstant())
                processingStatusRepository?.recordError(request.sourceType, message)
                logger.w(
                    tag,
                    "relation refresh failed source=${request.sourceType} " +
                        "error=${refresh.error::class.simpleName} message=$message",
                )
                return if (request.refreshFailureRetryable(refresh.error)) {
                    ServerBackedSourceSyncResult.RETRY
                } else {
                    ServerBackedSourceSyncResult.FAILURE
                }
            }
        }

        if (!request.recordSyncSuccessBeforeRefresh) {
            sourceStatusRepository.recordSyncSuccess(request.sourceType, clock.nowInstant())
        }
        logger.d(
            tag,
            "backend sync complete source=${request.sourceType} changed=${refreshStats.changedCount} " +
                "elapsedMs=${clock.nowInstant().toEpochMilliseconds() - startedAt.toEpochMilliseconds()}",
        )
        return ServerBackedSourceSyncResult.SUCCESS
    }
}
