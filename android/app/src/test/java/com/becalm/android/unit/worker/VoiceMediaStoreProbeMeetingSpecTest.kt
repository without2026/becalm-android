package com.becalm.android.unit.worker

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.RawIngestionSyncStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.meeting.MeetingImportFolders
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.ingestion.MediaStoreWorker
import com.becalm.android.worker.ingestion.NoOpCallRecordingPersonMatcher
import com.becalm.android.worker.ingestion.VoiceMediaStoreProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VoiceMediaStoreProbeMeetingSpecTest {
    private val appContext: Context = mockk(relaxed = true)
    private val contentResolver: ContentResolver = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `MTG-007 voice scanner excludes BeCalm Meetings subtree`() = runTest {
        stubCommon()
        val selectionArgs = slot<Array<String>>()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                capture(selectionArgs),
                any(),
            )
        } returns emptyAudioCursor()

        buildProbe().ingestVoiceRecordings(Instant.parse("2026-05-03T00:00:00Z"))

        assertTrue(selectionArgs.captured.contains(MeetingImportFolders.MEETINGS_RELATIVE_PATH_PATTERN))
    }

    @Test
    // spec: ING-001B
    fun `MTG-007 meeting audio scanner inserts meeting raw event awaiting user confirmation`() = runTest {
        stubCommon()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                any(),
                any(),
            )
        } returns MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
        ).apply {
            addRow(arrayOf<Any?>(42L, 1_777_766_400L, 120_000L, "1777766400000-standup.m4a", "Recordings/BeCalm Meetings/Audio/"))
        }
        coEvery { rawIngestionEventDao.findByClientEventId("user-1", any()) } returns null
        val inserted = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(inserted)) } returns 1L

        val outcome = buildProbe().ingestMeetingAudio(Instant.parse("2026-05-03T00:00:00Z"))

        assertEquals(SourceType.MEETING, inserted.captured.sourceType)
        assertEquals("1777766400000-standup.m4a", inserted.captured.eventTitle)
        assertEquals(RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION, inserted.captured.syncStatus)
        assertEquals(1, (outcome as com.becalm.android.worker.ingestion.MeetingIngestOutcome.Success).insertedCount)
        coVerify(exactly = 1) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING, 1_777_766_400_000L)
        }
        coVerify(exactly = 0) { workScheduler.enqueueMeetingSpeakerPreview(any(), any()) }
        coVerify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `meeting audio scanner treats same display name with different file metadata as distinct files`() = runTest {
        stubCommon()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                any(),
                any(),
            )
        } returns MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.SIZE,
            ),
        ).apply {
            addRow(arrayOf<Any?>(42L, 1_777_766_400L, 120_000L, "meeting.m4a", "Recordings/BeCalm Meetings/Audio/", 100_000L))
            addRow(arrayOf<Any?>(43L, 1_777_766_460L, 120_000L, "meeting.m4a", "Recordings/BeCalm Meetings/Audio/", 101_000L))
        }
        coEvery { rawIngestionEventDao.findByClientEventId("user-1", any()) } returns null
        val inserted = mutableListOf<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(inserted)) } returnsMany listOf(1L, 2L)

        val outcome = buildProbe().ingestMeetingAudio(Instant.parse("2026-05-03T00:00:00Z"))

        assertEquals(2, (outcome as com.becalm.android.worker.ingestion.MeetingIngestOutcome.Success).insertedCount)
        assertEquals(2, inserted.map { it.clientEventId }.distinct().size)
        coVerify(exactly = 0) { workScheduler.enqueueMeetingSpeakerPreview(any(), any()) }
    }

    @Test
    fun `voice scanner ignores unsupported Recordings subfolders and advances cursor`() = runTest {
        stubCommon()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                any(),
                any(),
            )
        } returns MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                "is_pending",
            ),
        ).apply {
            addRow(arrayOf<Any?>(44L, 1_777_766_600L, 30_000L, "foreign.m4a", "Recordings/Other Recorder/", 0))
        }

        val outcome = buildProbe().ingestVoiceRecordings(Instant.parse("2026-05-03T00:00:00Z"))

        assertEquals(0, outcome.insertedCount)
        coVerify(exactly = 0) { rawIngestionEventDao.insert(any()) }
        coVerify(exactly = 1) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_VOICE, 1_777_766_600_000L)
        }
    }

    @Test
    fun `meeting audio scanner reports has more after soft batch cap`() = runTest {
        stubCommon()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                any(),
                any(),
            )
        } returns MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.SIZE,
            ),
        ).apply {
            repeat(MediaStoreWorker.MEDIASTORE_SCAN_BATCH_SIZE + 1) { index ->
                addRow(
                    arrayOf<Any?>(
                        1_000L + index,
                        1_777_766_400L + index,
                        120_000L,
                        "meeting-$index.m4a",
                        "Recordings/BeCalm Meetings/Audio/",
                        100_000L + index,
                    ),
                )
            }
        }
        coEvery { rawIngestionEventDao.findByClientEventId("user-1", any()) } returns null
        coEvery { rawIngestionEventDao.insert(any()) } returns 1L

        val outcome = buildProbe().ingestMeetingAudio(Instant.parse("2026-05-03T00:00:00Z"))

        val success = outcome as com.becalm.android.worker.ingestion.MeetingIngestOutcome.Success
        assertEquals(MediaStoreWorker.MEDIASTORE_SCAN_BATCH_SIZE, success.insertedCount)
        assertTrue(success.hasMore)
        coVerify(exactly = MediaStoreWorker.MEDIASTORE_SCAN_BATCH_SIZE) { rawIngestionEventDao.insert(any()) }
        coVerify(exactly = 1) {
            syncCursorStore.setMediaStoreLastSeen(
                MediaStoreWorker.KIND_MEETING,
                (1_777_766_400L + MediaStoreWorker.MEDIASTORE_SCAN_BATCH_SIZE - 1) * 1_000L,
            )
        }
    }

    @Test
    fun `pending MediaStore rows are deferred without advancing source cursor`() = runTest {
        stubCommon()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                any(),
                any(),
            )
        } returns MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.RELATIVE_PATH,
                "is_pending",
            ),
        ).apply {
            addRow(arrayOf<Any?>(45L, 1_777_766_700L, 24_000L, "HS0008.m4a", "HS0008", "Recordings/Call/", 1))
        }

        val outcome = buildProbe().ingestCallRecordings(Instant.parse("2026-05-03T00:00:00Z"))

        assertEquals(0, (outcome as com.becalm.android.worker.ingestion.CallRecordingIngestOutcome.Success).insertedCount)
        coVerify(exactly = 0) { rawIngestionEventDao.insert(any()) }
        coVerify(exactly = 0) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_CALL_RECORDING, any())
        }
    }

    @Test
    fun `call recording scanner inserts call raw event awaiting user confirmation`() = runTest {
        stubCommon()
        every {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                any<Array<String>>(),
                any(),
                any(),
                any(),
            )
        } returns MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
        ).apply {
            addRow(arrayOf<Any?>(43L, 1_777_766_500L, 24_000L, "HS0007.m4a", "HS0007", "Recordings/Call/"))
        }
        coEvery { rawIngestionEventDao.findByClientEventId("user-1", any()) } returns null
        val inserted = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(inserted)) } returns 1L

        val outcome = buildProbe().ingestCallRecordings(Instant.parse("2026-05-03T00:00:00Z"))

        assertEquals(SourceType.CALL_RECORDING, inserted.captured.sourceType)
        assertEquals(RawIngestionSyncStatus.DETECTED_PENDING_CONFIRMATION, inserted.captured.syncStatus)
        assertEquals(1, (outcome as com.becalm.android.worker.ingestion.CallRecordingIngestOutcome.Success).insertedCount)
        coVerify(exactly = 0) { workScheduler.enqueueMeetingSpeakerPreview(any(), any()) }
        coVerify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any(), any(), any(), any()) }
    }

    private fun stubCommon() {
        every { appContext.contentResolver } returns contentResolver
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        every { userPrefsStore.observeCallLogMatchingConsent() } returns flowOf(false)
        every { userPrefsStore.observeSourceEnabledAt(any()) } returns flowOf(null)
        every { syncCursorStore.observeMediaStoreLastSeen(any()) } returns flowOf(null)
    }

    private fun emptyAudioCursor(): MatrixCursor =
        MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            ),
        )

    private fun buildProbe(): VoiceMediaStoreProbe =
        VoiceMediaStoreProbe(
            appContext = appContext,
            syncCursorStore = syncCursorStore,
            sourceStatusRepository = sourceStatusRepository,
            rawIngestionEventDao = rawIngestionEventDao,
            workScheduler = workScheduler,
            userPrefsStore = userPrefsStore,
            callRecordingPersonMatcher = NoOpCallRecordingPersonMatcher,
            logger = logger,
        )
}
