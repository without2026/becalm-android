package com.becalm.android.unit.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.worker.WorkScheduler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MeetingImportRepositorySpecTest {
    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    private val sourceUri = Uri.parse("content://picked/standup")
    private val treeUri = Uri.parse("content://tree/root")
    private val rootDocumentUri = Uri.parse("content://tree/root/document/root")
    private val rootChildrenUri = Uri.parse("content://tree/root/children/root")
    private val meetingsUri = Uri.parse("content://tree/root/document/root%2FBeCalm%20Meetings")
    private val meetingsChildrenUri = Uri.parse("content://tree/root/children/root%2FBeCalm%20Meetings")
    private val audioDirUri = Uri.parse("content://tree/root/document/root%2FBeCalm%20Meetings%2FAudio")
    private val targetUri = Uri.parse("content://tree/root/document/root%2FBeCalm%20Meetings%2FAudio%2Fstandup.m4a")

    @Before
    fun setUp() {
        mockkStatic(DocumentsContract::class)
        every { context.contentResolver } returns resolver
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeRecordingFolderTreeUri() } returns flowOf(treeUri.toString())
        every { resolver.getType(sourceUri) } returns "audio/m4a"
        every { resolver.openInputStream(sourceUri) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { resolver.openOutputStream(targetUri, "w") } returns ByteArrayOutputStream()
        every { resolver.query(any(), any<Array<String>>(), null, null, null) } answers {
            when (firstArg<Uri>()) {
                sourceUri -> openableCursor()
                rootChildrenUri, meetingsChildrenUri -> emptyDocumentCursor()
                else -> emptyDocumentCursor()
            }
        }
        every { DocumentsContract.getTreeDocumentId(treeUri) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(treeUri, "root") } returns rootDocumentUri
        every { DocumentsContract.getDocumentId(rootDocumentUri) } returns "root"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "root") } returns rootChildrenUri
        every {
            DocumentsContract.createDocument(
                resolver,
                rootDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "BeCalm Meetings",
            )
        } returns meetingsUri
        every { DocumentsContract.getDocumentId(meetingsUri) } returns "root/BeCalm Meetings"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, "root/BeCalm Meetings") } returns meetingsChildrenUri
        every {
            DocumentsContract.createDocument(
                resolver,
                meetingsUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "Audio",
            )
        } returns audioDirUri
        every { DocumentsContract.createDocument(resolver, audioDirUri, "audio/m4a", any()) } returns targetUri
    }

    @After
    fun tearDown() {
        unmockkStatic(DocumentsContract::class)
    }

    @Test
    // spec: ING-001A
    // spec: MTG-002
    fun `meeting audio import copies into recordings tree and enqueues upload when consented`() = runTest {
        val eventSlot = slot<RawIngestionEventEntity>()
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        io.mockk.coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importAudio(sourceUri)

        assertTrue(result is BecalmResult.Success)
        assertEquals(SourceType.MEETING, eventSlot.captured.sourceType)
        assertEquals(targetUri.toString(), eventSlot.captured.sourceRef)
        assertEquals("standup.m4a", eventSlot.captured.eventTitle)
        assertEquals("pending", eventSlot.captured.syncStatus)
        verify(exactly = 1) { workScheduler.enqueueVoiceUpload(eventSlot.captured.id, targetUri.toString(), null, null, null) }
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

    private fun repository(): MeetingImportRepository =
        MeetingImportRepository(
            context = context,
            userPrefsStore = userPrefsStore,
            rawIngestionRepository = rawIngestionRepository,
            workScheduler = workScheduler,
            ioDispatcher = Dispatchers.IO,
        )

    private fun openableCursor(): MatrixCursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any?>("standup.m4a", 3L))
        }

    private fun emptyDocumentCursor(): MatrixCursor =
        MatrixCursor(
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
        )

    private companion object {
        private const val USER_ID = "user-1"
    }
}
