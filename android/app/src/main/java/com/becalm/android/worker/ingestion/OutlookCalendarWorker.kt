package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceConnectionRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

/**
 * Thin WorkManager bridge for the backend-owned Outlook Calendar sync path.
 */
@HiltWorker
public class OutlookCalendarWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val apiProvider: Provider<RailwayApi>,
    private val calendarEventRepositoryProvider: Provider<CalendarEventRepository>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val sourceConnectionRepositoryProvider: Provider<SourceConnectionRepository>,
    private val sourceEventParticipantRepositoryProvider: Provider<SourceEventParticipantRepository>,
    private val commitmentParticipantRepositoryProvider: Provider<CommitmentParticipantRepository>,
    private val userCorrectionRepositoryProvider: Provider<UserCorrectionRepository>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val syncCursorStore: SyncCursorStore,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val processingPauseGate: ProcessingPauseGate,
    private val clock: Clock,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result {
        return runServerBackedCalendarSync(
            sourceType = SourceType.OUTLOOK_CALENDAR,
            tag = TAG,
            runAttemptCount = runAttemptCount,
            inputData = inputData,
            authRepository = authRepositoryProvider.get(),
            api = apiProvider.get(),
            calendarEventRepository = calendarEventRepositoryProvider.get(),
            commitmentRepository = commitmentRepositoryProvider.get(),
            sourceConnectionRepository = sourceConnectionRepositoryProvider.get(),
            sourceEventParticipantRepository = sourceEventParticipantRepositoryProvider.get(),
            commitmentParticipantRepository = commitmentParticipantRepositoryProvider.get(),
            userCorrectionRepository = userCorrectionRepositoryProvider.get(),
            sourceStatusRepository = sourceStatusRepositoryProvider.get(),
            syncCursorStore = syncCursorStore,
            workScheduler = workSchedulerProvider.get(),
            processingPauseGate = processingPauseGate,
            clock = clock,
            logger = logger,
        )
    }

    private companion object {
        private const val TAG = "OutlookCalendarWorker"
    }
}
