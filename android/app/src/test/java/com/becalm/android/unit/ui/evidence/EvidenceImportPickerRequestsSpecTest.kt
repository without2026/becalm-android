package com.becalm.android.unit.ui.evidence

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.ui.evidence.EvidenceImportPickerRequests
import com.becalm.android.ui.sources.MeetingOpenDocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvidenceImportPickerRequestsSpecTest {

    @Test
    fun `message screenshot uses visual media image picker not document folder picker`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ActivityResultContracts.PickVisualMedia()
            .createIntent(context, EvidenceImportPickerRequests.messageScreenshot())

        assertTrue(
            listOf(
                android.provider.MediaStore.ACTION_PICK_IMAGES,
                ActivityResultContracts.PickVisualMedia.ACTION_SYSTEM_FALLBACK_PICK_IMAGES,
                Intent.ACTION_GET_CONTENT,
            ).contains(intent.action),
        )
        assertFalse(intent.action == Intent.ACTION_OPEN_DOCUMENT)
        assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }

    @Test
    fun `meeting audio keeps document picker with audio MIME path only`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val request = EvidenceImportPickerRequests.meetingAudio()
        val intent = MeetingOpenDocumentContract().createIntent(context, request)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(request.mimeTypes.all { it.startsWith("audio/") || it == "application/octet-stream" })
        assertNull(request.initialUri)
    }
}
