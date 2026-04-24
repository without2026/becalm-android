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
import com.becalm.android.worker.ColdSyncStage2Worker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ColdSyncStage2WorkerSpecTest {

    @Test
    fun `COLD-004 stage2 worker starts stage2 persists completion and clears deferred flag once all terminal`() = runTest {
        val runtimeCoordinator: ColdSyncRuntimeCoordinator = mockk()
        val sourceStatusRepository: SourceStatusRepository = mockk()
        val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
        val logger: Logger = mockk(relaxed = true)
        val terminalStatuses = listOf(
            terminal(SourceType.GMAIL),
            terminal(SourceType.OUTLOOK_MAIL),
            terminal(SourceType.NAVER_IMAP),
            terminal(SourceType.DAUM_IMAP),
            terminal(SourceType.VOICE),
        )

        coEvery { runtimeCoordinator.startStage2(any()) } returns BecalmResult.Success(Unit)
        every { sourceStatusRepository.observeAll() } returns flowOf(terminalStatuses)

        val result = buildWorker(
            runtimeCoordinator = runtimeCoordinator,
            sourceStatusRepository = sourceStatusRepository,
            userPrefsStore = userPrefsStore,
            logger = logger,
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { runtimeCoordinator.startStage2(any()) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage2CompletedAt(any()) }
        coVerify(exactly = 1) { userPrefsStore.setColdSyncStage2Deferred(false) }
    }

    @Test
    fun `COLD-004 stage2 worker retries when stage2 start fails`() = runTest {
        val runtimeCoordinator: ColdSyncRuntimeCoordinator = mockk()
        coEvery { runtimeCoordinator.startStage2(any()) } returns BecalmResult.Failure(mockk(relaxed = true))

        val result = buildWorker(
            runtimeCoordinator = runtimeCoordinator,
            sourceStatusRepository = mockk(relaxed = true),
            userPrefsStore = mockk(relaxed = true),
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
        logger: Logger,
    ): ColdSyncStage2Worker = ColdSyncStage2Worker(
        appContext = mockk<Context>(relaxed = true),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        runtimeCoordinator = runtimeCoordinator,
        sourceStatusRepository = sourceStatusRepository,
        userPrefsStore = userPrefsStore,
        logger = logger,
    )
}
