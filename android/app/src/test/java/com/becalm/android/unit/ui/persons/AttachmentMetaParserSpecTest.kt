package com.becalm.android.ui.persons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentMetaParserSpecTest {

    @Test
    fun `parse returns attachment descriptors for valid metadata json`() {
        val attachments = AttachmentMetaParser.parse(
            """
            [
              {"filename":"a.pdf","mime":"application/pdf","size_bytes":10},
              {"filename":"b.png","mime":"image/png","size_bytes":20}
            ]
            """.trimIndent(),
        )

        assertEquals(2, attachments.size)
        assertEquals("a.pdf", attachments[0].filename)
        assertEquals("application/pdf", attachments[0].mime)
        assertEquals(10L, attachments[0].sizeBytes)
    }

    @Test
    fun `parse returns empty list for null input`() {
        assertTrue(AttachmentMetaParser.parse(null).isEmpty())
    }

    @Test
    fun `parse returns empty list for blank input`() {
        assertTrue(AttachmentMetaParser.parse("   ").isEmpty())
    }

    @Test
    fun `parse returns empty list for malformed json or wrong root shape`() {
        assertTrue(AttachmentMetaParser.parse("""{"filename":"a.pdf"}""").isEmpty())
        assertTrue(AttachmentMetaParser.parse("""[{"filename":1}]""").isEmpty())
    }
}
