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
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Thin WorkManager bridge for the backend-owned Outlook Calendar sync path.
 */
@HiltWorker
public class OutlookCalendarWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingPauseGate: ProcessingPauseGate,
    private val clock: Clock,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result =
        runServerBackedCalendarSync(
            sourceType = SourceType.OUTLOOK_CALENDAR,
            tag = TAG,
            runAttemptCount = runAttemptCount,
            inputData = inputData,
            authRepository = authRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            processingPauseGate = processingPauseGate,
            clock = clock,
            logger = logger,
        )

    private companion object {
        private const val TAG = "OutlookCalendarWorker"
    }
}
