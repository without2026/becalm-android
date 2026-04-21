package com.becalm.android.domain.email

import org.jsoup.Jsoup

/**
 * Pure-domain builder for `raw_ingestion_events.event_snippet` from email message parts.
 *
 * Mirrors the EMAIL-003 / EMAIL-007 contract from `.spec/email-pipeline.spec.yml:31-37, 67-73`:
 * a deterministic 3-step fallback chain with whitespace normalisation and a 200-character cap.
 *
 * ## Algorithm
 * 1. `bodyPlain` non-null and `isNotBlank()` → `SourceKind.PLAIN`.
 * 2. Otherwise `bodyHtml` non-null and `isNotBlank()` → `Jsoup.parse(html).text()`,
 *    `SourceKind.HTML_STRIPPED`. If parsing throws (malformed HTML / charset mismatch /
 *    nested overflow), [SnippetResult.parseFailed] flips true and the chain falls through
 *    to subject. EMAIL-007 requires graceful degrade rather than worker abort.
 * 3. Otherwise `subject.orEmpty()` → `SourceKind.SUBJECT_FALLBACK`.
 *
 * For all three paths the resulting string is normalised in this fixed order:
 * `replace(Regex("\\s+"), " ")` → `trim()` → `take(200)`. Whitespace collapse always
 * happens before truncate so the final length is measured against the cleaned text.
 *
 * ## Side effects
 * None. The builder is intentionally `object`-scoped so Android Context / DataStore
 * are not required for unit tests (Robolectric-free). Callers that need to bump the
 * `email_subject_only_skipped` DataStore counter inspect [SnippetResult.sourceKind]
 * and call `MetricsStore.incrementSubjectOnlySkipped()` themselves; this keeps the
 * domain unit pure and the metric write site visible at the call site.
 *
 * ## Out of scope
 * - LLM-skip gating for subject-only emails — `CommitmentExtractionWorker` decides.
 * - Persisting `EmailBody.parse_failed = true` — worker uses `parseFailed` flag.
 * - `<script>` / `<style>` whitelisting — handled implicitly by `Jsoup.text()`.
 *
 * Spec refs: EMAIL-003, EMAIL-007, ADAPT-EMAIL-008, `.spec/data-ingestion.spec.yml § ING-006/7/8`.
 */
public object EmailSnippetBuilder {

    /** Maximum snippet length in Kotlin code-units (not bytes). */
    private const val MAX_SNIPPET_CHARS: Int = 200

    /** Matches one-or-more whitespace characters (spaces, tabs, newlines, form-feeds). */
    private val WHITESPACE_RUN: Regex = Regex("\\s+")

    /**
     * Computes the snippet plus its provenance and parse-failure flag.
     *
     * @param bodyPlain The plain-text part of the email, or null when none was extracted.
     * @param bodyHtml The HTML part of the email, or null when none was extracted.
     * @param subject The subject line, used only as last-resort fallback.
     * @return A non-null [SnippetResult]. The [SnippetResult.snippet] string may be empty
     *   when all three inputs are null/blank — the caller decides whether to surface the
     *   row at all (the EMAIL-003 metric trigger fires on `SUBJECT_FALLBACK` regardless).
     */
    public fun buildSnippet(
        bodyPlain: String?,
        bodyHtml: String?,
        subject: String?,
    ): SnippetResult {
        if (!bodyPlain.isNullOrBlank()) {
            return SnippetResult(
                snippet = normalize(bodyPlain),
                sourceKind = SourceKind.PLAIN,
                parseFailed = false,
            )
        }

        if (!bodyHtml.isNullOrBlank()) {
            val parsed: String? = try {
                Jsoup.parse(bodyHtml).text()
            } catch (t: Throwable) {
                // EMAIL-007: HTML parse failure must not abort the worker. Surface the failure
                // via parseFailed so the caller writes EmailBody.parse_failed=true, then fall
                // through to subject. Throwable (not Exception) catches the rare Jsoup
                // StackOverflowError on deeply nested malformed HTML.
                null
            }
            if (parsed != null) {
                return SnippetResult(
                    snippet = normalize(parsed),
                    sourceKind = SourceKind.HTML_STRIPPED,
                    parseFailed = false,
                )
            }
            // parse failed: fall through to subject fallback with parseFailed=true
            return SnippetResult(
                snippet = normalize(subject.orEmpty()),
                sourceKind = SourceKind.SUBJECT_FALLBACK,
                parseFailed = true,
            )
        }

        return SnippetResult(
            snippet = normalize(subject.orEmpty()),
            sourceKind = SourceKind.SUBJECT_FALLBACK,
            parseFailed = false,
        )
    }

    /**
     * Collapses every run of whitespace to a single space, trims leading/trailing
     * whitespace, then truncates to [MAX_SNIPPET_CHARS]. Order matters — collapsing
     * must happen before truncation so a long stretch of internal whitespace cannot
     * push real content past the 200-char boundary.
     */
    private fun normalize(raw: String): String =
        raw.replace(WHITESPACE_RUN, " ").trim().take(MAX_SNIPPET_CHARS)
}

/**
 * Outcome of [EmailSnippetBuilder.buildSnippet].
 *
 * @property snippet The normalised, truncated snippet text. Always non-null; possibly empty
 *   when every input was null or blank.
 * @property sourceKind Which fallback layer produced [snippet]. Callers gate downstream
 *   behaviour on this — for example, `SUBJECT_FALLBACK` triggers a `MetricsStore` increment
 *   and skips `CommitmentExtractionWorker` enqueue.
 * @property parseFailed True when the HTML branch threw and the chain fell through to
 *   subject. Callers must mirror this flag onto `EmailBody.parse_failed` per EMAIL-007.
 */
public data class SnippetResult(
    val snippet: String,
    val sourceKind: SourceKind,
    val parseFailed: Boolean,
)

/**
 * The provenance of a snippet returned by [EmailSnippetBuilder.buildSnippet].
 *
 * Used by callers to drive metrics (`SUBJECT_FALLBACK` → `email_subject_only_skipped += 1`)
 * and LLM-extraction gating (subject-only emails skip `CommitmentExtractionWorker`).
 */
public enum class SourceKind {
    /** The plain-text part was non-blank and used directly. */
    PLAIN,

    /** The HTML part was parsed via Jsoup and the visible text was extracted. */
    HTML_STRIPPED,

    /** Both body parts were null/blank (or HTML failed); subject was used. */
    SUBJECT_FALLBACK,
}
