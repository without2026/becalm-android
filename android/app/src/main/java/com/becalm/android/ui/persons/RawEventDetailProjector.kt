package com.becalm.android.ui.persons

import com.becalm.android.R
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.ArchivedOriginal
import com.becalm.android.ui.components.UiMessage
import org.jsoup.Jsoup

internal object RawEventDetailProjector {
    private const val SNIPPET_CHAR_LIMIT: Int = 200

    fun buildLoadedState(
        entity: RawIngestionEventEntity,
        emailBody: EmailBodyEntity?,
        archivedOriginal: ArchivedOriginal?,
        commitmentQuotes: List<String>,
        extractedCommitments: List<RawEventCommitmentSummary>,
        attendeesRaw: String?,
    ): RawEventDetailUiState {
        val ui = emailBody?.let {
            EmailBodyUi(
                bodyPlain = it.bodyPlain?.let(::emailBodyDisplayText),
                bodyHtml = it.bodyHtml,
            )
        }
        val archiveUi = archivedOriginal?.let {
            ArchivedOriginalUi(
                bodyText = it.markdown?.let(::markdownBodyText)?.let(::emailBodyDisplayText),
                deletedFromDevice = !it.exists,
                truncated = it.markdownTruncated,
            )
        }
        val attachments = AttachmentMetaParser.parse(emailBody?.attachmentsMeta)
        return RawEventDetailUiState(
            eventId = entity.id,
            sourceType = entity.sourceType,
            eventTitle = entity.eventTitle,
            timestamp = entity.timestamp,
            snippet = entity.eventSnippet?.take(SNIPPET_CHAR_LIMIT),
            durationSeconds = entity.durationSeconds,
            location = entity.location,
            attendeesRaw = attendeesRaw,
            commitmentQuotes = commitmentQuotes,
            extractedCommitments = extractedCommitments,
            emailBody = ui,
            archivedOriginal = archiveUi,
            attachmentCount = attachments.size,
            commitmentsExtractedCount = entity.commitmentsExtractedCount,
            syncStatus = entity.syncStatus,
            loading = false,
        )
    }

    fun notFoundState(): RawEventDetailUiState =
        RawEventDetailUiState(loading = false, error = UiMessage.resource(R.string.raw_event_detail_not_found))

    private fun markdownBodyText(markdown: String): String {
        val withoutFrontMatter = if (markdown.startsWith("---")) {
            val end = markdown.indexOf("\n---", startIndex = 3)
            if (end >= 0) markdown.substring(end + 4) else markdown
        } else {
            markdown
        }
        return withoutFrontMatter
            .lineSequence()
            .dropWhile { it.isBlank() }
            .dropWhile { it.startsWith("# ") }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun emailBodyDisplayText(raw: String): String {
        if (!looksLikeHtml(raw)) return raw
        val withLineHints = raw
            .replace(HTML_BREAK_TAG, "\n")
            .replace(HTML_BLOCK_CLOSE_TAG, "\n$0")
        return runCatching {
            normalizeHtmlDisplayWhitespace(Jsoup.parse(withLineHints).wholeText())
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: raw
    }

    private fun looksLikeHtml(raw: String): Boolean =
        HTML_TAG_PATTERN.containsMatchIn(raw) || HTML_ENTITY_PATTERN.containsMatchIn(raw)

    private fun normalizeHtmlDisplayWhitespace(raw: String): String =
        raw
            .lines()
            .joinToString("\n") { line ->
                line.replace(HORIZONTAL_WHITESPACE, " ").trim()
            }
            .replace(EXCESSIVE_NEWLINES, "\n\n")
            .trim()

    private val HTML_TAG_PATTERN: Regex = Regex(
        pattern = "<\\s*/?\\s*(?:html|body|head|meta|title|style|script|div|p|br|span|a|table|tbody|thead|tr|td|th|ul|ol|li|strong|b|i|em|font|blockquote|section|article|pre|code|h[1-6])\\b[^>]*>",
        option = RegexOption.IGNORE_CASE,
    )
    private val HTML_ENTITY_PATTERN: Regex = Regex(
        pattern = "&(?:nbsp|amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);",
        option = RegexOption.IGNORE_CASE,
    )
    private val HTML_BREAK_TAG: Regex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val HTML_BLOCK_CLOSE_TAG: Regex = Regex(
        pattern = "</(?:p|div|li|tr|h[1-6]|section|article|blockquote)>",
        option = RegexOption.IGNORE_CASE,
    )
    private val HORIZONTAL_WHITESPACE: Regex = Regex("[\\t\\x0B\\f\\r ]+")
    private val EXCESSIVE_NEWLINES: Regex = Regex("\\n{3,}")
}
