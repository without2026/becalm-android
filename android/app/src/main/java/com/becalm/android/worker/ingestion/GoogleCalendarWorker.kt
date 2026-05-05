package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider

/**
 * Thin WorkManager bridge for the backend-owned Google Calendar sync path.
 */
@HiltWorker
public class GoogleCalendarWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepositoryProvider: Provider<AuthRepository>,
    private val calendarEventRepositoryProvider: Provider<CalendarEventRepository>,
    private val commitmentRepositoryProvider: Provider<CommitmentRepository>,
    private val sourceEventParticipantRepositoryProvider: Provider<SourceEventParticipantRepository>,
    private val commitmentParticipantRepositoryProvider: Provider<CommitmentParticipantRepository>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val workSchedulerProvider: Provider<WorkScheduler>,
    private val processingPauseGate: ProcessingPauseGate,
    private val clock: Clock,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result {
        if (processingPauseGate.shouldSkip(TAG)) {
            return Result.success()
        }
        return runServerBackedCalendarSync(
            sourceType = SourceType.GOOGLE_CALENDAR,
            tag = TAG,
            runAttemptCount = runAttemptCount,
            inputData = inputData,
            authRepository = authRepositoryProvider.get(),
            calendarEventRepository = calendarEventRepositoryProvider.get(),
            commitmentRepository = commitmentRepositoryProvider.get(),
            sourceEventParticipantRepository = sourceEventParticipantRepositoryProvider.get(),
            commitmentParticipantRepository = commitmentParticipantRepositoryProvider.get(),
            sourceStatusRepository = sourceStatusRepositoryProvider.get(),
            workScheduler = workSchedulerProvider.get(),
            processingPauseGate = processingPauseGate,
            clock = clock,
            logger = logger,
        )
    }

    public companion object {
        private const val TAG = "GoogleCalendarWorker"
    }
}
