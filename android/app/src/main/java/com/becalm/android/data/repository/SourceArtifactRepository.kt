package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.db.dao.SourceArtifactDao
import com.becalm.android.data.local.db.entity.SOURCE_ARTIFACT_TYPE_MARKDOWN_ORIGINAL
import com.becalm.android.data.local.db.entity.SourceArtifactEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

public data class SourceArchiveSummary(
    val count: Int,
    val totalBytes: Long,
)

public data class SourceArchiveDeleteResult(
    val deletedCount: Int,
    val failedCount: Int,
)

public data class ArchivedOriginal(
    val artifact: SourceArtifactEntity,
    val markdown: String?,
    val markdownTruncated: Boolean,
) {
    public val exists: Boolean get() = markdown != null
}

public interface SourceArtifactRepository {
    public suspend fun archiveEmailOriginal(input: EmailOriginalArchiveInput): SourceArtifactEntity?
    public suspend fun archiveMeetingTranscript(input: MeetingTranscriptArchiveInput): SourceArtifactEntity?
    public suspend fun findMarkdownOriginal(userId: String, rawEventId: String): ArchivedOriginal?
    public suspend fun summary(userId: String): SourceArchiveSummary
    public suspend fun deleteBefore(userId: String, cutoff: Instant): SourceArchiveDeleteResult
    public suspend fun deleteAllForUser(userId: String)
}

public data class MeetingTranscriptArchiveInput(
    val userId: String,
    val rawEventId: String,
    val sourceRef: String?,
    val occurredAt: Instant,
    val title: String?,
    val text: String,
)

public data class EmailOriginalArchiveInput(
    val userId: String,
    val rawEventId: String,
    val sourceType: String,
    val sourceRef: String?,
    val occurredAt: Instant,
    val title: String?,
    val folder: String,
    val fromAddress: String?,
    val toAddresses: List<String>,
    val attachmentsCount: Int,
    val bodyPlain: String?,
    val bodyHtml: String?,
)

