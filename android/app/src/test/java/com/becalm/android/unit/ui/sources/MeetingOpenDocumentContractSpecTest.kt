package com.becalm.android.unit.ui.sources

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import com.becalm.android.ui.sources.MeetingOpenDocumentContract
import com.becalm.android.ui.sources.MeetingOpenDocumentRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MeetingOpenDocumentContractSpecTest {
    private val context: Context = androidx.test.core.app.ApplicationProvider.getApplicationContext()

    @Test
    fun `MTG-001 audio picker is open document with no SAF folder preselection and mime allowlist`() {
        val intent = MeetingOpenDocumentContract().createIntent(
            context,
            MeetingOpenDocumentRequest(
                mimeTypes = arrayOf("audio/m4a", "audio/mpeg"),
            ),
        )

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
        assertArrayEquals(
            arrayOf("audio/m4a", "audio/mpeg"),
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
        assertFalse(intent.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))
        assertFalse(
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION ==
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }
}
