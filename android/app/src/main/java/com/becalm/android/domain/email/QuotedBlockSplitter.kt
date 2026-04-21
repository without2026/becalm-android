package com.becalm.android.domain.email

import javax.inject.Inject

/**
 * Splits an email plain-text body into its primary message area and a quoted reply block so
 * that [com.becalm.android.worker.extraction.CommitmentExtractionWorker] can hand the two
 * halves to the on-device LLM as distinct prompt sections.
 *
 * EMAIL-005 (`.spec/email-pipeline.spec.yml:49-54`) requires that the extraction prompt
 * isolates quoted replies — commitments must only come from the primary message area, never
 * from text that was originally written by the *other* correspondent and quoted back in a
 * reply. Without this separation the LLM would happily re-extract the counterparty's old
 * promises as if they were the user's new ones.
 *
 * ## Algorithm (in order; first match wins)
 * 1. **Sentinel scan** — look for a line that matches one of the three common "quote-header"
 *    markers: Gmail's `On <date>, <sender> wrote:` line, Outlook's
 *    `-----Original Message-----` separator, or Outlook's From/Sent/To/Subject header block.
 *    When a sentinel is found everything from that line onward (inclusive) becomes `quoted`;
 *    everything before it becomes `commitment`.
 * 2. **`^>` scan** — when no sentinel matched, search for the first contiguous run of lines
 *    whose first non-whitespace character is `>` (including nested `>>` / `>>>` forms). The
 *    run, plus everything after it, becomes `quoted`; everything before becomes `commitment`.
 * 3. **Fallback** — no sentinel and no `^>` block: the entire body is `commitment`, and
 *    `quoted` is null.
 *
 * Sentinel-first was chosen over `^>`-first because `On ... wrote:` / `-----Original Message-----`
 * markers have near-zero false-positive rate, while stray `>` lines occasionally appear inside
 * quoted code blocks or Markdown tables in the primary body.
 *
 * ## MVP scope
 * English sentinels only. Korean equivalents like `2023년 12월 18일 오후 3:45, 홍길동 님이 작성:`
 * are intentionally **not** matched — see the Korean-variant test case, which asserts the
 * body stays in `commitment` untouched so a future PR can add Korean patterns without silently
 * changing existing behaviour.
 *
 * Spec refs: EMAIL-005, `.spec/email-pipeline.spec.yml § invariants` (인용 영역에서는
 * commitment 추출하지 않는다).
 */
public class QuotedBlockSplitter @Inject constructor() {

    /**
     * Splits [bodyPlain] into its primary and quoted halves.
     *
     * Return invariants:
     * - `commitment` is always non-null. It is [String.trim]-med; empty string means the
     *   entire body was detected as quoted (e.g. a full-quote reply).
     * - `quoted` is null when no sentinel and no `^>` run was found. Otherwise it is
     *   [String.trim]-med; empty string results are normalised to null so downstream callers
     *   do not need a second `isBlank()` check.
     */
    public fun split(bodyPlain: String): SplitResult {
        if (bodyPlain.isEmpty()) {
            return SplitResult(commitment = "", quoted = null)
        }

        val sentinelSplit = splitOnSentinel(bodyPlain)
        if (sentinelSplit != null) return sentinelSplit

        val angleSplit = splitOnAngleBracketRun(bodyPlain)
        if (angleSplit != null) return angleSplit

        return SplitResult(commitment = bodyPlain.trim(), quoted = null)
    }

    // ─── Sentinel ──────────────────────────────────────────────────────────────

    /**
     * Attempts to split [body] using one of the three sentinel regexes. Returns null when no
     * sentinel matched so the caller can fall through to the `^>` scan.
     */
    private fun splitOnSentinel(body: String): SplitResult? {
        val earliestMatch = SENTINEL_REGEXES
            .mapNotNull { it.find(body) }
            .minByOrNull { it.range.first }
            ?: return null

        val splitIndex = earliestMatch.range.first
        val commitment = body.substring(0, splitIndex).trim()
        val quoted = body.substring(splitIndex).trim()
        return SplitResult(
            commitment = commitment,
            quoted = quoted.ifEmpty { null },
        )
    }

    /**
     * Attempts to split [body] on the first contiguous run of `^>`-prefixed lines. Returns
     * null when no such run exists.
     */
    private fun splitOnAngleBracketRun(body: String): SplitResult? {
        val lines = body.split("\n")
        var firstQuotedLine = -1
        for (index in lines.indices) {
            if (lines[index].trimStart().startsWith(">")) {
                firstQuotedLine = index
                break
            }
        }
        if (firstQuotedLine == -1) return null

        val commitmentLines = lines.subList(0, firstQuotedLine)
        val quotedLines = lines.subList(firstQuotedLine, lines.size)
        val commitment = commitmentLines.joinToString("\n").trim()
        val quoted = quotedLines.joinToString("\n").trim()
        return SplitResult(
            commitment = commitment,
            quoted = quoted.ifEmpty { null },
        )
    }
}

/**
 * Outcome of [QuotedBlockSplitter.split].
 *
 * @property commitment Primary message text — the only region the LLM should extract
 *   commitments from. Trimmed of leading/trailing whitespace. Possibly empty when the entire
 *   body is a quoted reply.
 * @property quoted Quoted-reply block including its sentinel header line, or null when no
 *   quoted content was detected. Trimmed; empty results are normalised to null.
 */
public data class SplitResult(
    val commitment: String,
    val quoted: String?,
)

// ─── Sentinel regexes — compiled once at class load ────────────────────────────

/**
 * Gmail-style reply header: `On Mon, Dec 18, 2023 at 3:45 PM John Doe <john@example.com> wrote:`.
 * Matches any line beginning with `On` and ending with `wrote:` possibly followed by trailing
 * whitespace. MVP English-only — Korean equivalent (`2023년 ... 님이 작성:`) is out of scope.
 */
private val SENTINEL_ON_WROTE: Regex = Regex("""(?m)^On\s+.+\s+wrote:\s*$""")

/**
 * Outlook legacy separator line `-----Original Message-----`. Outlook prepends this block
 * when quoting a full previous message as a reply context.
 */
private val SENTINEL_ORIGINAL_MESSAGE: Regex = Regex("""(?m)^-----Original Message-----\s*$""")

/**
 * Outlook-style contiguous header block — four consecutive lines starting with `From:`,
 * `Sent:`, `To:`, `Subject:` (optionally with a `Cc:` field interleaved). Captures the
 * whole block as a single match so the split point is the top of the header.
 */
private val SENTINEL_OUTLOOK_HEADER: Regex = Regex(
    """(?m)^From:\s*.+\n\s*Sent:\s*.+\n(?:\s*Cc:\s*.+\n)?\s*To:\s*.+\n\s*Subject:\s*.+$""",
)

/** Ordered list of sentinel regexes; earliest match wins regardless of list order. */
private val SENTINEL_REGEXES: List<Regex> = listOf(
    SENTINEL_ON_WROTE,
    SENTINEL_ORIGINAL_MESSAGE,
    SENTINEL_OUTLOOK_HEADER,
)
