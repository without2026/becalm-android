package com.becalm.android.unit.worker

import android.content.ContentResolver
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
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.remote.dto.SourceExtractedItemDto
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.MeetingTranscriptUploadWorker
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class MeetingTranscriptUploadWorkerSpecTest {
    private val appContext: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
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
    fun `meeting transcript worker extracts text and persists relation-backed commitments`() = runTest {
        val entity = RawIngestionEventEntity(
            id = RAW_ID,
            userId = USER_ID,
            clientEventId = "client-transcript-1",
            sourceType = SourceType.MEETING,
            sourceRef = "content://meeting/transcript-1",
            eventTitle = "standup.md",
            timestamp = Instant.parse("2026-05-05T01:00:00Z"),
            syncStatus = "pending",
        )
        val updated = slot<RawIngestionEventEntity>()
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any<Uri>()) } returns
            ByteArrayInputStream("내일 3시에 회의합시다.".toByteArray())
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById(RAW_ID, USER_ID) } returns entity
        coEvery { rawIngestionEventDao.update(capture(updated)) } returns 1
        coEvery {
            sourceExtractionApi.commitmentExtract(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns Response.success(
            SourceExtractionResponse(
                rawEventId = RAW_ID,
                items = listOf(
                    SourceExtractedItemDto(
                        type = "schedule",
                        text = "내일 3시 회의 확정",
                        quote = "내일 3시에 회의합시다",
                        counterpartyRef = "lee@example.com",
                        dueAt = null,
                        dueHint = "내일 3시",
                        dueIsApproximate = false,
                        confidence = 0.9f,
                        scheduleStatus = "confirmed",
                    ),
                ),
                personCandidates = emptyList(),
                model = "gemini-2.5-flash",
                region = "us-central1",
                rawModelText = """{"items":[],"person_candidates":[]}""",
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(1, updated.captured.commitmentsExtractedCount)
        assertEquals("pending", updated.captured.syncStatus)
        coVerify(exactly = 1) { commitmentDao.insertAll(match { it.single().itemType == "schedule" }) }
        coVerify(exactly = 1) { personIndexDao.upsertCommitmentParticipants(any()) }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    private fun buildWorker(): MeetingTranscriptUploadWorker =
        MeetingTranscriptUploadWorker(
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
            .putString(MeetingTranscriptUploadWorker.KEY_RAW_EVENT_ID, RAW_ID)
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
        private const val RAW_ID = "raw-transcript-1"
    }
}
