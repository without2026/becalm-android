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
 * ## Algorithm (earliest quote marker wins)
 * 1. Find the first sentinel line matching one of the common "quote-header" markers:
 *    Gmail's `On <date>, <sender> wrote:` line, Outlook's
 *    `-----Original Message-----` separator, or Outlook's From/Sent/To/Subject header block.
 * 2. Find the first line whose first non-whitespace character is `>` (including nested
 *    `>>` / `>>>` forms).
 * 3. Use whichever marker appears earlier in the body. Everything from that point onward
 *    becomes `quoted`; everything before becomes `commitment`.
 * 4. If neither marker exists, the entire body is `commitment` and `quoted` is null.
 *
 * This preserves the most context in the primary body while still stripping quoted history.
 * It also matches real clients better: some replies prepend a short `>` line before a later
 * `On ... wrote:` header, and the quoted split must start at that earliest visible quote.
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

        return splitAtFirstQuotedMarker(bodyPlain)
            ?: SplitResult(commitment = bodyPlain.trim(), quoted = null)
    }

    // ─── Quote-marker scan ────────────────────────────────────────────────────

    /**
     * Computes the earliest quoted-region start from either a sentinel match or a `>` run.
     * Returns null when the body has no quoted marker at all.
     */
    private fun splitAtFirstQuotedMarker(body: String): SplitResult? {
        val sentinelIndex = SENTINEL_REGEXES
            .mapNotNull { it.find(body) }
            .minByOrNull { it.range.first }
            ?.range
            ?.first
        val angleIndex = firstAngleQuotedLineIndex(body)
        val splitIndex = listOfNotNull(sentinelIndex, angleIndex).minOrNull() ?: return null
        val commitment = body.substring(0, splitIndex).trim()
        val quoted = body.substring(splitIndex).trim()
        return SplitResult(
            commitment = commitment,
            quoted = quoted.ifEmpty { null },
        )
    }

    private fun firstAngleQuotedLineIndex(body: String): Int? {
        var offset = 0
        for (line in body.split('\n')) {
            if (line.trimStart().startsWith(">")) {
                return offset
            }
            offset += line.length + 1
        }
        return null
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
