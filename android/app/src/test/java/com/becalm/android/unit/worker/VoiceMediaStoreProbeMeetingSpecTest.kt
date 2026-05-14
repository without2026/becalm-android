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
    fun `MTG-007 meeting audio scanner inserts meeting raw event and enqueues upload`() = runTest {
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
            addRow(arrayOf(42L, 1_777_766_400L, 120_000L, "1777766400000-standup.m4a", "Recordings/BeCalm Meetings/Audio/"))
        }
        coEvery { rawIngestionEventDao.findByClientEventId("user-1", any()) } returns null
        val inserted = slot<RawIngestionEventEntity>()
        coEvery { rawIngestionEventDao.insert(capture(inserted)) } returns 1L

        val outcome = buildProbe().ingestMeetingAudio(Instant.parse("2026-05-03T00:00:00Z"))

        assertEquals(SourceType.MEETING, inserted.captured.sourceType)
        assertEquals("1777766400000-standup.m4a", inserted.captured.eventTitle)
        assertEquals("pending", inserted.captured.syncStatus)
        assertEquals(1, (outcome as com.becalm.android.worker.ingestion.MeetingIngestOutcome.Success).insertedCount)
        coVerify(exactly = 1) {
            syncCursorStore.setMediaStoreLastSeen(MediaStoreWorker.KIND_MEETING, 1_777_766_400_000L)
        }
        coVerify(exactly = 1) { workScheduler.enqueueVoiceUpload(inserted.captured.id, any()) }
    }

    private fun stubCommon() {
        every { appContext.contentResolver } returns contentResolver
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
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
