package com.becalm.android.data.repository

import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import javax.inject.Inject
import javax.inject.Singleton

public data class SourceOriginalContext(
    val emailBody: EmailBodyEntity?,
    val archivedOriginal: ArchivedOriginal?,
)

@Singleton
public class SourceOriginalResolver @Inject constructor(
    private val emailBodyRepository: EmailBodyRepository,
    private val sourceArtifactRepository: SourceArtifactRepository,
) {
    public suspend fun resolve(
        userId: String,
        event: RawIngestionEventEntity,
        fallbackRawEventIds: List<String> = emptyList(),
    ): SourceOriginalContext {
        val rawEventIds = listOf(event.id)
            .plus(fallbackRawEventIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val emailBody = if (event.sourceType in EMAIL_SOURCE_TYPES) {
            firstEmailBody(rawEventIds)
        } else {
            null
        }
        val archivedOriginal = firstArchivedOriginal(userId, rawEventIds)
        return SourceOriginalContext(
            emailBody = emailBody,
            archivedOriginal = archivedOriginal,
        )
    }

    private suspend fun firstEmailBody(rawEventIds: List<String>): EmailBodyEntity? {
        for (rawEventId in rawEventIds) {
            emailBodyRepository.getByRawEventId(rawEventId)?.let { return it }
        }
        return null
    }

    private suspend fun firstArchivedOriginal(
        userId: String,
        rawEventIds: List<String>,
    ): ArchivedOriginal? {
        for (rawEventId in rawEventIds) {
            sourceArtifactRepository.findMarkdownOriginal(userId, rawEventId)?.let { return it }
        }
        return null
    }

    private companion object {
        private val EMAIL_SOURCE_TYPES = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
        )
    }
}
