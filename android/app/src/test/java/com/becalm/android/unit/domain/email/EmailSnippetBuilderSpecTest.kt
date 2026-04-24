package com.becalm.android.unit.domain.email

import com.becalm.android.domain.email.EmailSnippetBuilder
import com.becalm.android.domain.email.SnippetResult
import com.becalm.android.domain.email.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EmailSnippetBuilderSpecTest {

    @Test
    fun `EMAIL-003 plain text wins over html and subject`() {
        assertEquals(
            SnippetResult(
                snippet = "plain body",
                sourceKind = SourceKind.PLAIN,
                parseFailed = false,
            ),
            EmailSnippetBuilder.buildSnippet(
                bodyPlain = "  plain\n\nbody  ",
                bodyHtml = "<b>html body</b>",
                subject = "subject",
            ),
        )
    }

    @Test
    fun `EMAIL-003 strips html and normalises whitespace`() {
        assertEquals(
            SnippetResult(
                snippet = "hello world next",
                sourceKind = SourceKind.HTML_STRIPPED,
                parseFailed = false,
            ),
            EmailSnippetBuilder.buildSnippet(
                bodyPlain = null,
                bodyHtml = "<div>hello <b>world</b></div><p> next </p>",
                subject = "subject",
            ),
        )
    }

    @Test
    fun `EMAIL-003 falls back to subject when body parts are missing`() {
        assertEquals(
            SnippetResult(
                snippet = "subject only",
                sourceKind = SourceKind.SUBJECT_FALLBACK,
                parseFailed = false,
            ),
            EmailSnippetBuilder.buildSnippet(
                bodyPlain = null,
                bodyHtml = null,
                subject = "  subject only  ",
            ),
        )
    }

    @Test
    fun `EMAIL-003 truncates cleaned snippet to 200 characters`() {
        val raw = "a".repeat(250)

        assertEquals(
            200,
            EmailSnippetBuilder.buildSnippet(
                bodyPlain = raw,
                bodyHtml = null,
                subject = null,
            ).snippet.length,
        )
    }

    @Test
    fun `EMAIL-003 returns empty snippet when all inputs are blank`() {
        assertEquals(
            SnippetResult(
                snippet = "",
                sourceKind = SourceKind.SUBJECT_FALLBACK,
                parseFailed = false,
            ),
            EmailSnippetBuilder.buildSnippet(
                bodyPlain = "  ",
                bodyHtml = null,
                subject = "   ",
            ),
        )
    }
}
