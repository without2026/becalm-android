package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.today.ColdSyncRuntimeCoordinator
import com.becalm.android.ui.today.DefaultColdSyncRuntimeCoordinator
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock

/**
 * Deferred continuation of Stage 1 after the user taps [나중에 하기].
 */
@HiltWorker
public class ColdSyncStage1DeferredWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val runtimeCoordinatorProvider: Provider<ColdSyncRuntimeCoordinator>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {
    public constructor(
        appContext: Context,
        workerParams: WorkerParameters,
        runtimeCoordinator: ColdSyncRuntimeCoordinator,
        sourceStatusRepository: SourceStatusRepository,
        userPrefsStore: UserPrefsStore,
        workScheduler: WorkScheduler,
        logger: Logger,
    ) : this(
        appContext = appContext,
        workerParams = workerParams,
        runtimeCoordinatorProvider = Provider { runtimeCoordinator },
        sourceStatusRepositoryProvider = Provider { sourceStatusRepository },
        userPrefsStore = userPrefsStore,
        workScheduler = workScheduler,
        logger = logger,
    )

    override suspend fun doWork(): Result {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return Result.failure()

        val runtimeCoordinator = runtimeCoordinatorProvider.get()
        val sourceStatusRepository = sourceStatusRepositoryProvider.get()

        when (runtimeCoordinator.startStage1(Clock.System.now())) {
            is com.becalm.android.core.result.BecalmResult.Failure -> return Result.retry()
            is com.becalm.android.core.result.BecalmResult.Success -> Unit
        }

        runtimeCoordinator.observeUserProfileReady().first { it }
        sourceStatusRepository.observeAll().first { statuses: List<SourceStatus> ->
            DefaultColdSyncRuntimeCoordinator.STAGE1_SOURCE_TYPES.all { sourceType ->
                statuses.any { it.sourceType == sourceType && it.status in TERMINAL_STATUSES }
            }
        }

        if (userPrefsStore.observeColdSyncStage1CompletedAt().first() == null) {
            userPrefsStore.setColdSyncStage1CompletedAt(Clock.System.now().toEpochMilliseconds())
        }
        userPrefsStore.setColdSyncStage2Deferred(false)
        workScheduler.enqueueColdSyncStage2()
        logger.d(TAG, "deferred stage1 completed and stage2 enqueued")
        return Result.success()
    }

    private companion object {
        private val TERMINAL_STATUSES = setOf(
            SourceConnectionStatus.CONNECTED,
            SourceConnectionStatus.ERROR,
        )
        private const val TAG = "ColdSyncStage1DefWkr"
        private const val MAX_RETRIES = 5
    }
}
