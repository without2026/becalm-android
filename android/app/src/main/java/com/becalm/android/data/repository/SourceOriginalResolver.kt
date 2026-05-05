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
    ): SourceOriginalContext {
        val emailBody = if (event.sourceType in EMAIL_SOURCE_TYPES) {
            emailBodyRepository.getByRawEventId(event.id)
        } else {
            null
        }
        val archivedOriginal = sourceArtifactRepository.findMarkdownOriginal(userId, event.id)
        return SourceOriginalContext(
            emailBody = emailBody,
            archivedOriginal = archivedOriginal,
        )
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
