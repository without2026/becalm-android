package com.becalm.android.unit.worker

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
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
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.VoiceFailureNotifier
import com.becalm.android.worker.VoiceUploadWorker
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayInputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceUploadWorkerNotificationSpecTest {

    private val appContext: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val parsedUri: Uri = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val commitmentDao: CommitmentDao = mockk(relaxed = true)
    private val personIndexDao: PersonIndexDao = mockk(relaxed = true)
    private val sourceExtractionApi: SourceExtractionApi = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val processingPauseGate: ProcessingPauseGate = mockk(relaxed = true)
    private val voiceFailureNotifier: VoiceFailureNotifier = mockk(relaxed = true)
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

    @Before
    fun setUp() {
        every { appContext.applicationContext } returns appContext
        every { appContext.contentResolver } returns contentResolver
        every { contentResolver.openInputStream(any()) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        mockkStatic(ContextCompat::class)
        mockkStatic(Uri::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { Uri.parse(any()) } returns parsedUri
    }

    @After
    fun tearDown() {
        unmockkStatic(ContextCompat::class)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `VOI-006 terminal 502 failure posts voice processing notification when notifications are enabled`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.VOICE,
            sourceRef = "content://voice/raw-1",
            eventTitle = "긴 회의 녹음",
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "pending",
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(true)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
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
        } returns Response.error(
            502,
            """{"error":"output_truncated","message":"too long"}""".toResponseBody("application/json".toMediaType()),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 1) {
            processingStatusRepository.recordGemini(SourceType.VOICE, "Analyzing audio with Gemini")
        }
        coVerify(exactly = 1) {
            voiceFailureNotifier.notifyFailure(
                context = appContext,
                rawEventId = "raw-1",
                eventTitle = "긴 회의 녹음",
                reasonCode = "output_truncated",
            )
        }
    }

    @Test
    fun `VOI-001 successful empty extraction records last attempt to prevent duplicate reupload`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.CALL_RECORDING,
            sourceRef = "content://voice/raw-1",
            eventTitle = "통화 녹음",
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "pending",
        )
        val updatedSlot = slot<RawIngestionEventEntity>()
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        coEvery { rawIngestionEventDao.update(capture(updatedSlot)) } returns 1
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
                rawEventId = "raw-1",
                items = emptyList(),
                sourceEventParticipants = emptyList(),
                model = "gemini-2.5-flash",
                region = "us-central1",
                rawModelText = """{"items":[],"source_event_participants":[]}""",
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(0, updatedSlot.captured.commitmentsExtractedCount)
        assertEquals("pending", updatedSlot.captured.syncStatus)
        assertEquals(false, updatedSlot.captured.lastAttemptAt == null)
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    private fun buildWorker(): VoiceUploadWorker = VoiceUploadWorker(
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
        voiceFailureNotifier = voiceFailureNotifier,
        moshi = moshi,
        logger = logger,
        ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
    )

    private fun workerParams(): WorkerParameters = mockk<WorkerParameters>().also { params ->
        every { params.id } returns UUID.randomUUID()
        every {
            params.inputData
        } returns Data.Builder()
            .putString(VoiceUploadWorker.KEY_RAW_EVENT_ID, "raw-1")
            .putString(VoiceUploadWorker.KEY_AUDIO_URI, "content://voice/raw-1")
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
}
