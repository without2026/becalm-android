package com.becalm.android.worker

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.VoiceApi
import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.becalm.android.data.remote.dto.TranscribeExtractResponse
import com.becalm.android.data.repository.SourceStatusRepository
import com.squareup.moshi.Moshi
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/**
 * Unit tests for [VoiceUploadWorker].
 *
 * Uses Robolectric so that [android.content.Context] / [android.net.Uri] APIs resolve
 * without a full device. All I/O dependencies are MockK mocks.
 *
 * Test cases:
 * 1. Happy path — HTTP 200 with 2 commitments: verify 2 CommitmentEntity inserts, entity
 *    count update, and event_snippet population.
 * 2. Missing READ_MEDIA_AUDIO permission → [Result.failure].
 * 3. PIPA consent false → sync_status='awaiting_consent', no API call.
 * 4. HTTP 413 → sync_status='failed', no retry ([Result.success]).
 * 5. HTTP 503 with runAttemptCount=1 → [Result.retry].
 * 6. HTTP 503 with runAttemptCount=2 (3rd execution, zero-based) → sync_status='failed', [Result.success].
 *
 * Spec refs: VOI-001, VOI-004, VOI-005, VOI-006, VOI-007.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class VoiceUploadWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk()
    private val commitmentDao: CommitmentDao = mockk()
    private val voiceApi: VoiceApi = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var worker: VoiceUploadWorker

    private val fakeEntity = RawIngestionEventEntity(
        id = "event-uuid-123",
        userId = "user-uuid-456",
        clientEventId = "client-uuid-789",
        sourceType = "voice",
        sourceRef = "content://media/voice/001",
        personRef = null,
        eventTitle = "Meeting recording",
        eventSnippet = null,
        durationSeconds = 300,
        location = null,
        commitmentsExtractedCount = 0,
        timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000),
        syncStatus = "pending",
    )

    private val fakeAudioUri = "content://com.samsung.android.app.voicerecorder/audio/001"

    private val fakeCommitmentDtoGive = CommitmentDraftDto(
        direction = "give",
        text = "I will send the report by Friday",
        quote = "OK I'll send you the report by Friday",
        personRef = "kim@example.com",
        dueAt = null,
        confidence = 0.92f,
    )

    private val fakeCommitmentDtoTake = CommitmentDraftDto(
        direction = "take",
        text = "Kim will prepare the slides",
        quote = "I'll prepare the slides for the presentation",
        personRef = "kim@example.com",
        dueAt = null,
        confidence = 0.85f,
    )

    @Before
    fun setUp() {
        worker = VoiceUploadWorker(
            appContext = context,
            workerParams = workerParams,
            rawIngestionEventDao = rawIngestionEventDao,
            commitmentDao = commitmentDao,
            voiceApi = voiceApi,
            userPrefsStore = userPrefsStore,
            sourceStatusRepository = sourceStatusRepository,
            moshi = Moshi.Builder().build(),
            logger = logger,
        )
        // Default: permission granted
        every {
            context.checkPermission(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                any(),
                any(),
            )
        } returns android.content.pm.PackageManager.PERMISSION_GRANTED

        // Default: inputs present
        every { workerParams.inputData.getString(VoiceUploadWorker.KEY_RAW_EVENT_ID) } returns fakeEntity.id
        every { workerParams.inputData.getString(VoiceUploadWorker.KEY_AUDIO_URI) } returns fakeAudioUri
        every { workerParams.runAttemptCount } returns 0

        // Default: entity found
        coEvery { rawIngestionEventDao.findById(fakeEntity.id) } returns fakeEntity
        coEvery { rawIngestionEventDao.update(any()) } returns 1
        coEvery { commitmentDao.insertAll(any()) } returns listOf(1L, 2L)

        // Default: PIPA consent granted
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)

        // Default: content resolver returns a stream of zeros (audio content)
        every { context.contentResolver } returns mockk(relaxed = true) {
            every { openInputStream(any()) } returns "fake audio bytes".byteInputStream()
        }
    }

    // ── T1: Happy path — 200 with 2 commitments ──────────────────────────────

    @Test
    fun `doWork inserts 2 commitments and updates entity on HTTP 200`() = runTest {
        val responseBody = TranscribeExtractResponse(
            rawEventId = fakeEntity.id,
            commitments = listOf(fakeCommitmentDtoGive, fakeCommitmentDtoTake),
            model = "gemini-2.5-flash",
            region = "asia-northeast3",
        )
        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(responseBody)

        val insertedEntities = slot<List<CommitmentEntity>>()
        coEvery { commitmentDao.insertAll(capture(insertedEntities)) } returns listOf(1L, 2L)

        val updatedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedEntity)) } returns 1

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // Verify 2 entities were inserted
        assertEquals(2, insertedEntities.captured.size)
        assertEquals("give", insertedEntities.captured[0].direction)
        assertEquals("take", insertedEntities.captured[1].direction)

        // Verify entity commitments count updated
        assertEquals(2, updatedEntity.captured.commitmentsExtractedCount)

        // Verify event_snippet = first commitment's quote (truncated to 200 chars)
        val expectedSnippet = fakeCommitmentDtoGive.quote.take(200)
        assertEquals(expectedSnippet, updatedEntity.captured.eventSnippet)

        // Verify sync_status stays 'pending' for batch mirror pipeline
        assertEquals("pending", updatedEntity.captured.syncStatus)
    }

    // ── T2: Permission missing → Result.failure ──────────────────────────────

    @Test
    fun `doWork returns failure when READ_MEDIA_AUDIO permission not granted`() = runTest {
        every {
            context.checkPermission(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                any(),
                any(),
            )
        } returns android.content.pm.PackageManager.PERMISSION_DENIED

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── T3: PIPA consent false → awaiting_consent, no API call ──────────────

    @Test
    fun `doWork parks event as awaiting_consent when PIPA consent is false`() = runTest {
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(false)

        val updatedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedEntity)) } returns 1

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals("awaiting_consent", updatedEntity.captured.syncStatus)
        coVerify(exactly = 0) { voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── T4: HTTP 413 → sync_status=failed, no retry ──────────────────────────

    @Test
    fun `doWork marks failed and returns success on HTTP 413`() = runTest {
        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns Response.error(
            413,
            """{"error":"body_too_large","message":"exceeds 60 MiB"}"""
                .toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val updatedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedEntity)) } returns 1

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals("failed", updatedEntity.captured.syncStatus)
    }

    // ── T5: HTTP 503 with runAttemptCount=1 → Result.retry ───────────────────

    @Test
    fun `doWork returns retry on HTTP 503 when runAttemptCount is 1`() = runTest {
        every { workerParams.runAttemptCount } returns 1

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns Response.error(
            503,
            """{"error":"service_unavailable"}"""
                .toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
    }

    // ── T6: HTTP 503 on 3rd execution (runAttemptCount=2, zero-based) → failed, Result.success ─

    @Test
    fun `doWork marks failed and returns success on HTTP 503 after exhausting retries`() = runTest {
        // runAttemptCount=2 means this is the 3rd execution (0-indexed); MAX_ATTEMPTS=3 ⇒ quarantine.
        every { workerParams.runAttemptCount } returns 2

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns Response.error(
            503,
            """{"error":"service_unavailable"}"""
                .toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val updatedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedEntity)) } returns 1

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals("failed", updatedEntity.captured.syncStatus)
    }
}
