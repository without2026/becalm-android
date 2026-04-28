package com.becalm.android.unit.worker

import com.becalm.android.worker.UniqueWorkKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UniqueWorkKeysSpecTest {

    @Test
    fun `SYNC-006 upload unique work key matches foreground contract and preserves legacy key for cleanup`() {
        assertEquals("sync-all-upload", UniqueWorkKeys.UPLOAD)
        assertEquals("sync.upload", UniqueWorkKeys.LEGACY_UPLOAD_KEY)
        assertNotEquals(UniqueWorkKeys.UPLOAD, UniqueWorkKeys.LEGACY_UPLOAD_KEY)
    }

    @Test
    fun `voice upload key is stable per raw event id`() {
        assertEquals(
            "voice.upload.raw-1",
            UniqueWorkKeys.voiceUpload("raw-1"),
        )
        assertEquals(
            UniqueWorkKeys.voiceUpload("raw-1"),
            UniqueWorkKeys.voiceUpload("raw-1"),
        )
    }

    @Test
    fun `different raw events do not share the same voice upload key`() {
        assertNotEquals(
            UniqueWorkKeys.voiceUpload("raw-1"),
            UniqueWorkKeys.voiceUpload("raw-2"),
        )
    }

}
