package com.becalm.android.domain.email

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [EmailSnippetBuilder.buildSnippet].
 *
 * Pure JVM — no Robolectric needed because Jsoup runs on plain Java. Pins the
 * EMAIL-003 / EMAIL-007 contract from `.spec/email-pipeline.spec.yml:31-37, 67-73`:
 *
 * - 3-step fallback chain: `body_plain` → `body_html` (Jsoup-stripped) → `subject`.
 * - Whitespace collapse via `\s+ → " "` BEFORE truncation (so internal whitespace
 *   never pushes real characters past 200).
 * - 200-char hard ceiling on Kotlin code-units.
 * - HTML parse failure surfaces via `parseFailed=true` AND degrades to subject
 *   (worker copies the flag to `EmailBody.parse_failed`).
 *
 * Spec refs: EMAIL-003, EMAIL-007.
 */
class EmailSnippetBuilderTest {

    @After
    fun tearDown() {
        // Defensive: any test that mockkStatic'd Jsoup MUST tear it down so a later
        // test does not see the previous test's mock when it expects real Jsoup.
        unmockkStatic(Jsoup::class)
    }

    // ─── PLAIN path ─────────────────────────────────────────────────────────

    @Test
    fun `body_plain present takes PLAIN source kind and truncates to 200`() {
        val plain = "a".repeat(300)

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = plain,
            bodyHtml = "<p>ignored</p>",
            subject = "ignored subject",
        )

