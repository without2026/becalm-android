package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.domain.person.PersonMemoryCommitment
import com.becalm.android.domain.person.PersonMemoryIdentity
import com.becalm.android.domain.person.PersonMemoryInput
import com.becalm.android.domain.person.PersonMemoryInteraction
import com.becalm.android.domain.person.PersonMemoryParticipant
import com.becalm.android.domain.person.PersonMemoryVoiceEvidence
import com.becalm.android.domain.person.PersonVoiceChunkReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

public class PersonMemoryInputCollector @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val commitmentDao: CommitmentDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    public suspend fun collect(
        userId: String,
        personId: String,
        generatedAt: Instant = Clock.System.now(),
        interactionLimit: Int = DEFAULT_INTERACTION_LIMIT,
        participantLimit: Int = DEFAULT_PARTICIPANT_LIMIT,
        commitmentLimit: Int = DEFAULT_COMMITMENT_LIMIT,
    ): PersonMemoryInput? = withContext(ioDispatcher) {
        val person = personIndexDao.findPersonForMemory(userId, personId)
        val identities = personIndexDao.findIdentitiesForMemory(userId, personId)
        val sourceParticipants = personIndexDao.findSourceEventParticipantsForMemory(
            userId = userId,
            personId = personId,
            limit = participantLimit,
        )
        val excludedSourceRefs = sourceParticipants
            .filterNot { it.isCounterpartyMemorySafe() }
            .map { it.memorySourceRef() }
            .toSet()
        val participants = sourceParticipants.filter { it.isCounterpartyMemorySafe() }
        val interactions = personIndexDao.findInteractionsForMemory(
            userId = userId,
            personId = personId,
            limit = interactionLimit,
        ).filterNot { it.sourceRef in excludedSourceRefs }
        val commitmentParticipants = personIndexDao.findCommitmentParticipantsForMemory(
            userId = userId,
            personId = personId,
            limit = commitmentLimit,
        )
        val commitmentIds = (
            interactions.mapNotNull { it.commitmentId ?: it.sourceRef.commitmentIdFromRef() } +
                commitmentParticipants.map { it.commitmentId }
            )
            .distinct()
            .take(commitmentLimit)
        val commitments = if (commitmentIds.isEmpty()) {
            emptyList()
        } else {
            commitmentDao.findLiveByIdsForPersonIndex(userId = userId, ids = commitmentIds)
                .filterNot { it.sourceRef in excludedSourceRefs }
        }
        val keptCommitmentRefs = commitments.map { "commitment:${it.id}" }.toSet()
        val memoryInteractions = interactions.filterNot {
            it.sourceRef.startsWith("commitment:") && it.sourceRef !in keptCommitmentRefs
        }

        if (
            identities.isEmpty() &&
            participants.isEmpty() &&
            memoryInteractions.isEmpty() &&
            commitments.isEmpty()
        ) {
            return@withContext null
        }

        PersonMemoryInput(
            userId = userId,
            personId = personId,
            displayName = person?.displayName ?: identityDisplayNameFallback(identities)
                ?: participantDisplayNameFallback(participants)
                ?: personId,
            generatedAt = generatedAt,
            identities = identities.map { it.toMemoryIdentity() },
            participants = participants.map { it.toMemoryParticipant() },
            interactions = memoryInteractions.map { it.toMemoryInteraction() },
            commitments = commitments.map { it.toMemoryCommitment() },
            voiceEvidence = participants.mapNotNull { it.toMemoryVoiceEvidence(userId = userId, personId = personId) },
        )
    }

    private fun identityDisplayNameFallback(identities: List<PersonIdentityEntity>): String? =
        identities.firstNotNullOfOrNull { identity ->
            identity.displayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: identity.displayNameHint?.trim()?.takeIf { it.isNotEmpty() }
                ?: identity.rawValue.trim().takeIf { it.isNotEmpty() }
        }

    private fun participantDisplayNameFallback(participants: List<SourceEventParticipantEntity>): String? =
        participants.firstNotNullOfOrNull { participant ->
            participant.displayNameRaw?.trim()?.takeIf { it.isNotEmpty() }
                ?: participant.emailRaw?.trim()?.takeIf { it.isNotEmpty() }
                ?: participant.phoneRaw?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun PersonIdentityEntity.toMemoryIdentity(): PersonMemoryIdentity =
        PersonMemoryIdentity(
            identityType = identityType,
            value = normalizedValue.takeIf { it.isNotBlank() } ?: identityValue.takeIf { it.isNotBlank() } ?: rawValue,
            verified = verified,
            sourceRef = sourceRef,
        )

    private fun SourceEventParticipantEntity.toMemoryParticipant(): PersonMemoryParticipant =
        PersonMemoryParticipant(
            sourceRef = memorySourceRef(),
            sourceType = sourceType,
            role = role,
            relationToUser = relationToUser,
            displayName = displayNameRaw,
            organization = organizationRaw,
            title = titleRaw,
            evidence = evidence,
            occurredAt = createdAt,
        )

    private fun SourceEventParticipantEntity.isCounterpartyMemorySafe(): Boolean =
        !relationToUser.equals("self", ignoreCase = true) &&
            !resolutionStatus.equals("self_resolved", ignoreCase = true) &&
            !resolutionStatus.equals("suggested_self", ignoreCase = true)

    private fun SourceEventParticipantEntity.toMemoryVoiceEvidence(
        userId: String,
        personId: String,
    ): PersonMemoryVoiceEvidence? {
        if (sourceType !in AUDIO_SOURCE_TYPES) return null
        if (!role.equals("speaker", ignoreCase = true)) return null
        val speakerLabel = displayNameRaw?.trim()
            ?: normalizedValue?.trim()
            ?: return null
        if (!speakerLabel.startsWith("SPEAKER_", ignoreCase = true)) return null
        return PersonMemoryVoiceEvidence(
            sourceRef = memorySourceRef(),
            sourceType = sourceType,
            speakerLabel = speakerLabel,
            chunkFileName = PersonVoiceChunkReference.fileName(
                userId = userId,
                personId = personId,
                sourceEventId = sourceEventId,
                speakerLabel = speakerLabel,
            ),
            evidence = evidence,
            occurredAt = createdAt,
        )
    }

    private fun PersonInteractionEntity.toMemoryInteraction(): PersonMemoryInteraction =
        PersonMemoryInteraction(
            sourceRef = sourceRef,
            sourceType = sourceType,
            interactionKind = interactionKind,
            title = title,
            snippet = snippet,
            occurredAt = occurredAt,
        )

    private fun CommitmentEntity.toMemoryCommitment(): PersonMemoryCommitment =
        PersonMemoryCommitment(
            commitmentId = id,
            sourceRef = "commitment:$id",
            itemType = itemType,
            title = title,
            status = memoryStatus(),
            quote = quote,
            occurredAt = sourceEventOccurredAt,
        )

    private fun CommitmentEntity.memoryStatus(): String? =
        when (itemType) {
            CommitmentItemType.SCHEDULE -> scheduleStatus
            CommitmentItemType.DECISION -> decisionStatus
            else -> actionState
        }

    private fun SourceEventParticipantEntity.memorySourceRef(): String =
        "raw:$sourceEventId"

    private fun String.commitmentIdFromRef(): String? =
        takeIf { it.startsWith("commitment:") }?.removePrefix("commitment:")?.takeIf { it.isNotBlank() }

    private companion object {
        private const val DEFAULT_INTERACTION_LIMIT = 50
        private const val DEFAULT_PARTICIPANT_LIMIT = 50
        private const val DEFAULT_COMMITMENT_LIMIT = 20
        private val AUDIO_SOURCE_TYPES = setOf("voice", "call_recording", "meeting")
    }
}
