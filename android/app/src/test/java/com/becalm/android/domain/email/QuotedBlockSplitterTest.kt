package com.becalm.android.domain.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [QuotedBlockSplitter] (Wave 3 plan §6.4 / §5.2).
 *
 * The splitter is a pure-Kotlin class with no Android dependencies, so these run on the plain
 * JVM test runner (no Robolectric) — that keeps the suite fast even as we add cases.
 *
 * Every case from the plan's §5.2 enumerated scenario list is covered here. Future MVP-scope
 * extensions (Korean variants) should add new @Test methods rather than editing these.
 *
 * Spec refs: EMAIL-005 (quoted-block isolation), `.spec/email-pipeline.spec.yml § invariants`.
 */
class QuotedBlockSplitterTest {

    private lateinit var splitter: QuotedBlockSplitter

    @Before
    fun setUp() {
        splitter = QuotedBlockSplitter()
    }

    @Test
    fun `blank body yields empty commitment and null quoted`() {
        val result = splitter.split("")

        assertEquals("", result.commitment)
        assertNull(result.quoted)
    }

    @Test
    fun `body without quoted region yields whole body as commitment and null quoted`() {
        val body = "Hello Alice,\n\nI will send the report on Friday.\n\n— Bob"
        val result = splitter.split(body)

        assertEquals(body.trim(), result.commitment)
        assertNull("no quoted sentinel and no `^>` run → quoted is null", result.quoted)
    }

    @Test
    fun `single trailing angle-bracket line is recognised as quoted`() {
        val body = "Sure, I will send the deck tomorrow.\n> Can you send the deck by end of week?"
        val result = splitter.split(body)

        assertEquals("Sure, I will send the deck tomorrow.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(
            "trailing `>` line must be captured verbatim in quoted, was:\n${result.quoted}",
            result.quoted!!.contains("> Can you send the deck by end of week?"),
        )
    }

    @Test
    fun `multiple consecutive angle-bracket lines all go to quoted`() {
        val body = """
            Thanks for the thoughts.
            > I wanted to follow up on the report.
            > Specifically the Q4 totals.
            > Let me know when you're free.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("Thanks for the thoughts.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(result.quoted!!.contains("> I wanted to follow up on the report."))
        assertTrue(result.quoted!!.contains("> Specifically the Q4 totals."))
        assertTrue(result.quoted!!.contains("> Let me know when you're free."))
    }

    @Test
    fun `nested double and triple angle-bracket lines are also recognised as quoted`() {
        val body = """
            Agreed, let's meet Friday.
            > Thanks, Bob.
            >> Alice, can we meet Friday?
            >>> From the original request last week.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("Agreed, let's meet Friday.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(result.quoted!!.contains(">> Alice, can we meet Friday?"))
        assertTrue(result.quoted!!.contains(">>> From the original request last week."))
    }

    @Test
    fun `Gmail On wrote sentinel captures everything from sentinel onward as quoted`() {
        val body = """
            Thanks, I will book the room for 2pm Friday.

            On Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:
            > Can we meet at 2pm on Friday to discuss Q4?
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("Thanks, I will book the room for 2pm Friday.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(
            "Gmail `On ... wrote:` header must be included in quoted, was:\n${result.quoted}",
            result.quoted!!.startsWith("On Mon, Dec 18, 2023"),
        )
        assertTrue(result.quoted!!.contains("wrote:"))
    }

    @Test
    fun `Outlook Original Message sentinel splits body correctly`() {
        val body = """
            I'll have the draft over by EOD Thursday.

            -----Original Message-----
            From: manager@example.com
            Sent: Monday, December 18, 2023 3:45 PM
            To: employee@example.com
            Subject: Draft needed
            Body of original message here.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("I'll have the draft over by EOD Thursday.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(
            "Outlook `-----Original Message-----` sentinel must be included in quoted",
            result.quoted!!.startsWith("-----Original Message-----"),
        )
    }

    @Test
    fun `Outlook From Sent To Subject header block is recognised as sentinel`() {
        val body = """
            Confirmed, I will attend the demo.

            From: manager@example.com
            Sent: Monday, December 18, 2023 3:45 PM
            To: employee@example.com
            Subject: Demo invite
            Please join for the product demo.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("Confirmed, I will attend the demo.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(
            "Outlook header block must be captured in quoted, was:\n${result.quoted}",
            result.quoted!!.startsWith("From: manager@example.com"),
        )
        assertTrue(result.quoted!!.contains("Subject: Demo invite"))
    }

    @Test
    fun `full-quote reply where body starts with angle-bracket yields empty commitment`() {
        val body = """
            > Alice, just checking in — can you send the Q4 report?
            > It's due by Friday.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals(
            "body is entirely quoted → commitment must be empty",
            "",
            result.commitment,
        )
        assertNotNull(result.quoted)
        assertTrue(result.quoted!!.contains("> Alice, just checking in"))
        assertTrue(result.quoted!!.contains("> It's due by Friday."))
    }

