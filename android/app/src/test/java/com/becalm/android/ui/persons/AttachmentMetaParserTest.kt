package com.becalm.android.ui.persons

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edge-case coverage for [AttachmentMetaParser]. The parser's contract is
 * "return an empty list on anything unparseable" — every negative case must
 * exercise that path, never throw.
 */
class AttachmentMetaParserTest {

    @Test
    fun `valid JSON with two entries parses both`() {
        val json = """
            [
              {"filename":"report.pdf","mime":"application/pdf","size_bytes":12345},
              {"filename":"data.xlsx","mime":"application/vnd.ms-excel","size_bytes":6789}
            ]
        """.trimIndent()

        val parsed = AttachmentMetaParser.parse(json)

        assertEquals(2, parsed.size)
        assertEquals("report.pdf", parsed[0].filename)
        assertEquals("application/pdf", parsed[0].mime)
        assertEquals(12345L, parsed[0].sizeBytes)
    }

    @Test
    fun `empty JSON array returns empty list`() {
        assertEquals(emptyList<AttachmentMeta>(), AttachmentMetaParser.parse("[]"))
    }

    @Test
    fun `null input returns empty list`() {
        assertEquals(emptyList<AttachmentMeta>(), AttachmentMetaParser.parse(null))
    }

    @Test
    fun `blank input returns empty list`() {
        assertEquals(emptyList<AttachmentMeta>(), AttachmentMetaParser.parse("   "))
    }

    @Test
    fun `malformed JSON returns empty list without throwing`() {
        assertEquals(emptyList<AttachmentMeta>(), AttachmentMetaParser.parse("{not valid"))
    }

    @Test
    fun `object instead of array returns empty list`() {
        assertEquals(
            emptyList<AttachmentMeta>(),
            AttachmentMetaParser.parse("""{"filename":"x.pdf"}"""),
        )
    }
}
