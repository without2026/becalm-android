package com.becalm.android.unit.ui.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.becalm.android.ui.sources.MeetingOpenDocumentContract
import com.becalm.android.ui.sources.MeetingOpenDocumentRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MeetingOpenDocumentContractSpecTest {
    private val context: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Test
    fun `MTG-001 audio picker is open document with target initial uri and mime allowlist`() {
        val initialUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3ARecordings/document/" +
                "primary%3ARecordings%2FBeCalm%20Meetings%2FAudio",
        )

        val intent = MeetingOpenDocumentContract().createIntent(
            context,
            MeetingOpenDocumentRequest(
                mimeTypes = arrayOf("audio/m4a", "audio/mpeg"),
                initialUri = initialUri,
            ),
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertArrayEquals(
            arrayOf("audio/m4a", "audio/mpeg"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertEquals(initialUri, intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI))
    }
}
