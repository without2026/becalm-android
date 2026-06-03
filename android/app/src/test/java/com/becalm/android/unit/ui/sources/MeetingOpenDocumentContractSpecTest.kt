package com.becalm.android.unit.ui.sources

import android.content.Intent
import com.becalm.android.ui.sources.MeetingOpenDocumentRequest
import com.becalm.android.ui.sources.toOpenDocumentIntentConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MeetingOpenDocumentContractSpecTest {
    @Test
    fun `MTG-001 audio picker is open document with no SAF folder preselection and mime allowlist`() {
        val config = MeetingOpenDocumentRequest(
                mimeTypes = arrayOf("audio/m4a", "audio/mpeg"),
            )
            .toOpenDocumentIntentConfig()

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, config.action)
        assertEquals("*/*", config.type)
        assertEquals(Intent.CATEGORY_OPENABLE, config.category)
        assertArrayEquals(
            arrayOf("audio/m4a", "audio/mpeg"),
            config.mimeTypes,
        )
        assertFalse(config.includesInitialUri)
        assertFalse(config.grantsPersistableUriPermission)
    }
}
