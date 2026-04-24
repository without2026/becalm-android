package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity

internal object RawEventDetailProjector {
    private const val SNIPPET_CHAR_LIMIT: Int = 200

    fun buildLoadedState(
        entity: RawIngestionEventEntity,
        emailBody: EmailBodyEntity?,
        commitmentQuotes: List<String>,
        attendeesRaw: String?,
    ): RawEventDetailUiState {
        val ui = emailBody?.let { EmailBodyUi(bodyPlain = it.bodyPlain, bodyHtml = it.bodyHtml) }
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
            attachmentCount = attachments.size,
            commitmentsExtractedCount = entity.commitmentsExtractedCount,
            loading = false,
        )
    }

    fun notFoundState(): RawEventDetailUiState =
        RawEventDetailUiState(loading = false, error = ERROR_EVENT_NOT_FOUND)
}
