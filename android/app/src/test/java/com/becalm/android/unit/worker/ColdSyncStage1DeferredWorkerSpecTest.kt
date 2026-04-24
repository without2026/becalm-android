package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.today.ColdSyncRuntimeCoordinator
import com.becalm.android.worker.ColdSyncStage1DeferredWorker
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncStage1DeferredWorkerSpecTest {

    @Test
    fun `COLD-006 deferred worker starts stage1 persists completion and enqueues stage2 after terminal statuses`() = runTest {
        val runtimeCoordinator: ColdSyncRuntimeCoordinator = mockk()
        val sourceStatusRepository: SourceStatusRepository = mockk()
        val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
        val workScheduler: WorkScheduler = mockk(relaxed = true)
        val logger: Logger = mockk(relaxed = true)
        val terminalStatuses = listOf(
            terminal(SourceType.GMAIL),
            terminal(SourceType.OUTLOOK_MAIL),
            terminal(SourceType.NAVER_IMAP),
            terminal(SourceType.DAUM_IMAP),
            terminal(SourceType.GOOGLE_CALENDAR),
            terminal(SourceType.OUTLOOK_CALENDAR),
        )

        coEvery { runtimeCoordinator.startStage1(any()) } returns BecalmResult.Success(Unit)
        every { runtimeCoordinator.observeUserProfileReady() } returns flowOf(true)
        every { sourceStatusRepository.observeAll() } returns flowOf(terminalStatuses)
        every { userPrefsStore.observeColdSyncStage1CompletedAt() } returns flowOf(null)

        val result = buildWorker(
            runtimeCoordinator = runtimeCoordinator,
            sourceStatusRepository = sourceStatusRepository,
            userPrefsStore = userPrefsStore,
            workScheduler = workScheduler,
            logger = logger,
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { runtimeCoordinator.startStage1(any()) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage1CompletedAt(any()) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage2Deferred(false) }
        verify(exactly = 1) { workScheduler.enqueueColdSyncStage2() }
    }

    @Test
    fun `COLD-006 deferred worker retries when stage1 start fails`() = runTest {
        val runtimeCoordinator: ColdSyncRuntimeCoordinator = mockk()
        coEvery { runtimeCoordinator.startStage1(any()) } returns BecalmResult.Failure(mockk(relaxed = true))

        val result = buildWorker(
            runtimeCoordinator = runtimeCoordinator,
            sourceStatusRepository = mockk(relaxed = true),
            userPrefsStore = mockk(relaxed = true),
            workScheduler = mockk(relaxed = true),
            logger = mockk(relaxed = true),
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
    }

    private fun terminal(sourceType: String): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = SourceConnectionStatus.CONNECTED,
        lastSyncedAt = null,
        errorMessage = null,
    )

    private fun buildWorker(
        runtimeCoordinator: ColdSyncRuntimeCoordinator,
        sourceStatusRepository: SourceStatusRepository,
        userPrefsStore: UserPrefsStore,
        workScheduler: WorkScheduler,
        logger: Logger,
    ): ColdSyncStage1DeferredWorker = ColdSyncStage1DeferredWorker(
        appContext = mockk<Context>(relaxed = true),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        runtimeCoordinator = runtimeCoordinator,
        sourceStatusRepository = sourceStatusRepository,
        userPrefsStore = userPrefsStore,
        workScheduler = workScheduler,
        logger = logger,
    )
}
