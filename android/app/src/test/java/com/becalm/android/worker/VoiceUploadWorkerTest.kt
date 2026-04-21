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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
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
            workScheduler = workScheduler,
            moshi = Moshi.Builder().build(),
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
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
        every { workerParams.inputData.getInt(VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT, 0) } returns 0
        every { workerParams.runAttemptCount } returns 0

        // Default: current user session present
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(fakeEntity.userId)

        // Default: entity found (scoped to current user — fix for cross-user leak)
        coEvery {
            rawIngestionEventDao.findById(id = fakeEntity.id, userId = fakeEntity.userId)
        } returns fakeEntity
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

    // ── T7: HTTP 429 with Retry-After=60 → re-enqueue with delay ≥ 60s ───────

    @Test
    fun `doWork honors Retry-After on HTTP 429 and re-enqueues via WorkScheduler`() = runTest {
        every { workerParams.runAttemptCount } returns 0
        every { workerParams.inputData.getInt(VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT, 0) } returns 0

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns error429(retryAfter = "60")

        val delaySlot = slot<Long>()
        val attemptSlot = slot<Int>()
        every {
            workScheduler.enqueueVoiceUploadWithDelay(
                eq(fakeEntity.id),
                eq(fakeAudioUri),
                capture(delaySlot),
                capture(attemptSlot),
            )
        } just Runs

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // Retry-After wins in UploadBackoff.nextDelaySeconds(..., retryAfterSec=60) → exactly 60s.
        assertEquals(60L, delaySlot.captured)
        // First 429: counter was 0, re-enqueue carries 1.
        assertEquals(1, attemptSlot.captured)
        coVerify(exactly = 0) { rawIngestionEventDao.update(any()) }
    }

    // ── T8: HTTP 429 without Retry-After → exponential fallback ──────────────

    @Test
    fun `doWork falls back to UploadBackoff exponential on HTTP 429 without Retry-After`() = runTest {
        every { workerParams.runAttemptCount } returns 0
        every { workerParams.inputData.getInt(VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT, 0) } returns 0

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns error429(retryAfter = null)

        val delaySlot = slot<Long>()
        val attemptSlot = slot<Int>()
        every {
            workScheduler.enqueueVoiceUploadWithDelay(
                eq(fakeEntity.id),
                eq(fakeAudioUri),
                capture(delaySlot),
                capture(attemptSlot),
            )
        } just Runs

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // UploadBackoff: base 30s × 2^(attempt-1) with ±20% jitter, attempt=1 ⇒ 24..36s.
        val d = delaySlot.captured
        assertTrue("delay out of jitter range: $d", d in 24L..36L)
        assertEquals(1, attemptSlot.captured)
    }

    // ── T9: HTTP 429 persistent-counter quarantine — third 429 marks failed ──

    @Test
    fun `429 retry budget survives across re-enqueues — quarantines after MAX_RATE_LIMITED_ATTEMPTS`() = runTest {
        // Each 429 re-enqueue produces a brand-new WorkRequest, so runAttemptCount always
        // starts at 0. The persistent counter (KEY_RATE_LIMITED_ATTEMPT) is what enforces
        // the quarantine. Simulate the *third* execution: prior re-enqueues threaded values
        // 0→1 (first 429), then 1→2 (second 429). On the third execution the input reads
        // 2; nextAttempt=3 ≥ MAX_RATE_LIMITED_ATTEMPTS=3 ⇒ quarantine.
        every { workerParams.runAttemptCount } returns 0
        every { workerParams.inputData.getInt(VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT, 0) } returns 2

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns error429(retryAfter = "60")

        val updatedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.update(capture(updatedEntity)) } returns 1
        coEvery {
            rawIngestionEventDao.markFailed(id = any(), retryIncrement = any(), now = any())
        } just Runs

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify {
            rawIngestionEventDao.markFailed(
                id = eq(fakeEntity.id),
                retryIncrement = eq(1),
                now = any(),
            )
        }
        coVerify(exactly = 0) {
            workScheduler.enqueueVoiceUploadWithDelay(any(), any(), any(), any())
        }
    }

    // ── T9b: 2nd 429 (counter=1) still re-enqueues with counter=2 ────────────

    @Test
    fun `second 429 re-enqueues with incremented persistent counter`() = runTest {
        every { workerParams.runAttemptCount } returns 0
        every { workerParams.inputData.getInt(VoiceUploadWorker.KEY_RATE_LIMITED_ATTEMPT, 0) } returns 1

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns error429(retryAfter = "60")

        val attemptSlot = slot<Int>()
        every {
            workScheduler.enqueueVoiceUploadWithDelay(
                eq(fakeEntity.id),
                eq(fakeAudioUri),
                any(),
                capture(attemptSlot),
            )
        } just Runs

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(2, attemptSlot.captured)
        coVerify(exactly = 0) { rawIngestionEventDao.markFailed(any(), any(), any()) }
    }

    // ── T10: HTTP 500 unchanged — Result.retry via handleFailure (regression) ─

    @Test
    fun `doWork returns retry on HTTP 500 regression guard`() = runTest {
        every { workerParams.runAttemptCount } returns 1

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns Response.error(
            500,
            """{"error":"internal"}"""
                .toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) {
            workScheduler.enqueueVoiceUploadWithDelay(any(), any(), any(), any())
        }
    }

    // ── T11: HTTP 503 unchanged — Result.retry via handleFailure (regression) ─

    @Test
    fun `doWork still returns retry on HTTP 503 regression guard`() = runTest {
        every { workerParams.runAttemptCount } returns 0

        coEvery {
            voiceApi.transcribeExtract(any(), any(), any(), any(), any(), any(), any())
        } returns Response.error(
            503,
            """{"error":"service_unavailable"}"""
                .toResponseBody("application/json".toMediaTypeOrNull()),
        )

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) {
            workScheduler.enqueueVoiceUploadWithDelay(any(), any(), any(), any())
        }
    }

    /**
     * Builds a retrofit2 [Response] wrapping an okhttp3 [okhttp3.Response] that carries a 429
     * status code and an optional `Retry-After` header. Retrofit's `Response.error(code, body)`
     * overload does not preserve headers, so we construct the raw response directly.
     */
    private fun error429(retryAfter: String?): Response<TranscribeExtractResponse> {
        val headers = if (retryAfter == null) Headers.headersOf() else Headers.headersOf("Retry-After", retryAfter)
        val errorBody = """{"error":"rate_limited"}"""
            .toResponseBody("application/json".toMediaTypeOrNull())
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .headers(headers)
            .body(errorBody)
            .build()
        return Response.error(errorBody, raw)
    }
}
