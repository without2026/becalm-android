package com.becalm.android.data.repository

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Checkpoint3ScreenshotImportSpecTest {

    @Test
    fun e2e_034_oversized_screenshot_is_rejected_before_raw_event_creation() {
        val oversized = ByteArray(MessageScreenshotImageNormalizer.MAX_SOURCE_BYTES.toInt() + 1)

        val error = assertThrows(MessageScreenshotImportValidationException::class.java) {
            MessageScreenshotImageNormalizer.normalize(
                input = ByteArrayInputStream(oversized),
                target = File.createTempFile("oversized", ".jpg"),
            )
        }

        assertEquals("message screenshot exceeds 25 MiB", error.validationMessage)
    }
}
