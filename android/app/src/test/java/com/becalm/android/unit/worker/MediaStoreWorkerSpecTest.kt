package com.becalm.android.unit.worker

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.ingestion.MediaStoreWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.coEvery
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MediaStoreWorkerSpecTest {

    private val appContext: Context = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val processingPauseGate: ProcessingPauseGate = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val directExecutor = java.util.concurrent.Executor { runnable -> runnable.run() }
    private val taskExecutor: TaskExecutor = object : TaskExecutor {
        override fun getMainThreadExecutor() = directExecutor
        override fun getSerialTaskExecutor() = object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
            override fun execute(command: Runnable) = command.run()
            override fun hasPendingTasks(): Boolean = false
        }
    }
    private val progressUpdater: ProgressUpdater = ProgressUpdater { _, _, _ ->
        SettableFuture.create<Void>().apply { set(null) }
    }
    private val foregroundUpdater: ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        SettableFuture.create<Void>().apply { set(null) }
    }

    @Before
    fun setUp() {
        every { appContext.applicationContext } returns appContext
        mockkStatic(ContextCompat::class)
        every {
            ContextCompat.checkSelfPermission(any(), any())
        } returns android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `ING-001 retries when SAF recordings tree grant is missing`() = runTest {
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(null)

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
    }

    @Test
    fun `PIPA-004 paused processing short-circuits MediaStore worker`() = runTest {
        coEvery { processingPauseGate.shouldSkip(any()) } returns true

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
    }

    private fun buildWorker(runAttemptCount: Int = 0): MediaStoreWorker = MediaStoreWorker(
        appContext = appContext,
        workerParams = workerParams(runAttemptCount),
        syncCursorStore = syncCursorStore,
        sourceStatusRepository = sourceStatusRepository,
        rawIngestionEventDao = rawIngestionEventDao,
        workScheduler = workScheduler,
        userPrefsStore = userPrefsStore,
        processingPauseGate = processingPauseGate,
        logger = logger,
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun workerParams(runAttemptCount: Int): WorkerParameters =
        mockk<WorkerParameters>().also { params ->
            every { params.id } returns UUID.randomUUID()
            every { params.inputData } returns androidx.work.Data.EMPTY
            every { params.tags } returns emptySet()
            every { params.triggeredContentUris } returns emptyList()
            every { params.triggeredContentAuthorities } returns emptyList()
            every { params.network } returns null
            every { params.runAttemptCount } returns runAttemptCount
            every { params.backgroundExecutor } returns directExecutor
            every { params.taskExecutor } returns taskExecutor
            every { params.workerFactory } returns WorkerFactory.getDefaultWorkerFactory()
            every { params.progressUpdater } returns progressUpdater
            every { params.foregroundUpdater } returns foregroundUpdater
        }
}