        assertEquals(SourceKind.PLAIN, result.sourceKind)
        assertEquals(200, result.snippet.length)
        assertEquals("a".repeat(200), result.snippet)
        assertFalse(result.parseFailed)
    }

    // ─── HTML_STRIPPED path ─────────────────────────────────────────────────

    @Test
    fun `body_plain null and body_html present strips HTML tags`() {
        val html = "<p>hello <b>world</b></p>"

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = html,
            subject = "ignored",
        )

        assertEquals(SourceKind.HTML_STRIPPED, result.sourceKind)
        assertEquals("hello world", result.snippet)
        assertFalse(result.parseFailed)
    }

    @Test
    fun `body_plain blank whitespace falls through to HTML_STRIPPED`() {
        // Blank-but-non-null body_plain (e.g. some IMAP servers strip text/plain to
        // a single newline) must NOT short-circuit the chain — we want the HTML.
        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = "   \n\t  ",
            bodyHtml = "<p>real content</p>",
            subject = "subj",
        )

        assertEquals(SourceKind.HTML_STRIPPED, result.sourceKind)
        assertEquals("real content", result.snippet)
        assertFalse(result.parseFailed)
    }

    @Test
    fun `HTML with script and style tags ignores JS and CSS content`() {
        val html = """
            <html><head>
              <style>body { color: red; }</style>
              <script>alert('xss');</script>
            </head>
            <body><p>visible only</p></body></html>
        """.trimIndent()

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = html,
            subject = "irrelevant",
        )

        assertEquals(SourceKind.HTML_STRIPPED, result.sourceKind)
        assertEquals("visible only", result.snippet)
        assertFalse(result.snippet.contains("alert"))
        assertFalse(result.snippet.contains("color"))
        assertFalse(result.parseFailed)
    }

    @Test
    fun `HTML entities are decoded in stripped output`() {
        // Jsoup decodes &amp; → '&' and &lt; → '<' as part of .text() extraction.
        val html = "<p>Tom &amp; Jerry &lt;3</p>"

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = html,
            subject = "ignored",
        )

        assertEquals(SourceKind.HTML_STRIPPED, result.sourceKind)
        assertEquals("Tom & Jerry <3", result.snippet)
        assertFalse(result.parseFailed)
    }

    // ─── SUBJECT_FALLBACK path ──────────────────────────────────────────────

    @Test
    fun `body_plain and body_html both null falls back to subject`() {
        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = null,
            subject = "Re: meeting tomorrow",
        )

        assertEquals(SourceKind.SUBJECT_FALLBACK, result.sourceKind)
        assertEquals("Re: meeting tomorrow", result.snippet)
        assertFalse(result.parseFailed)
    }

    @Test
    fun `all inputs null or blank yields empty SUBJECT_FALLBACK with parseFailed false`() {
        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = "",
            subject = null,
        )

        assertEquals(SourceKind.SUBJECT_FALLBACK, result.sourceKind)
        assertEquals("", result.snippet)
        assertFalse(result.parseFailed)
    }

    // ─── Whitespace collapse ────────────────────────────────────────────────

    @Test
    fun `whitespace collapse merges spaces tabs and newlines into single spaces`() {
        val plain = "alpha   beta\t\tgamma\n\n\ndelta\r\nepsilon"

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = plain,
            bodyHtml = null,
            subject = null,
        )

        assertEquals(SourceKind.PLAIN, result.sourceKind)
        assertEquals("alpha beta gamma delta epsilon", result.snippet)
    }

    @Test
    fun `whitespace collapse happens before truncate at 200 char boundary`() {
        // 250-char source dominated by repeated whitespace that, after collapse,
        // shrinks WELL below 200. The result must reflect post-collapse length, not
        // a pre-collapse 200-char prefix.
        val plain = ("word    ").repeat(40) // 8 chars * 40 = 320, post-collapse: "word " * 40 = 200
            // After collapse + trim: "word word word ... word" (40 words separated by single spaces)
            // length = 40 words (4 chars) + 39 separators (1 char) = 199 chars

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = plain,
            bodyHtml = null,
            subject = null,
        )

        assertEquals(SourceKind.PLAIN, result.sourceKind)
        // Each word is "word", separator is single space (collapsed), trimmed: 40*4 + 39 = 199
        assertEquals(199, result.snippet.length)
        assertTrue(result.snippet.startsWith("word word word"))
        assertTrue(result.snippet.endsWith("word"))
    }

    // ─── Truncate boundary ──────────────────────────────────────────────────

    @Test
    fun `exactly 200 chars stays intact untruncated`() {
        val plain = "x".repeat(200)

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = plain,
            bodyHtml = null,
            subject = null,
        )

        assertEquals(200, result.snippet.length)
        assertEquals("x".repeat(200), result.snippet)
    }

    @Test
    fun `201 chars truncates to 200`() {
        val plain = "y".repeat(201)

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = plain,
            bodyHtml = null,
            subject = null,
        )

        assertEquals(200, result.snippet.length)
        assertEquals("y".repeat(200), result.snippet)
    }

    // ─── EMAIL-007 parse failure ────────────────────────────────────────────

    @Test
    fun `malformed HTML triggers parseFailed and falls through to subject`() {
        // Force Jsoup to throw via mockkStatic — the only reliable way since real
        // Jsoup is famously tolerant of malformed HTML and rarely throws.
        mockkStatic(Jsoup::class)
        every { Jsoup.parse(any<String>()) } throws RuntimeException("boom: malformed")

        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = "<<<broken html>>>",
            subject = "fallback subject",
        )

        assertEquals(SourceKind.SUBJECT_FALLBACK, result.sourceKind)
        assertEquals("fallback subject", result.snippet)
        assertTrue("parseFailed must propagate to caller", result.parseFailed)
    }

    // ─── Non-AC sanity check (cross-ref from plan §6) ───────────────────────

    @Test
    fun `cross-ref AC subject only with x returns SnippetResult x SUBJECT_FALLBACK false`() {
        // Direct quote from `domain-email-snippet-builder.md` §6 acceptance criteria.
        val result = EmailSnippetBuilder.buildSnippet(
            bodyPlain = null,
            bodyHtml = null,
            subject = "x",
        )

        assertNotNull(result)
        assertEquals("x", result.snippet)
        assertEquals(SourceKind.SUBJECT_FALLBACK, result.sourceKind)
        assertFalse(result.parseFailed)
    }
}