@Singleton
public class SourceArtifactRepositoryImpl @Inject constructor(
    private val dao: SourceArtifactDao,
    private val store: SourceArchiveStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceArtifactRepository {

    override suspend fun archiveEmailOriginal(input: EmailOriginalArchiveInput): SourceArtifactEntity? =
        withContext(ioDispatcher) {
            val body = input.bodyPlain?.takeIf { it.isNotBlank() }
                ?: input.bodyHtml?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val markdown = buildEmailMarkdown(input, body)
            val write = store.writeMarkdown(
                userId = input.userId,
                sourceType = input.sourceType,
                rawEventId = input.rawEventId,
                occurredAt = input.occurredAt,
                markdown = markdown,
            )
            val now = Clock.System.now()
            val entity = SourceArtifactEntity(
                id = stableId(input.userId, input.sourceType, input.sourceRef, input.rawEventId),
                userId = input.userId,
                rawEventId = input.rawEventId,
                sourceType = input.sourceType,
                sourceRef = input.sourceRef,
                artifactType = SOURCE_ARTIFACT_TYPE_MARKDOWN_ORIGINAL,
                localPath = write.relativePath,
                sha256 = write.sha256,
                byteSize = write.byteSize,
                occurredAt = input.occurredAt,
                createdAt = now,
                updatedAt = now,
            )
            try {
                dao.upsert(entity)
                entity
            } catch (t: Throwable) {
                store.delete(write.relativePath)
                throw t
            }
        }

    override suspend fun archiveMeetingTranscript(input: MeetingTranscriptArchiveInput): SourceArtifactEntity? =
        withContext(ioDispatcher) {
            val body = input.text.takeIf { it.isNotBlank() } ?: return@withContext null
            val markdown = buildMeetingTranscriptMarkdown(input, body)
            val write = store.writeMarkdown(
                userId = input.userId,
                sourceType = com.becalm.android.data.remote.dto.SourceType.MEETING,
                rawEventId = input.rawEventId,
                occurredAt = input.occurredAt,
                markdown = markdown,
            )
            val now = Clock.System.now()
            val entity = SourceArtifactEntity(
                id = stableId(
                    input.userId,
                    com.becalm.android.data.remote.dto.SourceType.MEETING,
                    input.sourceRef,
                    input.rawEventId,
                ),
                userId = input.userId,
                rawEventId = input.rawEventId,
                sourceType = com.becalm.android.data.remote.dto.SourceType.MEETING,
                sourceRef = input.sourceRef,
                artifactType = SOURCE_ARTIFACT_TYPE_MARKDOWN_ORIGINAL,
                localPath = write.relativePath,
                sha256 = write.sha256,
                byteSize = write.byteSize,
                occurredAt = input.occurredAt,
                createdAt = now,
                updatedAt = now,
            )
            try {
                dao.upsert(entity)
                entity
            } catch (t: Throwable) {
                store.delete(write.relativePath)
                throw t
            }
        }

    override suspend fun findMarkdownOriginal(userId: String, rawEventId: String): ArchivedOriginal? =
        withContext(ioDispatcher) {
            val artifact = dao.findForRawEvent(
                userId = userId,
                rawEventId = rawEventId,
                artifactType = SOURCE_ARTIFACT_TYPE_MARKDOWN_ORIGINAL,
            ) ?: return@withContext null
            val read = store.readText(artifact.localPath, maxChars = ARCHIVED_ORIGINAL_PREVIEW_CHAR_LIMIT)
            ArchivedOriginal(
                artifact = artifact,
                markdown = read?.text,
                markdownTruncated = read?.truncated == true,
            )
        }

    override suspend fun summary(userId: String): SourceArchiveSummary =
        withContext(ioDispatcher) {
            SourceArchiveSummary(
                count = dao.countForUser(userId),
                totalBytes = dao.totalBytesForUser(userId),
            )
        }

    override suspend fun deleteBefore(userId: String, cutoff: Instant): SourceArchiveDeleteResult =
        withContext(ioDispatcher) {
            val artifacts = dao.findBefore(userId, cutoff.toEpochMilliseconds())
            val results = artifacts.map { artifact -> artifact to store.delete(artifact.localPath) }
            val failed = results.count { !it.second }
            val deletedIds = results.filter { it.second }.map { it.first.id }
            val deletedRows = if (deletedIds.isNotEmpty()) dao.deleteByIds(userId, deletedIds) else 0
            SourceArchiveDeleteResult(deletedCount = deletedRows, failedCount = failed)
        }

    override suspend fun deleteAllForUser(userId: String) {
        withContext(ioDispatcher) {
            store.deleteUserArchive(userId)
            dao.deleteAllForUser(userId)
        }
    }

    private fun buildEmailMarkdown(input: EmailOriginalArchiveInput, body: String): String =
        buildString {
            appendLine("---")
            appendLine("raw_event_id: ${frontMatter(input.rawEventId)}")
            appendLine("source_type: ${frontMatter(input.sourceType)}")
            appendLine("source_ref: ${frontMatter(input.sourceRef ?: "null")}")
            appendLine("occurred_at: ${frontMatter(input.occurredAt.toString())}")
            appendLine("title: ${frontMatter(input.title ?: "(no subject)")}")
            appendLine("folder: ${frontMatter(input.folder)}")
            appendLine("from: ${frontMatter(input.fromAddress ?: "null")}")
            appendLine("to:")
            input.toAddresses.forEach { appendLine("  - ${frontMatter(it)}") }
            appendLine("attachments_count: ${input.attachmentsCount}")
            appendLine("---")
            appendLine()
            appendLine("# ${heading(input.title)}")
            appendLine()
            appendLine(body.trim())
        }

    private fun buildMeetingTranscriptMarkdown(input: MeetingTranscriptArchiveInput, body: String): String =
        buildString {
            appendLine("---")
            appendLine("raw_event_id: ${frontMatter(input.rawEventId)}")
            appendLine("source_type: meeting")
            appendLine("source_ref: ${frontMatter(input.sourceRef ?: "null")}")
            appendLine("occurred_at: ${frontMatter(input.occurredAt.toString())}")
            appendLine("title: ${frontMatter(input.title ?: "(untitled meeting transcript)")}")
            appendLine("---")
            appendLine()
            appendLine("# ${heading(input.title)}")
            appendLine()
            appendLine(body.trim())
        }

    private fun frontMatter(value: String): String =
        value.replace(Regex("[\\r\\n\\u0000-\\u001f]"), " ").trim()

    private fun heading(value: String?): String =
        value?.replace(Regex("[\\r\\n]"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: "(no subject)"

    private fun stableId(userId: String, sourceType: String, sourceRef: String?, rawEventId: String): String =
        UUID.nameUUIDFromBytes(
            "source-artifact:$userId:$sourceType:${sourceRef ?: rawEventId}:$SOURCE_ARTIFACT_TYPE_MARKDOWN_ORIGINAL"
                .toByteArray(Charsets.UTF_8),
        ).toString()

    private companion object {
        private const val ARCHIVED_ORIGINAL_PREVIEW_CHAR_LIMIT = 64 * 1024
    }
}
