package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.ArchivedOriginal

internal object RawEventDetailProjector {
    private const val SNIPPET_CHAR_LIMIT: Int = 200

    fun buildLoadedState(
        entity: RawIngestionEventEntity,
        emailBody: EmailBodyEntity?,
        archivedOriginal: ArchivedOriginal?,
        commitmentQuotes: List<String>,
        attendeesRaw: String?,
    ): RawEventDetailUiState {
        val ui = emailBody?.let { EmailBodyUi(bodyPlain = it.bodyPlain, bodyHtml = it.bodyHtml) }
        val archiveUi = archivedOriginal?.let {
            ArchivedOriginalUi(
                bodyText = it.markdown?.let(::markdownBodyText),
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
            emailBody = ui,
            archivedOriginal = archiveUi,
            attachmentCount = attachments.size,
            commitmentsExtractedCount = entity.commitmentsExtractedCount,
            loading = false,
        )
    }

    fun notFoundState(): RawEventDetailUiState =
        RawEventDetailUiState(loading = false, error = ERROR_EVENT_NOT_FOUND)

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
}
