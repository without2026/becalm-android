package com.becalm.android.worker.ingestion

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.os.Build
import android.provider.MediaStore
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [MediaStoreWorker] (ING-003 voice; VOI-001/005/007).
 *
 * The worker is driven only by [doWork] — no internal methods are exercised directly per
 * the brief. To avoid mock fragility we use [MatrixCursor] for ContentResolver returns
 * rather than mocking the cursor itself, mirroring the pattern used in EnrichmentWorkerTest.
 *
 * FINDING: this worker uses [kotlinx.datetime.Clock.System.now] directly rather than the
 * injected [com.becalm.android.core.util.Clock]. As a result the [FakeClock] cannot drive
 * its time. We assert behaviour, not exact timestamps.
 *
 * Test cases:
 * 1. Audio permission missing → [Result.retry] (no work done).
 * 2. Voice happy path — fresh insert produces deterministic clientEventId, advances cursor,
 *    enqueues [WorkScheduler.enqueueVoiceUpload].
 * 3. Voice no-userId — skip without advancing cursor.
 * 4. Voice DAO insert failure — cursor frozen so failed row is retried next run.
 * 5. Voice dedup — UNIQUE collision with existing pending row reuses entity id and enqueues
 *    upload with `wasFresh=false`.
 * 6. Voice DedupSkip — UNIQUE collision but existing row already has commitments → no enqueue.
 *
 * Spec refs: ING-003, VOI-001, VOI-005, VOI-007.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MediaStoreWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk()
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)

    private lateinit var worker: MediaStoreWorker

    private val fakeUserId = "user-uuid-mediastore-1"

    @Before
    fun setUp() {
        worker = MediaStoreWorker(
            appContext = context,
            workerParams = workerParams,
            syncCursorStore = syncCursorStore,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionEventDao = rawIngestionEventDao,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )

        every { workerParams.runAttemptCount } returns 0
        every { context.contentResolver } returns contentResolver

        // Default: audio permission GRANTED via ContextCompat → context.checkPermission
        every {
            context.checkPermission(
                android.Manifest.permission.READ_MEDIA_AUDIO,
                any(),
                any(),
            )
        } returns PackageManager.PERMISSION_GRANTED

        // Default: stored watermark is null (cold start)
        every { syncCursorStore.observeMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE) } returns
            flowOf(null)

        // Default: signed-in userId
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(fakeUserId)

        // Default: PIPA third-party provision consent GRANTED so existing happy-path tests
        // continue to see syncStatus="pending". Consent-off scenarios override explicitly.
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)

        // Default: voice query returns an empty cursor
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any(),
            )
        } returns voiceEmptyCursor()
    }

    private fun voiceEmptyCursor(): MatrixCursor =
        MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
        )

    private fun voiceCursorWith(
        mediaId: Long,
        dateAddedSec: Long,
        durationMs: Long = 30_000L,
        displayName: String = "rec.m4a",
        relativePath: String = "VoiceRecorder/",
    ): MatrixCursor = MatrixCursor(
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
        ),
    ).apply {
        addRow(arrayOf<Any?>(mediaId, dateAddedSec, durationMs, displayName, relativePath))
    }

    // ── T1: Audio permission missing → Result.retry ──────────────────────────

    @Test
    fun `doWork returns retry when audio permission missing`() = runTest {
        every {
            context.checkPermission(android.Manifest.permission.READ_MEDIA_AUDIO, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        coVerify(exactly = 0) { contentResolver.query(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { rawIngestionEventDao.insert(any()) }
    }

    // ── T2: Voice happy path — fresh insert + cursor + enqueueVoiceUpload ────

    @Test
    fun `doWork inserts voice row and enqueues upload on fresh insert`() = runTest {
        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 42L, dateAddedSec = 1_700_000_000L)

        // Fresh insert: DAO returns rowId != -1L
        val capturedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(capturedEntity)) } returns 1L

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // Deterministic clientEventId per VOI-001 idempotency
        assertEquals("mediastore:voice:42", capturedEntity.captured.clientEventId)
        assertEquals(SourceType.VOICE, capturedEntity.captured.sourceType)
        assertEquals(fakeUserId, capturedEntity.captured.userId)
        assertEquals("pending", capturedEntity.captured.syncStatus)
        // Cursor advanced to dateAddedSec * 1000 (epoch ms)
        coVerify {
            syncCursorStore.setMediaStoreLastSeen(
                MediaStoreWorker.KIND_VOICE,
                1_700_000_000_000L,
            )
        }
        // Enqueue must use the entity's audioUri (sourceRef) and the same id
        coVerify {
            workScheduler.enqueueVoiceUpload(capturedEntity.captured.id, capturedEntity.captured.sourceRef!!)
        }
    }

    // ── T3: Voice — userId null → skip + cursor frozen ───────────────────────

    @Test
    fun `doWork skips voice ingestion when no signed-in userId`() = runTest {
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

        // Even if a row was discoverable, the userId guard fires first.
        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 99L, dateAddedSec = 1_700_000_000L)

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { rawIngestionEventDao.insert(any()) }
        coVerify(exactly = 0) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, any())
        }
        coVerify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any()) }
    }

    // ── T4: Voice DAO insert failure → cursor frozen, no enqueue ─────────────

    @Test
    fun `doWork freezes voice cursor when DAO insert throws`() = runTest {
        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 7L, dateAddedSec = 1_700_000_007L)

        coEvery { rawIngestionEventDao.insert(any()) } throws RuntimeException("disk full")

        val result = worker.doWork()

        // doWork still returns success — bad rows are logged, the worker doesn't fail the cycle.
        assertEquals(Result.success(), result)
        // Cursor MUST NOT advance, so the row is retried on the next run.
        coVerify(exactly = 0) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, any())
        }
        // No enqueue because the insert failed.
        coVerify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any()) }
    }

    // ── T5: Voice dedup — existing pending row → re-enqueue with wasFresh=false ─

    @Test
    fun `doWork enqueues upload for existing pending row on UNIQUE collision`() = runTest {
        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 200L, dateAddedSec = 1_700_000_200L)

        // UNIQUE collision: insert returns -1L
        coEvery { rawIngestionEventDao.insert(any()) } returns -1L

        // Existing row is pending and has not extracted commitments yet → re-enqueue
        val existing = RawIngestionEventEntity(
            id = "existing-id-200",
            userId = fakeUserId,
            clientEventId = "mediastore:voice:200",
            sourceType = SourceType.VOICE,
            sourceRef = "content://media/external/audio/media/200",
            syncStatus = "pending",
            commitmentsExtractedCount = 0,
            timestamp = kotlinx.datetime.Instant.fromEpochSeconds(1_700_000_200L),
        )
        coEvery {
            rawIngestionEventDao.findByClientEventId(fakeUserId, "mediastore:voice:200")
        } returns existing

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // The EXISTING entity id is forwarded to the upload pipeline (NOT a new UUID).
        coVerify { workScheduler.enqueueVoiceUpload("existing-id-200", any()) }
        // Cursor still advances because dedup is treated as success.
        coVerify {
            syncCursorStore.setMediaStoreLastSeen(
                MediaStoreWorker.KIND_VOICE,
                1_700_000_200_000L,
            )
        }
    }

    // ── T6: Voice DedupSkip — already-uploaded row, no re-enqueue ────────────

    @Test
    fun `doWork skips enqueue when existing row already has commitments`() = runTest {
        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 300L, dateAddedSec = 1_700_000_300L)

        coEvery { rawIngestionEventDao.insert(any()) } returns -1L

        // Existing row has commitments extracted (commitmentsExtractedCount > 0) → skip
        val existing = RawIngestionEventEntity(
            id = "existing-id-300",
            userId = fakeUserId,
            clientEventId = "mediastore:voice:300",
            sourceType = SourceType.VOICE,
            sourceRef = "content://media/external/audio/media/300",
            syncStatus = "pending",
            commitmentsExtractedCount = 2,
            timestamp = kotlinx.datetime.Instant.fromEpochSeconds(1_700_000_300L),
        )
        coEvery {
            rawIngestionEventDao.findByClientEventId(fakeUserId, "mediastore:voice:300")
        } returns existing

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // No enqueue because the row already finished extraction.
        coVerify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any()) }
        // Cursor frozen: DedupSkip does NOT advance maxDateAddedMs (only Fresh / Dedup do).
        coVerify(exactly = 0) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, any())
        }
    }

    // ── T8: PIPA consent=false → syncStatus='awaiting_consent' at insert time ──
    //
    // cold-sync.spec:49 requires sync_status='awaiting_consent' when
    // pipa_third_party_consent=false at insertion time (VOI-004). This closes the
    // race window between a "pending" insert and VoiceUploadWorker's 2nd gate.

    @Test
    fun `doWork inserts awaiting_consent when pipa consent is false`() = runTest {
        every {
            context.checkPermission(android.Manifest.permission.READ_SMS, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(false)

        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 501L, dateAddedSec = 1_700_000_501L)

        val capturedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(capturedEntity)) } returns 1L

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals("awaiting_consent", capturedEntity.captured.syncStatus)
        assertEquals(SourceType.VOICE, capturedEntity.captured.sourceType)
        // Enqueue still fires — VoiceUploadWorker owns the 2nd-defense gate and will
        // skip awaiting_consent rows there. Insertion-time gate is the 1st defense.
        coVerify {
            workScheduler.enqueueVoiceUpload(capturedEntity.captured.id, capturedEntity.captured.sourceRef!!)
        }
    }

    // ── T9: PIPA consent=true → syncStatus='pending' (existing default path) ───

    @Test
    fun `doWork inserts pending when pipa consent is true`() = runTest {
        every {
            context.checkPermission(android.Manifest.permission.READ_SMS, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)

        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns voiceCursorWith(mediaId = 502L, dateAddedSec = 1_700_000_502L)

        val capturedEntity = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(capturedEntity)) } returns 1L

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals("pending", capturedEntity.captured.syncStatus)
    }

    // ── T10: Batch snapshots PIPA once — mid-batch toggle is ignored ───────────
    //
    // observeThirdPartyProvisionConsent() must be called EXACTLY ONCE per batch
    // (via `.first()`), so multi-row batches apply a consistent status and are
    // race-free against Settings toggles landing mid-scan.

    @Test
    fun `batch uses snapshot consent value across multiple rows`() = runTest {
        every {
            context.checkPermission(android.Manifest.permission.READ_SMS, any(), any())
        } returns PackageManager.PERMISSION_DENIED

        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(false)

        // Two rows in a single cursor → single batch.
        val twoRowCursor = MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
        ).apply {
            addRow(arrayOf<Any?>(601L, 1_700_000_601L, 30_000L, "a.m4a", "VoiceRecorder/"))
            addRow(arrayOf<Any?>(602L, 1_700_000_602L, 30_000L, "b.m4a", "VoiceRecorder/"))
        }
        every {
            contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, any(), any(), any(), any())
        } returns twoRowCursor

        val captured = mutableListOf<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(captured)) } returns 1L

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // Both rows saw the same snapshotted consent=false → both awaiting_consent.
        assertEquals(2, captured.size)
        assertEquals("awaiting_consent", captured[0].syncStatus)
        assertEquals("awaiting_consent", captured[1].syncStatus)
        // Flow read exactly once (the `.first()` snapshot). Settings toggles that
        // might fire between row 1 and row 2 do not re-enter the Flow.
        verify(exactly = 1) { userPrefsStore.observeThirdPartyProvisionConsent() }
    }
}
