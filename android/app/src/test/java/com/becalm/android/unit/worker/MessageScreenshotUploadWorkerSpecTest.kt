package com.becalm.android.unit.worker

import android.content.Context
import android.net.Uri
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
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.MessageScreenshotUploadWorker
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.MultipartBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class MessageScreenshotUploadWorkerSpecTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val appContext: Context = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val commitmentDao: CommitmentDao = mockk(relaxed = true)
    private val personIndexDao: PersonIndexDao = mockk(relaxed = true)
    private val sourceExtractionApi: SourceExtractionApi = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val processingPauseGate: ProcessingPauseGate = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val moshi = Moshi.Builder().build()
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

    @Test
    fun `message screenshot worker uploads image modality through shared extraction runner`() = runTest {
        val image = temp.newFile("normalized-kakao-thread.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val imageSlot = io.mockk.slot<MultipartBody.Part>()
        val entity = RawIngestionEventEntity(
            id = RAW_ID,
            userId = USER_ID,
            clientEventId = "client-shot-1",
            sourceType = SourceType.MESSAGE_SCREENSHOT,
            sourceRef = Uri.fromFile(image).toString(),
            eventTitle = "kakao-thread.png",
            timestamp = Instant.parse("2026-05-08T01:00:00Z"),
            syncStatus = "pending",
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById(RAW_ID, USER_ID) } returns entity
        coEvery { rawIngestionEventDao.update(any()) } returns 1
        coEvery {
            sourceExtractionApi.commitmentExtract(
                audio = null,
                image = capture(imageSlot),
                inputModality = any(),
                sourceType = any(),
                clientEventId = any(),
                rawEventId = any(),
                durationSeconds = any(),
                timestamp = any(),
                counterpartyRef = any(),
                eventTitle = any(),
                folder = any(),
                conversationRef = any(),
                previousThreadContext = any(),
                selfSpeakerId = any(),
                speakerMappings = any(),
                speakerPreviewId = any(),
            )
        } returns Response.success(
            SourceExtractionResponse(
                rawEventId = RAW_ID,
                items = emptyList(),
                sourceEventParticipants = emptyList(),
                model = "gemini-2.5-flash",
                region = "us-central1",
                rawModelText = """{"items":[],"source_event_participants":[]}""",
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals("image/jpeg", imageSlot.captured.body.contentType().toString())
        coVerify(exactly = 1) { sourceExtractionApi.commitmentExtract(audio = null, image = any(), inputModality = any(), sourceType = any(), clientEventId = any(), rawEventId = any(), durationSeconds = any(), timestamp = any(), counterpartyRef = any(), eventTitle = any(), folder = any(), conversationRef = any(), previousThreadContext = any(), selfSpeakerId = any(), speakerMappings = any(), speakerPreviewId = any()) }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    private fun buildWorker(): MessageScreenshotUploadWorker =
        MessageScreenshotUploadWorker(
            appContext = appContext,
            workerParams = workerParams(),
            rawIngestionEventDao = rawIngestionEventDao,
            commitmentDao = commitmentDao,
            personIndexDao = personIndexDao,
            sourceExtractionApi = sourceExtractionApi,
            userPrefsStore = userPrefsStore,
            sourceStatusRepository = sourceStatusRepository,
            processingStatusRepository = processingStatusRepository,
            workScheduler = workScheduler,
            processingPauseGate = processingPauseGate,
            moshi = moshi,
            logger = logger,
            ioDispatcher = Dispatchers.IO,
        )

    private fun workerParams(): WorkerParameters = mockk<WorkerParameters>().also { params ->
        every { params.id } returns UUID.randomUUID()
        every { params.inputData } returns Data.Builder()
            .putString(MessageScreenshotUploadWorker.KEY_RAW_EVENT_ID, RAW_ID)
            .build()
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

    private companion object {
        private const val USER_ID = "user-1"
        private const val RAW_ID = "raw-shot-1"
    }
}
