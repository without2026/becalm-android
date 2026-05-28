package com.becalm.android.unit.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import com.becalm.android.share.ShareImportIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ShareImportIntentsSpecTest {

    @Test
    fun `single image share resolves extra stream uri`() {
        val uri = Uri.parse("content://screenshots/thread.png")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        assertEquals(uri, ShareImportIntents.singleImageUri(intent))
    }

    @Test
    fun `single image share falls back to one clip data uri`() {
        val uri = Uri.parse("content://screenshots/thread.webp")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            clipData = ClipData.newRawUri("thread", uri)
        }

        assertEquals(uri, ShareImportIntents.singleImageUri(intent))
    }

    @Test
    fun `image share parser rejects non image and multiple shares`() {
        assertNull(
            ShareImportIntents.singleImageUri(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "hello")
                },
            ),
        )
        assertNull(
            ShareImportIntents.singleImageUri(
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/png"
                },
            ),
        )
    }
}
