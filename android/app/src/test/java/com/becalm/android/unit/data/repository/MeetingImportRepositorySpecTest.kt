package com.becalm.android.unit.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerAliasDao
import com.becalm.android.data.local.db.dao.MeetingSpeakerPreviewDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.MeetingSpeakerAliasEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewEntity
import com.becalm.android.data.local.db.entity.MeetingSpeakerPreviewStatus
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.MeetingSpeakerReviewContext
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.worker.WorkScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MeetingImportRepositorySpecTest {
    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val commitmentDao: CommitmentDao = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val meetingSpeakerPreviewDao: MeetingSpeakerPreviewDao = mockk(relaxed = true)
    private val meetingSpeakerAliasDao: MeetingSpeakerAliasDao = mockk(relaxed = true)
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    private val sourceUri = Uri.parse("content://picked/standup")
    private val targetUri = Uri.parse("content://tree/root/document/root%2FBeCalm%20Meetings%2FAudio%2Fstandup.m4a")

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns temporaryFolder.newFolder("files")
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { resolver.getType(sourceUri) } returns "audio/m4a"
        every { resolver.openInputStream(sourceUri) } answers { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        every { resolver.query(any(), any<Array<String>>(), null, null, null) } answers {
            when (firstArg<Uri>()) {
                sourceUri -> openableCursor()
                else -> emptyDocumentCursor()
            }
        }
    }

    @After
    fun tearDown() {
        ShadowMediaMetadataRetriever.reset()
    }

    @Test
    // spec: ING-001A
    // spec: MTG-002
    fun `meeting audio import copies into app private storage and enqueues speaker preview when consented`() = runTest {
        val eventSlot = slot<RawIngestionEventEntity>()
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importAudio(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(SourceType.MEETING, eventSlot.captured.sourceType)
        assertAppPrivateAudioRef(eventSlot.captured.sourceRef)
        assertTrue(eventSlot.captured.eventTitle.orEmpty().startsWith("회의 녹음 · "))
        assertEquals("원본 파일: standup.m4a", eventSlot.captured.eventSnippet)
        assertEquals(MeetingSpeakerPreviewStatus.PENDING, eventSlot.captured.syncStatus)
        verify(exactly = 1) { workScheduler.enqueueMeetingSpeakerPreview(eventSlot.captured.id, eventSlot.captured.sourceRef!!) }
        verify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `meeting audio import stores confirmed self speaker as source-event scoped self anchor`() = runTest {
        val eventSlot = slot<RawIngestionEventEntity>()
        val anchorSlot = slot<SelfIdentityAnchorEntity>()
        val aliasSlot = slot<MeetingSpeakerAliasEntity>()
        val reviewContext = MeetingSpeakerReviewContext(
            selfSpeakerId = "SPEAKER_01",
            speakerMappingsJson = """[{"speaker_id":"SPEAKER_01","relation_to_user":"self"}]""",
            speakerPreviewId = "preview-1",
        )
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }
        io.mockk.coEvery { selfIdentityAnchorDao.upsert(capture(anchorSlot)) } returns 1L
        io.mockk.coEvery { meetingSpeakerAliasDao.upsert(capture(aliasSlot)) } returns Unit

        val result = repository().importAudio(sourceUri, reviewContext)

        assertTrue(result is BecalmResult.Success)
        assertEquals("speaker_label", anchorSlot.captured.anchorType)
        assertEquals("speaker_01", anchorSlot.captured.normalizedValue)
        assertEquals("SPEAKER_01", anchorSlot.captured.displayValue)
        assertEquals("meeting_review", anchorSlot.captured.source)
        assertEquals("source_event", anchorSlot.captured.scope)
        assertEquals(eventSlot.captured.id, anchorSlot.captured.sourceEventId)
        assertEquals("user_confirmed", anchorSlot.captured.trust)
        assertEquals("active", anchorSlot.captured.status)
        assertEquals(eventSlot.captured.id, aliasSlot.captured.rawEventId)
        assertEquals("SPEAKER_01", aliasSlot.captured.speakerId)
        assertEquals("나", aliasSlot.captured.displayName)
        verify(exactly = 1) {
            workScheduler.enqueueVoiceUpload(
                eventSlot.captured.id,
                eventSlot.captured.sourceRef!!,
                "SPEAKER_01",
                reviewContext.speakerMappingsJson,
                "preview-1",
            )
        }
    }

    @Test
    fun `meeting speaker review confirmation stores default self alias and queues extraction`() = runTest {
        val rawEvent = RawIngestionEventEntity(
            id = "raw-meeting-2",
            userId = USER_ID,
            clientEventId = "client-raw-meeting-2",
            sourceType = SourceType.MEETING,
            sourceRef = targetUri.toString(),
            eventTitle = "standup.m4a",
            timestamp = Instant.parse("2026-05-19T00:00:00Z"),
        )
        val aliasSlot = slot<MeetingSpeakerAliasEntity>()
        val reviewContext = MeetingSpeakerReviewContext(
            selfSpeakerId = "SPEAKER_02",
            speakerMappingsJson = """[{"speaker_id":"SPEAKER_02","relation_to_user":"self"}]""",
            speakerPreviewId = "preview-2",
        )
        io.mockk.coEvery { rawIngestionEventDao.findById(rawEvent.id, USER_ID) } returns rawEvent
        io.mockk.coEvery { meetingSpeakerPreviewDao.findByRawEventId(rawEvent.id) } returns null
        io.mockk.coEvery { meetingSpeakerAliasDao.upsert(capture(aliasSlot)) } returns Unit

        val result = repository().confirmSpeakerReview(rawEvent.id, reviewContext)

        assertTrue(result is BecalmResult.Success)
        assertEquals(rawEvent.id, aliasSlot.captured.rawEventId)
        assertEquals("SPEAKER_02", aliasSlot.captured.speakerId)
        assertEquals("나", aliasSlot.captured.displayName)
        verify(exactly = 1) {
            workScheduler.enqueueVoiceUpload(
                rawEvent.id,
                targetUri.toString(),
                "SPEAKER_02",
                reviewContext.speakerMappingsJson,
                "preview-2",
            )
        }
    }

    @Test
    fun `expired speaker preview requeues preview instead of final extraction`() = runTest {
        val rawEvent = RawIngestionEventEntity(
            id = "raw-meeting-1",
            userId = USER_ID,
            clientEventId = "client-raw-meeting-1",
            sourceType = SourceType.MEETING,
            sourceRef = targetUri.toString(),
            eventTitle = "standup.m4a",
            timestamp = Instant.parse("2026-05-19T00:00:00Z"),
        )
        val reviewContext = MeetingSpeakerReviewContext(
            selfSpeakerId = "SPEAKER_01",
            speakerMappingsJson = """[{"speaker_id":"SPEAKER_01","relation_to_user":"self"}]""",
            speakerPreviewId = "preview-1",
        )
        io.mockk.coEvery { rawIngestionEventDao.findById(rawEvent.id, USER_ID) } returns rawEvent
        io.mockk.coEvery { meetingSpeakerPreviewDao.findByRawEventId(rawEvent.id) } returns meetingPreview(
            rawEventId = rawEvent.id,
            expiresAt = Instant.parse("2026-05-18T00:00:00Z"),
        )

        val result = repository().confirmSpeakerReview(rawEvent.id, reviewContext)

        assertTrue(result is BecalmResult.Success)
        assertEquals(false, (result as BecalmResult.Success).value.queuedExtraction)
        verify(exactly = 1) { workScheduler.enqueueMeetingSpeakerPreview(rawEvent.id, targetUri.toString()) }
        verify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any(), any(), any(), any()) }
    }

    @Test
    // spec: MTG-006
    fun `meeting audio import parks awaiting consent and does not enqueue upload`() = runTest {
        val eventSlot = slot<RawIngestionEventEntity>()
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(false)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importAudio(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals("awaiting_consent", eventSlot.captured.syncStatus)
        verify(exactly = 0) { workScheduler.enqueueVoiceUpload(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `meeting audio preview staging persists review state and enqueues speaker preview instead of extraction`() =
        runTest {
            val eventSlot = slot<RawIngestionEventEntity>()
            val commitmentSlot = slot<CommitmentEntity>()
            ShadowMediaMetadataRetriever.addMetadata(
                DataSource.toDataSource(context, sourceUri),
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                "61000",
            )
            every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
            io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
                BecalmResult.Success(eventSlot.captured.id)
            }
            io.mockk.coEvery { commitmentDao.insert(capture(commitmentSlot)) } returns 1L

            val result = repository().stageAudioForSpeakerPreview(sourceUri)

            assertTrue(result is BecalmResult.Success)
            assertEquals("meeting_preview_pending", eventSlot.captured.syncStatus)
            assertEquals(61, eventSlot.captured.durationSeconds)
            assertEquals(CommitmentItemType.SCHEDULE, commitmentSlot.captured.itemType)
            assertTrue(commitmentSlot.captured.title.startsWith("회의 녹음 · "))
            assertTrue(commitmentSlot.captured.sourceEventTitle.orEmpty().startsWith("회의 녹음 · "))
            assertEquals(eventSlot.captured.sourceRef, commitmentSlot.captured.sourceRef)
            assertAppPrivateAudioRef(commitmentSlot.captured.sourceRef)
            verify(exactly = 1) {
                workScheduler.enqueueMeetingSpeakerPreview(eventSlot.captured.id, eventSlot.captured.sourceRef!!)
            }
            verify(exactly = 0) {
                workScheduler.enqueueVoiceUpload(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `meeting audio preview uses filename recording date before embedded metadata`() = runTest {
        val fileNameRecordedAt = localInstant(2026, 5, 18, 14, 30, 45)
        val embeddedRecordedAt = Instant.parse("2026-05-19T09:00:00Z")
        val eventSlot = slot<RawIngestionEventEntity>()
        val commitmentSlot = slot<CommitmentEntity>()
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)) },
                null,
                null,
                null,
            )
        } returns openableCursor("20260518_143045_client-sync.m4a")
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, sourceUri),
            MediaMetadataRetriever.METADATA_KEY_DATE,
            "20260519T090000.000Z",
        )
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }
        io.mockk.coEvery { commitmentDao.insert(capture(commitmentSlot)) } returns 1L

        val result = repository().stageAudioForSpeakerPreview(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(fileNameRecordedAt, eventSlot.captured.timestamp)
        assertEquals(fileNameRecordedAt, commitmentSlot.captured.sourceEventOccurredAt)
        assertEquals(fileNameRecordedAt, commitmentSlot.captured.dueAt)
        assertEquals("회의 녹음 · 5월 18일 14:30", eventSlot.captured.eventTitle)
        assertEquals("원본 파일: 20260518_143045_client-sync.m4a", eventSlot.captured.eventSnippet)
        assertTrue(embeddedRecordedAt != eventSlot.captured.timestamp)
    }

    @Test
    fun `meeting audio preview supports galaxy voice recorder two digit filename timestamp`() = runTest {
        val recordedAt = localInstant(2026, 5, 18, 21, 2, 56)
        val eventSlot = slot<RawIngestionEventEntity>()
        val commitmentSlot = slot<CommitmentEntity>()
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)) },
                null,
                null,
                null,
            )
        } returns openableCursor("음성 260518_210256.m4a")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }
        io.mockk.coEvery { commitmentDao.insert(capture(commitmentSlot)) } returns 1L

        val result = repository().stageAudioForSpeakerPreview(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(recordedAt, eventSlot.captured.timestamp)
        assertEquals(recordedAt, commitmentSlot.captured.sourceEventOccurredAt)
        assertEquals(recordedAt, commitmentSlot.captured.dueAt)
    }

    @Test
    fun `meeting audio preview parses filename recording date inside saved or downloaded names`() = runTest {
        val recordedAt = localInstant(2025, 8, 21, 9, 4, 4)
        val eventSlot = slot<RawIngestionEventEntity>()
        val commitmentSlot = slot<CommitmentEntity>()
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)) },
                null,
                null,
                null,
            )
        } returns openableCursor("1779199465826-CarbonBlack Weekly-20250821_090404-모임 녹음녹화.mp3")
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }
        io.mockk.coEvery { commitmentDao.insert(capture(commitmentSlot)) } returns 1L

        val result = repository().stageAudioForSpeakerPreview(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(recordedAt, eventSlot.captured.timestamp)
        assertEquals(recordedAt, commitmentSlot.captured.sourceEventOccurredAt)
        assertEquals(recordedAt, commitmentSlot.captured.dueAt)
    }

    @Test
    fun `meeting audio preview uses embedded recording date before selected audio file timestamp`() = runTest {
        val recordedAt = Instant.parse("2026-05-18T03:15:00Z")
        val downloadedAt = Instant.parse("2026-05-19T09:00:00Z")
        val eventSlot = slot<RawIngestionEventEntity>()
        val commitmentSlot = slot<CommitmentEntity>()
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, sourceUri),
            MediaMetadataRetriever.METADATA_KEY_DATE,
            "20260518T031500.000Z",
        )
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) },
                null,
                null,
                null,
            )
        } returns longColumnCursor(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            downloadedAt.toEpochMilliseconds(),
        )
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }
        io.mockk.coEvery { commitmentDao.insert(capture(commitmentSlot)) } returns 1L

        val result = repository().stageAudioForSpeakerPreview(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(recordedAt, eventSlot.captured.timestamp)
        assertEquals(recordedAt, commitmentSlot.captured.sourceEventOccurredAt)
        assertEquals(recordedAt, commitmentSlot.captured.dueAt)
    }

    @Test
    fun `meeting audio preview uses selected audio last modified time as schedule time`() = runTest {
        val recordedAt = Instant.parse("2026-05-18T03:15:00Z")
        val eventSlot = slot<RawIngestionEventEntity>()
        val commitmentSlot = slot<CommitmentEntity>()
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) },
                null,
                null,
                null,
            )
        } returns longColumnCursor(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            recordedAt.toEpochMilliseconds(),
        )
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }
        io.mockk.coEvery { commitmentDao.insert(capture(commitmentSlot)) } returns 1L

        val result = repository().stageAudioForSpeakerPreview(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(recordedAt, eventSlot.captured.timestamp)
        assertEquals(recordedAt, commitmentSlot.captured.sourceEventOccurredAt)
        assertEquals(recordedAt, commitmentSlot.captured.dueAt)
    }

    @Test
    fun `meeting audio import falls back to import time when selected audio metadata is invalid`() = runTest {
        val eventSlot = slot<RawIngestionEventEntity>()
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) },
                null,
                null,
                null,
            )
        } returns longColumnCursor(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0L)
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(MediaStore.MediaColumns.DATE_MODIFIED)) },
                null,
                null,
                null,
            )
        } returns longColumnCursor(MediaStore.MediaColumns.DATE_MODIFIED, 0L)
        every {
            resolver.query(
                sourceUri,
                match { it.contentEquals(arrayOf(MediaStore.MediaColumns.DATE_ADDED)) },
                null,
                null,
                null,
            )
        } returns longColumnCursor(MediaStore.MediaColumns.DATE_ADDED, 0L)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val beforeImport = Clock.System.now()
        val result = repository().importAudio(sourceUri)
        val afterImport = Clock.System.now()

        assertTrue(result is BecalmResult.Success)
        assertTrue(eventSlot.captured.timestamp >= beforeImport)
        assertTrue(eventSlot.captured.timestamp <= afterImport)
    }

    @Test
    fun `meeting audio import does not require meeting recording folder tree selection`() = runTest {
        val eventSlot = slot<RawIngestionEventEntity>()
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importAudio(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertAppPrivateAudioRef(eventSlot.captured.sourceRef)
        verify(exactly = 0) { userPrefsStore.observeRecordingFolderTreeUri(SourceType.MEETING) }
    }

    @Test
    fun `meeting audio import uses stable idempotency key for the same selected file`() =
        runTest {
            val events = mutableListOf<RawIngestionEventEntity>()
            every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
            io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(events)) } answers {
                BecalmResult.Success(events.last().id)
            }

            val first = repository().importAudio(sourceUri)
            val second = repository().importAudio(sourceUri)

            assertTrue(first is BecalmResult.Success)
            assertTrue(second is BecalmResult.Success)
            assertEquals(2, events.size)
            assertEquals(events.first().clientEventId, events.last().clientEventId)
            assertEquals(events.first().sourceRef, events.last().sourceRef)
            assertAppPrivateAudioRef(events.first().sourceRef)
        }

    private fun repository(): MeetingImportRepository =
        MeetingImportRepository(
            context = context,
            userPrefsStore = userPrefsStore,
            rawIngestionRepository = rawIngestionRepository,
            commitmentDao = commitmentDao,
            rawIngestionEventDao = rawIngestionEventDao,
            meetingSpeakerPreviewDao = meetingSpeakerPreviewDao,
            meetingSpeakerAliasDao = meetingSpeakerAliasDao,
            selfIdentityAnchorDao = selfIdentityAnchorDao,
            workScheduler = workScheduler,
            ioDispatcher = Dispatchers.IO,
        )

    private fun openableCursor(displayName: String = "standup.m4a"): MatrixCursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>(displayName, 3L))
        }

    private fun emptyDocumentCursor(): MatrixCursor =
        MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
        )

    private fun longColumnCursor(columnName: String, value: Long?): MatrixCursor =
        MatrixCursor(arrayOf(columnName)).apply {
            addRow(arrayOf<Any?>(value))
        }

    private fun assertAppPrivateAudioRef(sourceRef: String?) {
        val uri = Uri.parse(sourceRef)
        assertEquals("file", uri.scheme)
        val file = File(requireNotNull(uri.path))
        assertTrue(file.exists())
        assertTrue(file.path.contains("meeting_imports"))
        assertTrue(file.path.endsWith("standup.m4a"))
        assertFalse(file.path.contains("BeCalm Meetings"))
    }

    private fun meetingPreview(rawEventId: String, expiresAt: Instant?): MeetingSpeakerPreviewEntity =
        MeetingSpeakerPreviewEntity(
            id = "preview-$rawEventId",
            userId = USER_ID,
            rawEventId = rawEventId,
            sourceRef = targetUri.toString(),
            speakerPreviewId = "preview-1",
            speakersJson = "[]",
            transcriptSegmentsJson = "[]",
            billableSeconds = 0,
            status = MeetingSpeakerPreviewStatus.REVIEW_REQUIRED,
            selectedSelfSpeakerId = null,
            lastError = null,
            expiresAt = expiresAt,
            createdAt = Instant.parse("2026-05-18T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-18T00:00:00Z"),
        )

    private fun localInstant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
    ): Instant =
        Instant.fromEpochMilliseconds(
            LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        )

    private companion object {
        private const val USER_ID = "user-1"
    }
}
