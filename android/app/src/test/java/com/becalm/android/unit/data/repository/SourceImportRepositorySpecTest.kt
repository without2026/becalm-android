package com.becalm.android.unit.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.SourceExtractionApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingImportRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SourceImportRepositorySpecTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = mockk()
    private val resolver: ContentResolver = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val meetingImportRepository: MeetingImportRepository = mockk()
    private val sourceExtractionApi: SourceExtractionApi = mockk()
    private val workScheduler: WorkScheduler = mockk(relaxed = true)

    @Test
    fun `MSG-002 import message screenshot stores normalized JPEG in app-private folder and enqueues upload`() = runTest {
        val uri = Uri.parse("content://screenshots/kakao-thread")
        val eventSlot = slot<RawIngestionEventEntity>()
        arrangeContent(
            uri = uri,
            displayName = "kakao-thread.png",
            byteSize = null,
            mimeType = "image/png",
            bytes = pngBytes(width = 4, height = 4),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importMessageScreenshot(uri)

        assertTrue(result is BecalmResult.Success)
        val event = eventSlot.captured
        assertEquals(USER_ID, event.userId)
        assertEquals(SourceType.MESSAGE_SCREENSHOT, event.sourceType)
        assertEquals("kakao-thread.png", event.eventTitle)
        assertEquals("pending", event.syncStatus)
        assertTrue(Uri.parse(event.sourceRef).path.orEmpty().contains("source_imports/message_screenshots"))
        assertTrue(Uri.parse(event.sourceRef).lastPathSegment.orEmpty().endsWith(".jpg"))
        val saved = java.io.File(requireNotNull(Uri.parse(event.sourceRef).path))
        val savedBytes = saved.readBytes()
        assertEquals(0xFF.toByte(), savedBytes[0])
        assertEquals(0xD8.toByte(), savedBytes[1])
        verify(exactly = 1) { workScheduler.enqueueMessageScreenshotUpload(event.id) }
    }

    @Test
    fun `MSG-002 import message screenshot downscales oversized image for OCR upload`() = runTest {
        val uri = Uri.parse("content://screenshots/large-thread")
        val eventSlot = slot<RawIngestionEventEntity>()
        arrangeContent(
            uri = uri,
            displayName = "large-thread.png",
            byteSize = null,
            mimeType = "image/png",
            bytes = pngBytes(width = 1800, height = 1800),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importMessageScreenshot(uri)

        assertTrue(result is BecalmResult.Success)
        val saved = java.io.File(requireNotNull(Uri.parse(eventSlot.captured.sourceRef).path))
        val normalized = BitmapFactory.decodeFile(saved.absolutePath)
        assertEquals(1440, normalized.width)
        assertEquals(1440, normalized.height)
        assertTrue(saved.length() <= 10L * 1024L * 1024L)
        normalized.recycle()
    }

    @Test
    fun `MSG-004 import message screenshot rejects source over-limit stream when provider omits size`() = runTest {
        val uri = Uri.parse("content://screenshots/oversized")
        val eventSlot = slot<RawIngestionEventEntity>()
        arrangeContent(
            uri = uri,
            displayName = "oversized.png",
            byteSize = null,
            mimeType = "image/png",
            bytes = ByteArray(25 * 1024 * 1024 + 1),
        )
        every { userPrefsStore.observeCurrentUserId() } returns flowOf(USER_ID)
        every { userPrefsStore.observeThirdPartyProvisionConsent() } returns flowOf(true)
        coEvery { rawIngestionRepository.insertLocal(capture(eventSlot)) } answers {
            BecalmResult.Success(eventSlot.captured.id)
        }

        val result = repository().importMessageScreenshot(uri)

        assertTrue(result is BecalmResult.Failure)
        assertEquals(BecalmError.Validation("file", "message screenshot exceeds 25 MiB"), (result as BecalmResult.Failure).error)
        coVerify(exactly = 0) { rawIngestionRepository.insertLocal(any()) }
        verify(exactly = 0) { workScheduler.enqueueMessageScreenshotUpload(any()) }
        assertTrue(temp.root.walkTopDown().none { it.isFile && it.name.contains("oversized") })
    }

    @Test
    fun `MSG-004 import message screenshot rejects unknown MIME without image extension`() = runTest {
        val uri = Uri.parse("content://screenshots/blob")
        arrangeContent(
            uri = uri,
            displayName = "conversation",
            byteSize = 3L,
            mimeType = null,
            bytes = byteArrayOf(1, 2, 3),
        )

        val result = repository().importMessageScreenshot(uri)

        assertTrue(result is BecalmResult.Failure)
        assertEquals(BecalmError.Validation("file", "unsupported message screenshot format"), (result as BecalmResult.Failure).error)
        coVerify(exactly = 0) { rawIngestionRepository.insertLocal(any()) }
        verify(exactly = 0) { workScheduler.enqueueMessageScreenshotUpload(any()) }
    }

    private fun repository(): SourceImportRepository =
        SourceImportRepository(
            context = context,
            userPrefsStore = userPrefsStore,
            rawIngestionRepository = rawIngestionRepository,
            meetingImportRepository = meetingImportRepository,
            sourceExtractionApi = sourceExtractionApi,
            workScheduler = workScheduler,
            ioDispatcher = Dispatchers.IO,
        )

    private fun arrangeContent(
        uri: Uri,
        displayName: String,
        byteSize: Long?,
        mimeType: String?,
        bytes: ByteArray,
    ) {
        every { context.contentResolver } returns resolver
        every { context.filesDir } returns temp.root
        every { resolver.getType(uri) } returns mimeType
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        every {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any?>(displayName, byteSize))
            }
        }
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFEFE7DC.toInt())
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private companion object {
        private const val USER_ID = "user-1"
    }
}