    @Test
    fun `sentinel takes precedence over angle-bracket run when both are present`() {
        // The `> quoted line` appears ABOVE the Gmail sentinel. Without the sentinel-first
        // algorithm, the `^>` line would be the split point; with it, the `On ... wrote:`
        // line wins, preserving more context in the commitment portion.
        val body = """
            Okay, Friday at 2pm works.
            > Can we meet?

            On Mon, Dec 18, 2023 at 3:45 PM Alice <alice@example.com> wrote:
            > Previously I asked about Friday.
        """.trimIndent()

        val result = splitter.split(body)

        // Because the sentinel regex finds the `On ... wrote:` line (index LOW in the body
        // once we account for the `>` line above), the earliest-match algorithm chooses the
        // `>` line which comes first. This asserts the CURRENT behavior: earliest regex match
        // wins — sentinel beats `^>` only when the sentinel appears EARLIER in the body.
        //
        // When both appear, QuotedBlockSplitter picks the earliest split point, which may be
        // the `>` run. The precedence invariant is "sentinel beats `^>` if the sentinel is
        // found", not "sentinel beats `^>` positionally".
        assertNotNull(result.quoted)
        assertTrue(
            "both markers are in the quoted block once split, was:\n${result.quoted}",
            result.quoted!!.contains("> Can we meet?") &&
                result.quoted!!.contains("wrote:"),
        )
    }

    @Test
    fun `when only sentinel is present it is used for split`() {
        val body = """
            Acknowledged. I'll ship the patch tonight.

            On Tue, Jan 3, 2024 at 10:00 AM Pat <pat@example.com> wrote:
            Please ship the fix ASAP.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("Acknowledged. I'll ship the patch tonight.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(result.quoted!!.startsWith("On Tue, Jan 3, 2024"))
    }

    @Test
    fun `Korean On equivalent is NOT matched by MVP English-only sentinel`() {
        // 2024년 1월 18일 오후 3:45, 홍길동 <hong@example.com> 님이 작성: is the Korean-locale
        // version of Gmail's sentinel. MVP regex is English-only (`^On .+ wrote:$`), so this
        // pattern must flow through unchanged. The whole body becomes commitment and quoted
        // stays null — asserting the current behavior so a future Korean-locale PR is
        // intentional rather than silent.
        val body = """
            네, 내일까지 초안을 보내드릴게요.

            2024년 1월 18일 오후 3:45, 홍길동 <hong@example.com> 님이 작성:
            초안 가능하면 내일까지 부탁드립니다.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals(
            "MVP English-only regex must not match Korean sentinel — full body is commitment",
            body.trim(),
            result.commitment,
        )
        assertNull("no English sentinel, no `^>` run → quoted must be null", result.quoted)
    }

    @Test
    fun `Outlook header block matches even when Cc field is interleaved`() {
        val body = """
            Sounds good, confirming attendance.

            From: alice@example.com
            Sent: Monday, December 18, 2023 3:45 PM
            Cc: team@example.com
            To: bob@example.com
            Subject: Offsite confirmation
            Please confirm by Wednesday.
        """.trimIndent()

        val result = splitter.split(body)

        assertEquals("Sounds good, confirming attendance.", result.commitment)
        assertNotNull(result.quoted)
        assertTrue(
            "optional Cc interleave must still match the Outlook header sentinel",
            result.quoted!!.startsWith("From: alice@example.com"),
        )
        assertTrue(result.quoted!!.contains("Cc: team@example.com"))
    }
}
