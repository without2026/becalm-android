package com.becalm.android.worker

import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceExtractionResponse
import com.becalm.android.data.repository.PersonIndexDirtySources
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.toPersonEntityOrNull
import com.becalm.android.data.repository.toPersonIdentityEntityOrNull
import kotlinx.datetime.Instant

internal class StructuredExtractionPersister(
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val commitmentDao: CommitmentDao,
    private val personIndexDao: PersonIndexDao,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao,
) {
    suspend fun persist(
        userId: String,
        entity: RawIngestionEventEntity,
        body: SourceExtractionResponse,
        now: Instant,
    ): StructuredExtractionPersistStats {
        val selfIdentityAnchors = selfIdentityAnchorDao.findActiveForMatching(
            userId = userId,
            sourceEventId = entity.id,
        )
        val relevantItems = body.items.filterUserRelevantItems(
            rawCounterpartyRef = entity.counterpartyRef,
            participants = body.sourceEventParticipants,
            selfIdentityAnchors = selfIdentityAnchors,
        )
        val commitmentEntities = relevantItems.mapIndexed { index, dto ->
            dto.toTrackableCommitmentEntity(
                rawEventId = entity.id,
                index = index,
                userId = userId,
                sourceRef = entity.sourceRef,
                sourceType = entity.sourceType,
                sourceEventTitle = entity.eventTitle,
                sourceEventOccurredAt = entity.timestamp,
                now = now,
            )
        }
        if (commitmentEntities.isNotEmpty()) {
            commitmentDao.insertAll(commitmentEntities)
        }

        val sourceParticipants = body.sourceEventParticipants.mapIndexed { index, dto ->
            dto.toSourceEventParticipantEntity(
                userId = userId,
                sourceEventId = entity.id,
                sourceType = entity.sourceType,
                sourceRef = entity.sourceRef,
                index = index,
                now = now,
                selfIdentityAnchors = selfIdentityAnchors,
            )
        }
        if (sourceParticipants.isNotEmpty()) {
            personIndexDao.upsertPersons(sourceParticipants.mapNotNull { it.toPersonEntityOrNull() })
            personIndexDao.upsertIdentities(sourceParticipants.mapNotNull { it.toPersonIdentityEntityOrNull() })
            personIndexDao.upsertSourceEventParticipants(sourceParticipants)
        }

        val fallbackPersonId = sourceParticipants.singleSourceCounterpartyPersonId()
        val commitmentParticipants = commitmentEntities.mapIndexedNotNull { index, commitment ->
            commitment.toCommitmentParticipantEntity(
                userId = userId,
                index = index,
                fallbackPersonId = fallbackPersonId,
                now = now,
                selfIdentityAnchors = selfIdentityAnchors,
            )
        }
        if (commitmentParticipants.isNotEmpty()) {
            personIndexDao.upsertCommitmentParticipants(commitmentParticipants)
        }
        val dirtySources =
            PersonIndexDirtySources.rawEvent(
                userId = userId,
                sourceType = entity.sourceType,
                sourceEventId = entity.id,
                reason = "local_extraction",
                now = now,
            )
                .let(::listOf) +
                PersonIndexDirtySources.forCommitments(
                    commitments = commitmentEntities,
                    reason = "local_extraction",
                    now = now,
                )
        if (dirtySources.isNotEmpty()) {
            personIndexDao.upsertDirtySources(dirtySources)
        }

        rawIngestionEventDao.update(
            entity.copy(
                commitmentsExtractedCount = relevantItems.size,
                eventSnippet = relevantItems.firstOrNull()?.quote?.take(SNIPPET_MAX_CHARS),
                lastAttemptAt = now,
                syncStatus = STATUS_PENDING,
            ),
        )

        sourceStatusRepository.recordSyncSuccess(entity.sourceType, now)
        processingStatusRepository.recordSynced(entity.sourceType, relevantItems.size)
        SourceGraphChangedNotifier(workScheduler).notifyChanged()
        logger.d(TAG, "extraction persisted id=${redact(entity.id)} items=${relevantItems.size}")
        return StructuredExtractionPersistStats(
            itemCount = relevantItems.size,
            sourceParticipantCount = sourceParticipants.size,
            commitmentParticipantCount = commitmentParticipants.size,
        )
    }

    private companion object {
        private const val TAG = "StructuredExtractionPersister"
        private const val SNIPPET_MAX_CHARS = 200
    }
}

internal data class StructuredExtractionPersistStats(
    val itemCount: Int,
    val sourceParticipantCount: Int,
    val commitmentParticipantCount: Int,
)
