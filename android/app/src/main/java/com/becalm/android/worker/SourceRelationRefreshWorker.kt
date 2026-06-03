package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

@HiltWorker
public class SourceRelationRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val rawIngestionRepositoryProvider: Provider<RawIngestionRepository>,
    private val calendarEventRepositoryProvider: Provider<CalendarEventRepository>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val sourceEventParticipantRepositoryProvider: Provider<SourceEventParticipantRepository>,
    private val commitmentParticipantRepositoryProvider: Provider<CommitmentParticipantRepository>,
    private val scheduleEventLinkRepositoryProvider: Provider<ScheduleEventLinkRepository>,
    private val userCorrectionRepositoryProvider: Provider<UserCorrectionRepository>,
    private val syncCursorStore: SyncCursorStore,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val sourceType = inputData.getString(KEY_SOURCE_TYPE)?.trim().orEmpty()
        if (sourceType.isBlank()) {
            logger.w(TAG, "missing sourceType")
            return Result.failure()
        }
        val userId = authRepositoryProvider.get().currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "no active session; skipping source relation refresh sourceType=$sourceType")
            return Result.success()
        }

        return when (
            val result = SourceRelationRefreshCoordinator(
                rawIngestionRepository = rawIngestionRepositoryProvider.get(),
                calendarEventRepository = calendarEventRepositoryProvider.get(),
                commitmentRepository = commitmentRepositoryProvider.get(),
                sourceEventParticipantRepository = sourceEventParticipantRepositoryProvider.get(),
                commitmentParticipantRepository = commitmentParticipantRepositoryProvider.get(),
                scheduleEventLinkRepository = scheduleEventLinkRepositoryProvider.get(),
                userCorrectionRepository = userCorrectionRepositoryProvider.get(),
                syncCursorStore = syncCursorStore,
                workScheduler = workSchedulerProvider.get(),
                logger = logger,
            ).refresh(
                userId = userId,
                plan = sourceRelationRefreshPlanFor(
                    sourceType = sourceType,
                    resetBeforeRefresh = inputData.getBoolean(KEY_RESET_MIRROR_CURSOR, false),
                ),
            )
        ) {
            is BecalmResult.Success -> Result.success()
            is BecalmResult.Failure -> {
                logger.w(TAG, "source relation refresh failed sourceType=$sourceType error=${result.error::class.simpleName}")
                if (result.error.isRetryable()) Result.retry() else Result.failure()
            }
        }
    }

    private fun BecalmError.isRetryable(): Boolean =
        when (this) {
            is BecalmError.Network,
            is BecalmError.RateLimited,
            is BecalmError.ServerError,
            is BecalmError.Io,
            is BecalmError.ExtractorUnavailable,
            is BecalmError.Unknown,
            -> true
            is BecalmError.Unauthorized,
            is BecalmError.Validation,
            is BecalmError.NotFound,
            is BecalmError.Permission,
            is BecalmError.Cancelled,
            -> false
        }

    public companion object {
        public const val KEY_SOURCE_TYPE: String = "source_type"
        public const val KEY_RESET_MIRROR_CURSOR: String = "reset_mirror_cursor"
        private const val TAG = "SourceRelationRefreshWorker"
    }
}

internal fun sourceRelationRefreshPlanFor(
    sourceType: String,
    resetBeforeRefresh: Boolean,
): SourceRelationRefreshPlan =
    when (sourceType) {
        SourceType.GMAIL,
        SourceType.OUTLOOK_MAIL,
        -> SourceRelationRefreshPlan(
            sourceType = sourceType,
            rawSourceType = sourceType,
            resetMirrorCursorBeforeRefresh = resetBeforeRefresh,
        )
        SourceType.GOOGLE_CALENDAR,
        SourceType.OUTLOOK_CALENDAR,
        -> SourceRelationRefreshPlan(
            sourceType = sourceType,
            calendarRefresh = CalendarRelationRefresh(),
            resetMirrorCursorBeforeRefresh = resetBeforeRefresh,
        )
        UploadWorker.SOURCE_TYPE -> SourceRelationRefreshPlan(
            sourceType = sourceType,
            sourceParticipantRefreshScope = SourceParticipantRefreshScope.ALL,
            resetMirrorCursorBeforeRefresh = resetBeforeRefresh,
        )
        else -> SourceRelationRefreshPlan(
            sourceType = sourceType,
            resetMirrorCursorBeforeRefresh = resetBeforeRefresh,
        )
    }
