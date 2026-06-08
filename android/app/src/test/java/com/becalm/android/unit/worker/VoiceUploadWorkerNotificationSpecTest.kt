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
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.CommitmentProgressEventDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.ExtractionStorageRefDto
import com.becalm.android.data.remote.dto.ExtractionUploadPrepareRequest
import com.becalm.android.data.remote.dto.ExtractionUploadPrepareResponse
import com.becalm.android.data.remote.dto.SourceExtractionErrorEnvelope
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.ProcessingStatusMessages
import com.becalm.android.data.repository.RawIngestionRepository
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
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
    private val commitmentProgressEventDao: CommitmentProgressEventDao = mockk(relaxed = true)
    private val personIndexDao: PersonIndexDao = mockk(relaxed = true)
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao = mockk(relaxed = true)
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao = mockk(relaxed = true)
    private val sourceExtractionApi: SourceExtractionApi = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
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
        every { contentResolver.openInputStream(any()) } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        mockkStatic(ContextCompat::class)
        mockkStatic(Uri::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { Uri.parse(any()) } returns parsedUri
        coEvery { rawIngestionRepository.uploadBatch(any()) } returns BecalmResult.Success(
            BatchUploadResponse(acknowledged = 1, failed = emptyList()),
        )
        coEvery { rawIngestionRepository.markSynced(any()) } returns BecalmResult.Success(Unit)
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
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(true)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        stubPreparedAudioExtraction(
            Response.error(
                502,
                """{"error":"output_truncated","message":"too long"}""".toResponseBody("application/json".toMediaType()),
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 1) {
            processingStatusRepository.recordGemini(SourceType.VOICE, "내용 정리 중")
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
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        val updatedSlot = slot<RawIngestionEventEntity>()
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        coEvery { rawIngestionEventDao.update(capture(updatedSlot)) } returns 1
        stubPreparedAudioExtraction(
            Response.success(
                SourceExtractionResponse(
                    rawEventId = "raw-1",
                    items = emptyList(),
                    sourceEventParticipants = emptyList(),
                    model = "gemini-2.5-flash",
                    region = "us-central1",
                    rawModelText = """{"items":[],"source_event_participants":[]}""",
                ),
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(0, updatedSlot.captured.commitmentsExtractedCount)
        assertEquals("pending", updatedSlot.captured.syncStatus)
        assertEquals(false, updatedSlot.captured.lastAttemptAt == null)
        verify(exactly = 1) { workScheduler.enqueueUpload() }
        coVerify(exactly = 1) {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = "raw-1",
                status = MeetingSpeakerPreviewStatus.DONE,
                lastError = null,
                updatedAt = any(),
            )
        }
        coVerify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `terminal extraction failure marks meeting speaker preview failed and clears active processing`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.MEETING,
            sourceRef = "content://voice/raw-1",
            eventTitle = "회의 녹음",
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "pending",
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        stubPreparedAudioExtraction(
            Response.error(
                422,
                """{"error":"invalid_audio","message":"invalid"}""".toResponseBody("application/json".toMediaType()),
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 1) {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = "raw-1",
                status = MeetingSpeakerPreviewStatus.FAILED,
                lastError = "non_retryable_http_422",
                updatedAt = any(),
            )
        }
        coVerify(atLeast = 1) {
            processingStatusRepository.recordError(SourceType.MEETING, "non_retryable_http_422")
        }
    }

    @Test
    fun `VOI-007 uploads wav evidence with wav multipart metadata`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.MEETING,
            sourceRef = "file:///data/data/com.becalm.android/files/qa/clova/meeting.wav",
            eventTitle = "회의 녹음",
            durationSeconds = 90,
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "pending",
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        val audioPart = slot<MultipartBody.Part>()
        val prepareSlot = slot<ExtractionUploadPrepareRequest>()
        every { parsedUri.lastPathSegment } returns "/data/data/com.becalm.android/files/qa/clova/meeting.wav"
        every { contentResolver.getType(parsedUri) } returns null
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        stubPreparedAudioExtraction(
            response = Response.success(
                SourceExtractionResponse(
                    rawEventId = "raw-1",
                    items = emptyList(),
                    sourceEventParticipants = emptyList(),
                    model = "clova-speech+gemini-2.5-flash",
                    region = "us-central1",
                    rawModelText = """{"items":[],"source_event_participants":[]}""",
                ),
            ),
            prepareSlot = prepareSlot,
            uploadPartSlot = audioPart,
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals("audio/wav", prepareSlot.captured.contentType)
        assertEquals("audio/wav", audioPart.captured.body.contentType().toString())
        assertEquals(
            "form-data; name=\"file\"; filename=\"meeting.wav\"",
            audioPart.captured.headers?.get("Content-Disposition"),
        )
        val firstWrite = Buffer()
        val secondWrite = Buffer()
        audioPart.captured.body.writeTo(firstWrite)
        audioPart.captured.body.writeTo(secondWrite)
        assertEquals(3L, firstWrite.size)
        assertEquals(3L, secondWrite.size)
    }

    @Test
    fun `long audio 202 response re-enqueues polling work instead of re-upload retry`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.MEETING,
            sourceRef = "content://voice/raw-1",
            eventTitle = "긴 회의 녹음",
            durationSeconds = 600,
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "pending",
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        stubPreparedAudioExtraction(
            Response.success(
                202,
                SourceExtractionResponse(
                    rawEventId = "raw-1",
                    items = emptyList(),
                    sourceEventParticipants = emptyList(),
                    model = "pending",
                    region = "pending",
                    rawModelText = null,
                    jobId = "job-1",
                    status = "pending",
                    retryAfterSeconds = 12,
                ),
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        verify(exactly = 1) {
            workScheduler.enqueueVoiceUploadWithDelay(
                rawEventId = "raw-1",
                audioUri = "content://voice/raw-1",
                initialDelaySec = 12,
                rateLimitedAttempt = 0,
                selfSpeakerId = null,
                speakerMappingsJson = null,
                speakerPreviewId = null,
                extractionJobId = "job-1",
                extractionJobPollAttempt = 1,
            )
        }
    }

    @Test
    fun `LLM daily budget 429 records blocked processing status before delayed retry`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.MEETING,
            sourceRef = "content://voice/raw-1",
            eventTitle = "긴 회의 녹음",
            durationSeconds = 600,
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "pending",
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        stubPreparedAudioExtraction(
            Response.error(
                429,
                """
                    {"error":"llm_daily_budget_exceeded","message":"Daily model budget exceeded"}
                """.trimIndent().toResponseBody("application/json".toMediaType()),
            ),
        )

        val result = buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 1) {
            processingStatusRepository.recordBlocked(
                SourceType.MEETING,
                ProcessingStatusMessages.LLM_DAILY_BUDGET_EXCEEDED,
            )
        }
        verify(exactly = 1) {
            workScheduler.enqueueVoiceUploadWithDelay(
                rawEventId = "raw-1",
                audioUri = "content://voice/raw-1",
                initialDelaySec = any(),
                rateLimitedAttempt = 1,
                selfSpeakerId = null,
                speakerMappingsJson = null,
                speakerPreviewId = null,
                extractionJobId = null,
                extractionJobPollAttempt = 0,
            )
        }
    }

    @Test
    fun `polling accepted audio job persists succeeded extraction without re-uploading audio`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.MEETING,
            sourceRef = "content://voice/raw-1",
            eventTitle = "긴 회의 녹음",
            durationSeconds = 600,
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "synced",
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        coEvery { rawIngestionEventDao.findById("raw-1", "user-1") } returns entity
        coEvery { sourceExtractionApi.commitmentExtractionJob("job-1") } returns Response.success(
            SourceExtractionResponse(
                rawEventId = "raw-1",
                items = emptyList(),
                sourceEventParticipants = emptyList(),
                model = "clova-speech+gemini-2.5-flash",
                region = "us-central1",
                rawModelText = """{"items":[],"source_event_participants":[]}""",
                jobId = "job-1",
                status = "succeeded",
                retryAfterSeconds = 10,
            ),
        )

        val inputData = Data.Builder()
            .putString(VoiceUploadWorker.KEY_RAW_EVENT_ID, "raw-1")
            .putString(VoiceUploadWorker.KEY_AUDIO_URI, "content://voice/raw-1")
            .putString(VoiceUploadWorker.KEY_EXTRACTION_JOB_ID, "job-1")
            .putInt(VoiceUploadWorker.KEY_EXTRACTION_JOB_POLL_ATTEMPT, 1)
            .build()
        val result = buildWorker(inputData = inputData).doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 1) { sourceExtractionApi.commitmentExtractionJob("job-1") }
        coVerify(exactly = 0) {
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
                any(),
                any(),
                any(),
            )
        }
        verify(exactly = 0) {
            workScheduler.enqueueVoiceUploadWithDelay(
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
        }
    }

    @Test
    fun `speaker preview cache miss restarts speaker preview instead of generic 503 retry`() = runTest {
        val entity = RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = SourceType.MEETING,
            sourceRef = "content://voice/raw-1",
            eventTitle = "회의 녹음",
            durationSeconds = 600,
            timestamp = Instant.parse("2026-04-23T00:00:00Z"),
            syncStatus = "synced",
            processingConfirmedAt = Instant.parse("2026-04-23T00:00:00Z"),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeNotificationsEnabled() } returns flowOf(false)
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
                any(),
                any(),
                any(),
                processingConfirmed = any(),
            )
        } returns Response.error(
            503,
            """
                {
                  "error":"speaker_preview_unavailable",
                  "message":"restart speaker preview",
                  "client_action":"restart_meeting_speaker_preview"
                }
            """.trimIndent().toResponseBody("application/json".toMediaType()),
        )

        val inputData = Data.Builder()
            .putString(VoiceUploadWorker.KEY_RAW_EVENT_ID, "raw-1")
            .putString(VoiceUploadWorker.KEY_AUDIO_URI, "content://voice/raw-1")
            .putString(VoiceUploadWorker.KEY_SPEAKER_PREVIEW_ID, "preview-1")
            .putString(VoiceUploadWorker.KEY_SELF_SPEAKER_ID, "SPEAKER_01")
            .build()
        val result = buildWorker(inputData = inputData).doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        coVerify(exactly = 1) {
            rawIngestionEventDao.updateSyncStatus(
                id = "raw-1",
                status = MeetingSpeakerPreviewStatus.PENDING,
                now = any(),
                lastError = SourceExtractionErrorEnvelope.SPEAKER_PREVIEW_UNAVAILABLE,
            )
        }
        coVerify(exactly = 1) {
            meetingSpeakerPreviewDao.markStatus(
                rawEventId = "raw-1",
                status = MeetingSpeakerPreviewStatus.PENDING,
                lastError = SourceExtractionErrorEnvelope.SPEAKER_PREVIEW_UNAVAILABLE,
                updatedAt = any(),
            )
        }
        coVerify(exactly = 1) {
            processingStatusRepository.recordGemini(SourceType.MEETING, "화자 확인을 다시 준비 중")
        }
        verify(exactly = 1) {
            workScheduler.enqueueMeetingSpeakerPreview(
                rawEventId = "raw-1",
                audioUri = "content://voice/raw-1",
            )
        }
        verify(exactly = 0) {
            workScheduler.enqueueVoiceUploadWithDelay(
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
        }
    }

    private fun stubPreparedAudioExtraction(
        response: Response<SourceExtractionResponse>,
        prepareSlot: io.mockk.CapturingSlot<ExtractionUploadPrepareRequest>? = null,
        uploadPartSlot: io.mockk.CapturingSlot<MultipartBody.Part>? = null,
    ) {
        if (prepareSlot == null) {
            coEvery { sourceExtractionApi.prepareCommitmentExtractionUpload(any()) } answers {
                preparedAudioUploadResponse(firstArg<ExtractionUploadPrepareRequest>())
            }
        } else {
            coEvery { sourceExtractionApi.prepareCommitmentExtractionUpload(capture(prepareSlot)) } answers {
                preparedAudioUploadResponse(firstArg<ExtractionUploadPrepareRequest>())
            }
        }
        if (uploadPartSlot == null) {
            coEvery { sourceExtractionApi.uploadExtractionMediaToSignedUrl(any(), any()) } returns
                Response.success("{}".toResponseBody("application/json".toMediaType()))
        } else {
            coEvery { sourceExtractionApi.uploadExtractionMediaToSignedUrl(any(), capture(uploadPartSlot)) } returns
                Response.success("{}".toResponseBody("application/json".toMediaType()))
        }
        coEvery { sourceExtractionApi.createCommitmentExtractionJob(any()) } returns response
    }

    private fun preparedAudioUploadResponse(
        request: ExtractionUploadPrepareRequest,
    ): Response<ExtractionUploadPrepareResponse> {
        val contentType = request.contentType
        return Response.success(
            ExtractionUploadPrepareResponse(
                rawEventId = request.rawEventId,
                jobId = "job-1",
                bucket = "extraction-jobs",
                path = "user-1/job-1/audio.m4a",
                contentType = contentType,
                mediaKind = "audio",
                signedUploadUrl = "https://storage.example/upload/sign/extraction-jobs/path?token=signed-token",
                uploadToken = "signed-token",
                uploadContentType = contentType,
                storageRef = ExtractionStorageRefDto(
                    bucket = "extraction-jobs",
                    path = "user-1/job-1/audio.m4a",
                    contentType = contentType,
                    rawEventId = request.rawEventId,
                    mediaKind = "audio",
                ),
            ),
        )
    }

    private fun buildWorker(inputData: Data = defaultInputData()): VoiceUploadWorker = VoiceUploadWorker(
        appContext = appContext,
        workerParams = workerParams(inputData),
        rawIngestionEventDao = rawIngestionEventDao,
        commitmentDao = commitmentDao,
        commitmentProgressEventDao = commitmentProgressEventDao,
        personIndexDao = personIndexDao,
        meetingSpeakerPreviewDao = meetingSpeakerPreviewDao,
        selfIdentityAnchorDao = selfIdentityAnchorDao,
        sourceExtractionApi = sourceExtractionApi,
        rawIngestionRepository = rawIngestionRepository,
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

    private fun workerParams(inputData: Data): WorkerParameters = mockk<WorkerParameters>().also { params ->
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

    private fun defaultInputData(): Data = Data.Builder()
        .putString(VoiceUploadWorker.KEY_RAW_EVENT_ID, "raw-1")
        .putString(VoiceUploadWorker.KEY_AUDIO_URI, "content://voice/raw-1")
        .build()
}
