package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.extraction.CommitmentExtractionWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import javax.inject.Provider

class CommitmentExtractionWorkerManualSourceSpecTest {

    @Test
    fun `MAN-006 raw event extraction helper skips manual commitments`() {
        assertFalse(SourceType.ALL.contains("manual"))
        assertFalse(CommitmentExtractionWorker.Companion.supportsRawEventSource("manual"))
    }

    @Test
    fun `MAN-006 raw event extraction helper still accepts supported ingestion sources`() {
        assertTrue(CommitmentExtractionWorker.Companion.supportsRawEventSource(SourceType.VOICE))
        assertTrue(CommitmentExtractionWorker.Companion.supportsRawEventSource(SourceType.GMAIL))
        assertTrue(CommitmentExtractionWorker.Companion.supportsRawEventSource(SourceType.GOOGLE_CALENDAR))
    }

    @Test
    fun `EMAIL-001 AICORE_ERROR retries once then stops retry storm`() {
        assertTrue(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "AICORE_ERROR",
                runAttemptCount = 0,
            ),
        )
        assertFalse(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "AICORE_ERROR",
                runAttemptCount = 1,
            ),
        )
    }

    @Test
    fun `EMAIL-001 malformed LLM JSON does not retry the same prompt indefinitely`() {
        assertFalse(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "LLM_JSON_PARSE_FAILED",
                runAttemptCount = 3,
            ),
        )
    }

    @Test
    fun `EMAIL-001 unknown extractor failures use capped WorkManager retry semantics`() {
        assertTrue(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "UNKNOWN_TRANSIENT",
                runAttemptCount = 3,
            ),
        )
        assertFalse(
            CommitmentExtractionWorker.Companion.shouldRetryExtractorFailure(
                reason = "UNKNOWN_TRANSIENT",
                runAttemptCount = 5,
            ),
        )
    }

    @Test
    fun `AUTH-009 stale extraction succeeds without opening DAO or LLM providers`() = runTest {
        val userPrefsStore: UserPrefsStore = mockk()
        val processingPauseGate: ProcessingPauseGate = mockk()
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false

        val worker = CommitmentExtractionWorker(
            appContext = mockk<Context>(relaxed = true),
            workerParams = workerParams(
                Data.Builder()
                    .putString(CommitmentExtractionWorker.KEY_RAW_EVENT_ID, "raw-1")
                    .build(),
            ),
            rawIngestionEventDaoProvider = failingProvider("raw dao"),
            emailBodyDaoProvider = failingProvider("email dao"),
            commitmentDaoProvider = failingProvider("commitment dao"),
            userPrefsStore = userPrefsStore,
            metricsStoreProvider = failingProvider("metrics"),
            promptBuilderProvider = failingProvider("prompt builder"),
            quotedBlockSplitterProvider = failingProvider("splitter"),
            geminiNanoExtractorProvider = failingProvider("extractor"),
            processingStatusRepository = mockk<ProcessingStatusRepository>(relaxed = true),
            processingPauseGate = processingPauseGate,
            logger = mockk<Logger>(relaxed = true),
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
    }

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

    private fun workerParams(inputData: Data): WorkerParameters =
        mockk<WorkerParameters>().also { params ->
            every { params.id } returns UUID.randomUUID()
            every { params.inputData } returns inputData
            every { params.tags } returns emptySet()
            every { params.triggeredContentUris } returns emptyList()
            every { params.triggeredContentAuthorities } returns emptyList()
            every { params.network } returns null
            every { params.runAttemptCount } returns 0
            every { params.backgroundExecutor } returns directExecutor
            every { params.taskExecutor } returns taskExecutor
            every { params.workerFactory } returns WorkerFactory.getDefaultWorkerFactory()
            every { params.progressUpdater } returns progressUpdater
            every { params.foregroundUpdater } returns foregroundUpdater
        }

    private fun <T> failingProvider(name: String): Provider<T> =
        Provider { error("$name provider must stay lazy") }
}
