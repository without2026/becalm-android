package com.becalm.android.ui.persons

import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.RawEventCommitmentRow
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.domain.person.PersonIdentityResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Additional projection seam for raw-event drill-down fields that are not carried directly
 * on [RawIngestionEventEntity].
 */
public interface RawEventDetailProjectionPort {
    public suspend fun loadCommitmentQuotes(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<String>

    public suspend fun loadCommitmentSummaries(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventCommitmentSummary>

    public suspend fun loadCalendarAttendeesRaw(
        userId: String,
        event: RawIngestionEventEntity,
    ): String?

    public suspend fun loadThreadMessages(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventThreadMessageUi>

    public suspend fun loadParticipantCorrections(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventParticipantCorrectionRow>

    public suspend fun loadParticipantCorrectionChoices(userId: String): List<RawEventParticipantChoiceRow>
}

@Singleton
public class RoomBackedRawEventDetailProjectionPort @Inject constructor(
    private val commitmentDao: CommitmentDao,
    private val calendarEventDao: CalendarEventDao,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val personIndexDao: PersonIndexDao,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
) : RawEventDetailProjectionPort {
    override suspend fun loadCommitmentQuotes(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<String> {
        val sourceRefs = event.commitmentSourceRefs()
        return commitmentDao.findQuotesBySourceEventForUser(
            userId = userId,
            sourceEventId = event.id,
            sourceRefs = sourceRefs.ifEmpty { listOf(event.id) },
        )
    }

    override suspend fun loadCommitmentSummaries(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventCommitmentSummary> {
        val sourceRefs = event.commitmentSourceRefs()
        return commitmentDao.findRawEventCommitmentsBySourceEventForUser(
            userId = userId,
            sourceEventId = event.id,
            sourceRefs = sourceRefs.ifEmpty { listOf(event.id) },
        ).map { it.toUiSummary() }
    }

    override suspend fun loadCalendarAttendeesRaw(
        userId: String,
        event: RawIngestionEventEntity,
    ): String? {
        val sourceRef = event.sourceRef ?: return null
        if (event.sourceType !in setOf(SourceType.GOOGLE_CALENDAR, SourceType.OUTLOOK_CALENDAR)) {
            return null
        }
        return calendarEventDao.findBySourceRefForUser(
            userId = userId,
            sourceType = event.sourceType,
            sourceRef = sourceRef,
        )?.attendeesRaw
    }

    override suspend fun loadThreadMessages(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventThreadMessageUi> {
        val conversationRef = event.conversationRef?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        if (event.sourceType !in EMAIL_SOURCE_TYPES) return emptyList()
        return rawIngestionEventDao.findByConversationRefForUser(
            userId = userId,
            sourceType = event.sourceType,
            conversationRef = conversationRef,
            limit = THREAD_MESSAGE_LIMIT,
        ).map { raw ->
            RawEventThreadMessageUi(
                rawEventId = raw.id,
                title = raw.eventTitle?.trim()?.takeIf { it.isNotEmpty() },
                snippet = raw.eventSnippet?.trim()?.takeIf { it.isNotEmpty() },
                timestamp = raw.timestamp,
                isCurrent = raw.id == event.id,
            )
        }
    }

    override suspend fun loadParticipantCorrections(
        userId: String,
        event: RawIngestionEventEntity,
    ): List<RawEventParticipantCorrectionRow> =
        personIndexDao.findSourceEventParticipantsForUserAndEventRefs(
            userId = userId,
            sourceType = event.sourceType,
            sourceEventIds = event.candidateSourceEventIds(),
            sourceRefs = event.candidateSourceRefs(),
        )
            .filter { participant ->
                participant.relationToUser != "self" &&
                    participant.resolutionStatus.isReviewableParticipantStatus() &&
                    !participant.isSourceLocalSpeakerLabelOnly()
            }
            .sortedWith(
                compareByDescending<SourceEventParticipantEntity> { it.personId != null }
                    .thenByDescending { it.confidence }
                    .thenBy { it.role },
            )
            .map { it.toCorrectionRow() }

    override suspend fun loadParticipantCorrectionChoices(userId: String): List<RawEventParticipantChoiceRow> {
        val enrichmentRows = personEnrichmentRepository.observeAll().first()
        val enrichmentByRef = enrichmentRows.associateBy { it.personRef }
        return personIndexDao.findIdentitiesForUser(userId)
            .filterNot { it.isTechnicalIdentity() }
            .groupBy(PersonIdentityEntity::personId)
            .mapNotNull { (personId, identities) ->
                val primaryIdentity = identities.maxWithOrNull(
                    compareBy<PersonIdentityEntity> { it.verified }
                        .thenBy { it.confidence }
                        .thenBy { it.lastSeenAt },
                ) ?: return@mapNotNull null
                val enrichment = identities.firstNotNullOfOrNull { identity ->
                    enrichmentByRef[identity.rawValue]
                        ?: enrichmentByRef[identity.identityKey.substringAfter(':', identity.identityKey)]
                }
                val displayName = listOfNotNull(
                    enrichment?.displayName.sanitizeCorrectionDisplayName(),
                    enrichment?.nickname.sanitizeCorrectionDisplayName(),
                    primaryIdentity.displayName.sanitizeCorrectionDisplayName(),
                    primaryIdentity.displayNameHint.sanitizeCorrectionDisplayName(),
                    primaryIdentity.rawValue.sanitizeCorrectionDisplayName(),
                ).firstOrNull() ?: return@mapNotNull null
                RawEventParticipantChoiceRow(
                    personId = personId,
                    displayName = displayName,
                    detail = listOfNotNull(
                        enrichment?.company.sanitizeCorrectionDisplayName(),
                        enrichment?.title.sanitizeCorrectionDisplayName(),
                    ).firstOrNull(),
                    identityType = primaryIdentity.identityType,
                    normalizedValue = primaryIdentity.normalizedValue.takeIf { it.isNotBlank() }
                        ?: primaryIdentity.identityKey.substringAfter(':', primaryIdentity.rawValue),
                )
            }
            .distinctBy(RawEventParticipantChoiceRow::personId)
            .sortedBy { it.displayName.lowercase() }
    }

    private fun RawIngestionEventEntity.commitmentSourceRefs(): List<String> =
        listOfNotNull(
            sourceRef,
            "raw:$id",
            id,
            clientEventId,
        ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun RawIngestionEventEntity.candidateSourceEventIds(): List<String> =
        listOf(id, clientEventId, sourceRef?.removePrefix("raw:"))
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()

    private fun RawIngestionEventEntity.candidateSourceRefs(): List<String> =
        listOfNotNull(sourceRef, id, "raw:$id", clientEventId, clientEventId?.let { "raw:$it" })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun RawEventCommitmentRow.toUiSummary(): RawEventCommitmentSummary =
        RawEventCommitmentSummary(
            id = id,
            title = title,
            itemType = itemType,
            direction = direction,
            status = status,
            quote = quote,
        )

    private fun SourceEventParticipantEntity.toCorrectionRow(): RawEventParticipantCorrectionRow {
        val safeDisplayName = listOfNotNull(
            displayNameRaw.sanitizeCorrectionDisplayName(),
            organizationRaw.sanitizeCorrectionDisplayName(),
            normalizedValue.sanitizeCorrectionDisplayName(),
            emailRaw.sanitizeCorrectionDisplayName(),
            phoneRaw.sanitizeCorrectionDisplayName(),
        ).firstOrNull() ?: UNKNOWN_PARTICIPANT_DISPLAY_NAME
        val detail = listOfNotNull(
            organizationRaw.sanitizeCorrectionDisplayName(),
            titleRaw.sanitizeCorrectionDisplayName(),
            evidence?.trim()?.takeIf { it.isNotBlank() && !it.contains("@") },
        ).firstOrNull()
        return RawEventParticipantCorrectionRow(
            participantId = id,
            sourceEventId = sourceEventId,
            currentPersonId = personId,
            displayName = safeDisplayName,
            detail = detail,
            role = role,
            resolutionStatus = resolutionStatus,
            confidence = confidence,
        )
    }

    private fun PersonIdentityEntity.isTechnicalIdentity(): Boolean =
        identityType == "speaker_label" ||
            PersonIdentityResolver.isSpeakerLabelValue(rawValue)

    private fun SourceEventParticipantEntity.isSourceLocalSpeakerLabelOnly(): Boolean {
        if (identityType != "speaker_label" && identityType != "source_local_speaker") return false
        val hasRealContact = !emailRaw.isNullOrBlank() || !phoneRaw.isNullOrBlank() || !organizationRaw.isNullOrBlank()
        val hasRealDisplayName = !displayNameRaw.isNullOrBlank() &&
            !PersonIdentityResolver.isSpeakerLabelValue(displayNameRaw)
        return !hasRealContact && !hasRealDisplayName
    }

    private fun String?.sanitizeCorrectionDisplayName(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value == UNKNOWN_PARTICIPANT_DISPLAY_NAME) return null
        if (PersonIdentityResolver.isSpeakerLabelValue(value)) return null
        if (PersonIdentityResolver.normalizeEmailAnchor(value) != null) return null
        if (PersonIdentityResolver.normalizePhoneAnchor(value) != null) return null
        if (value.all { it.isDigit() || it == '-' || it == ' ' }) return null
        return value
    }

    private companion object {
        const val UNKNOWN_PARTICIPANT_DISPLAY_NAME: String = "이름 없는 사람"
        const val THREAD_MESSAGE_LIMIT: Int = 20
        val EMAIL_SOURCE_TYPES: Set<String> = setOf(
            SourceType.GMAIL,
            SourceType.OUTLOOK_MAIL,
            SourceType.NAVER_IMAP,
            SourceType.DAUM_IMAP,
        )
    }
}

private fun String.isReviewableParticipantStatus(): Boolean =
    this == "unresolved" || this == "suggested_self"

@Module
@InstallIn(SingletonComponent::class)
public abstract class RawEventDetailProjectionModule {

    @Binds
    @Singleton
    public abstract fun bindRawEventDetailProjectionPort(
        impl: RoomBackedRawEventDetailProjectionPort,
    ): RawEventDetailProjectionPort
}
