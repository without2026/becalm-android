package com.becalm.android.worker.ingestion

import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.email.SourceRefEnvelope
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.domain.email.EmailPersonRef
import com.becalm.android.domain.email.EmailSnippetBuilder
import com.squareup.moshi.JsonAdapter
import java.util.UUID

internal class ImapRawEventMapper(
    private val config: ImapProviderConfig,
    private val sourceRefAdapter: JsonAdapter<SourceRefEnvelope>,
) {
    fun toEntity(
        message: ImapMessage,
        userId: String,
        mailboxKey: String,
        folderLabel: String,
    ): RawIngestionEventEntity {
        val sourceRefJson = sourceRefAdapter.toJson(
            SourceRefEnvelope(
                messageId = message.providerMessageId(),
                inReplyTo = message.inReplyTo,
                references = message.references,
            ),
        )
        val snippetResult = EmailSnippetBuilder.buildSnippet(
            bodyPlain = message.bodyPlain,
            bodyHtml = message.bodyHtml,
            subject = message.subject,
        )
        return RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = imapClientEventId(
                provider = config.provider,
                folder = folderLabel,
                providerMessageId = message.providerMessageId(),
            ),
            sourceType = config.sourceType,
            sourceRef = sourceRefJson,
            counterpartyRef = message.counterpartyRef(mailboxKey),
            eventTitle = message.subject,
            eventSnippet = snippetResult.snippet,
            folder = folderLabel,
            timestamp = message.sentAt,
        )
    }

    private fun ImapMessage.counterpartyRef(mailboxKey: String): String? = when (mailboxKey) {
        config.inboxMailboxKey -> EmailPersonRef.forInbox(fromEmail)
        config.sentMailboxKey -> EmailPersonRef.forSent(toAddresses)
        else -> null
    }
}
