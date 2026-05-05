package com.becalm.android.worker.ingestion

import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.remote.imap.ImapAttachmentMeta
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.EmailOriginalArchiveInput
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.domain.email.EmailSnippetBuilder
import com.becalm.android.domain.email.SourceKind
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.JsonAdapter
import java.util.UUID

internal class ImapMessagePersistence(
    private val emailBodyRepository: EmailBodyRepository,
    private val sourceArtifactRepository: SourceArtifactRepository,
    private val workScheduler: WorkScheduler,
    private val metricsStore: MetricsStore,
    private val stringListAdapter: JsonAdapter<List<String>>,
    private val attachmentListAdapter: JsonAdapter<List<ImapAttachmentMeta>>,
    private val logger: Logger,
    private val tag: String,
) {
    suspend fun persistEmailBody(
        message: ImapMessage,
        rawEventId: String,
        userId: String,
        sourceType: String,
        folderLabel: String,
        isGroupEmail: Boolean,
    ) {
        val snippetResult = EmailSnippetBuilder.buildSnippet(
            bodyPlain = message.bodyPlain,
            bodyHtml = message.bodyHtml,
            subject = message.subject,
        )
        val body = EmailBodyEntity(
            id = UUID.randomUUID().toString(),
            rawEventId = rawEventId,
            providerMessageId = message.providerMessageId(),
            folder = folderLabel,
            subject = message.subject,
            fromAddress = message.fromEmail?.let(::canonicalizeEmail),
            toAddresses = if (message.toAddresses.isEmpty()) {
                null
            } else {
                stringListAdapter.toJson(message.toAddresses.map { it.lowercase() })
            },
            bodyPlain = if (snippetResult.parseFailed) null else message.bodyPlain,
            bodyHtml = message.bodyHtml,
            attachmentsMeta = if (message.attachmentsMeta.isEmpty()) {
                null
            } else {
                attachmentListAdapter.toJson(message.attachmentsMeta)
            },
            rawHeaders = message.rawHeadersJson,
            parseFailed = snippetResult.parseFailed,
            groupEmail = isGroupEmail,
            receivedAt = message.sentAt,
        )
        emailBodyRepository.insert(body)
        runCatching {
            sourceArtifactRepository.archiveEmailOriginal(
                EmailOriginalArchiveInput(
                    userId = userId,
                    rawEventId = rawEventId,
                    sourceType = sourceType,
                    sourceRef = message.providerMessageId(),
                    occurredAt = message.sentAt,
                    title = message.subject,
                    folder = folderLabel,
                    fromAddress = message.fromEmail?.let(::canonicalizeEmail),
                    toAddresses = message.toAddresses.map { it.lowercase() },
                    attachmentsCount = message.attachmentsMeta.size,
                    bodyPlain = body.bodyPlain,
                    bodyHtml = body.bodyHtml,
                ),
            )
        }.onFailure { error ->
            logger.w(tag, "source archive write failed raw=${rawEventId.hashCode()} reason=${error.message}")
        }

        if (snippetResult.sourceKind == SourceKind.SUBJECT_FALLBACK) {
            metricsStore.incrementSubjectOnlySkipped()
            return
        }
        workScheduler.enqueueUpload()
    }
}
