package com.becalm.android.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonIndexDirtySourceEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.SourceInteractionKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

private fun interactionKindFor(sourceType: String): String = SourceInteractionKind.forSourceType(sourceType)

@HiltWorker
public class PersonInteractionIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val databaseProvider: Provider<BeCalmDatabase>,
    private val rawDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val userPrefsStore: UserPrefsStore,
    private val workScheduler: WorkScheduler,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "no active user — skipping person index")
            return@withContext Result.success()
        }

        val dao = personIndexDaoProvider.get()
        val dirtySources = dao.findDirtySourcesForUser(userId, DIRTY_LIMIT)
        val blockedPersonRefs = userPrefsStore.observeBlockedPersonRefs().first()
        val projectionInput = loadProjectionInput(
            userId = userId,
            dirtySources = dirtySources,
        )

        val sourceRecords = buildSourceRecords(
            rawEvents = projectionInput.rawEvents,
            commitments = projectionInput.commitments,
            sourceParticipants = projectionInput.sourceParticipants,
            commitmentParticipants = projectionInput.commitmentParticipants,
        )
        val changedRecords = sourceRecords
        val changedKeys = if (projectionInput.mode == "full") {
            emptyList()
        } else {
            (dirtySources.map { it.toSourceKey() } + changedRecords.map { it.key }).distinct()
        }
        val previousAffectedPersonIds = findPreviousAffectedPersonIds(
            userId = userId,
            mode = projectionInput.mode,
            changedKeys = changedKeys,
        )

        if (projectionInput.mode == "dirty" && changedKeys.isEmpty()) {
            if (dirtySources.isNotEmpty()) {
                dao.deleteDirtySourcesByIds(userId, dirtySources.map { it.id })
            }
            logger.d(
                TAG,
                "person index unchanged sources=${sourceRecords.size} dirtySources=${dirtySources.size} " +
                    "mode=${projectionInput.mode}",
            )
            notifyMatchingState(userId)
            return@withContext Result.success()
        }

        val builder = PersonIndexBuild(
            userId = userId,
            blockedPersonRefs = blockedPersonRefs,
        )
        changedRecords.forEach { it.applyTo(builder) }

        val snapshot = builder.snapshot()
        val affectedPersonIds = (
            previousAffectedPersonIds +
                snapshot.identities.map { it.personId } +
                snapshot.interactions.map { it.personId }
            )
            .filter { it.isNotBlank() }
            .distinct()
        databaseProvider.get().withTransaction {
            val txDao = personIndexDaoProvider.get()
            if (projectionInput.mode == "full") {
                txDao.deleteInteractionsForUser(userId)
                txDao.deleteUnmatchedInteractionsForUser(userId)
            } else {
                changedKeys.forEach { key ->
                    txDao.deleteInteractionsForSourceKey(
                        userId = userId,
                        key = key,
                    )
                    txDao.deleteUnmatchedInteractionsForSourceKey(
                        userId = userId,
                        key = key,
                    )
                }
            }
            if (snapshot.identities.isNotEmpty()) txDao.upsertIdentities(snapshot.identities)
            if (snapshot.interactions.isNotEmpty()) txDao.upsertInteractions(snapshot.interactions)
            if (snapshot.unmatched.isNotEmpty()) txDao.upsertUnmatchedInteractions(snapshot.unmatched)
            if (dirtySources.isNotEmpty()) txDao.deleteDirtySourcesByIds(userId, dirtySources.map { it.id })
        }

        logger.d(
            TAG,
                "indexed mode=${projectionInput.mode} dirtySources=${dirtySources.size} " +
                "changedSources=${changedRecords.size} " +
                "identities=${snapshot.identities.size} interactions=${snapshot.interactions.size} " +
                "unmatched=${snapshot.unmatched.size}",
        )
        notifyMatchingState(userId)
        enqueueProfileMemoryForAffectedPeople(affectedPersonIds)
        Result.success()
    }

    private suspend fun findPreviousAffectedPersonIds(
        userId: String,
        mode: String,
        changedKeys: List<SourceKey>,
    ): List<String> {
        val dao = personIndexDaoProvider.get()
        return if (mode == "full") {
            dao.findInteractionPersonIdsForUser(userId)
        } else {
            changedKeys.flatMap { key ->
                dao.findInteractionsForSourceKey(
                    userId = userId,
                    key = key,
                )
            }.map { it.personId }
        }
    }

    private fun enqueueProfileMemoryForAffectedPeople(personIds: List<String>) {
        personIds.distinct().forEach { personId ->
            workScheduler.enqueueProfileMemory(personId)
        }
    }

    private suspend fun loadProjectionInput(
        userId: String,
        dirtySources: List<PersonIndexDirtySourceEntity>,
    ): ProjectionInput {
        if (dirtySources.isEmpty()) {
            return ProjectionInput(
                mode = "full",
                dirtySources = emptyList(),
                rawEvents = rawDaoProvider.get().findAllForUser(userId),
                commitments = commitmentDaoProvider.get().findLiveForPersonIndex(userId),
                sourceParticipants = personIndexDaoProvider.get().findSourceEventParticipantsForUser(userId),
                commitmentParticipants = personIndexDaoProvider.get().findCommitmentParticipantsForUser(userId),
            )
        }

        val rawEventIds = dirtySources
            .mapNotNull { it.sourceRef.removePrefix("raw:").takeIf { id -> it.sourceRef.startsWith("raw:") && id.isNotBlank() } }
            .distinct()
        val commitmentIds = dirtySources
            .mapNotNull {
                it.sourceRef.removePrefix("commitment:")
                    .takeIf { id -> it.sourceRef.startsWith("commitment:") && id.isNotBlank() }
            }
            .distinct()
        return ProjectionInput(
            mode = "dirty",
            dirtySources = dirtySources,
            rawEvents = rawEventIds.takeIf { it.isNotEmpty() }
                ?.let { rawDaoProvider.get().findByIdsForUser(userId, it) }
                ?: emptyList(),
            commitments = commitmentIds.takeIf { it.isNotEmpty() }
                ?.let { commitmentDaoProvider.get().findLiveByIdsForPersonIndex(userId, it) }
                ?: emptyList(),
            sourceParticipants = rawEventIds.takeIf { it.isNotEmpty() }
                ?.let { personIndexDaoProvider.get().findSourceEventParticipantsForUserAndEventIds(userId, it) }
                ?: emptyList(),
            commitmentParticipants = commitmentIds.takeIf { it.isNotEmpty() }
                ?.let { personIndexDaoProvider.get().findCommitmentParticipantsForUserAndCommitmentIds(userId, it) }
                ?: emptyList(),
        )
    }

    private suspend fun notifyMatchingState(userId: String) {
        val unmatchedCount = personIndexDaoProvider.get().countUnmatchedInteractions(userId)
        MatchingRequiredNotifier.update(
            context = applicationContext,
            userPrefsStore = userPrefsStore,
            unmatchedCount = unmatchedCount,
            logger = logger,
        )
    }

    private fun buildSourceRecords(
        rawEvents: List<RawIngestionEventEntity>,
        commitments: List<CommitmentEntity>,
        sourceParticipants: List<SourceEventParticipantEntity>,
        commitmentParticipants: List<CommitmentParticipantEntity>,
    ): List<SourceRecord> {
        val records = mutableListOf<SourceRecord>()
        val rawById = rawEvents.associateBy { it.id }
        val commitmentsById = commitments.associateBy { it.id }

        sourceParticipants
            .groupBy { it.sourceType to it.sourceEventId }
            .forEach { (sourceKey, participants) ->
                val raw = rawById[sourceKey.second]
                val key = SourceKey(
                    sourceType = sourceKey.first,
                    sourceRef = "raw:${sourceKey.second}",
                    interactionKind = interactionKindFor(sourceKey.first),
                )
                records += SourceRecord(
                    key = key,
                    applyTo = { builder ->
                        participants.forEach { participant ->
                            builder.addSourceParticipant(participant, raw)
                        }
                    },
                )
            }

        commitmentParticipants
            .groupBy { it.commitmentId }
            .forEach { (commitmentId, participants) ->
                val commitment = commitmentsById[commitmentId] ?: return@forEach
                val key = SourceKey(
                    sourceType = commitment.sourceType,
                    sourceRef = "commitment:$commitmentId",
                    interactionKind = "commitment",
                )
                records += SourceRecord(
                    key = key,
                    applyTo = { builder ->
                        participants.forEach { participant ->
                            builder.addCommitmentParticipant(participant, commitment)
                        }
                    },
                )
            }
        return records
            .groupBy { it.key }
            .map { (key, grouped) ->
                SourceRecord(
                    key = key,
                    applyTo = { builder -> grouped.forEach { it.applyTo(builder) } },
                )
            }
    }

    private class PersonIndexBuild(
        private val userId: String,
        private val blockedPersonRefs: Set<String>,
    ) {
        private val now = Clock.System.now()
        private val identities = linkedMapOf<String, PersonIdentityEntity>()
        private val interactions = linkedMapOf<String, PersonInteractionEntity>()
        private val unmatched = linkedMapOf<String, UnmatchedPersonInteractionEntity>()

        fun addSourceParticipant(
            participant: SourceEventParticipantEntity,
            raw: RawIngestionEventEntity?,
        ) {
            val sourceRef = "raw:${participant.sourceEventId}"
            val kind = interactionKindFor(participant.sourceType)
            val anchor = participant.emailRaw
                ?: participant.phoneRaw
                ?: participant.normalizedValue
                ?: participant.displayNameRaw
                ?: participant.organizationRaw
            if (shouldSuppress(anchor)) return
            val occurredAt = raw?.timestamp ?: participant.createdAt
            if (participant.personId.isNullOrBlank()) {
                if (participant.resolutionStatus == "unresolved") {
                    upsertUnmatched(
                        sourceType = participant.sourceType,
                        sourceRef = sourceRef,
                        kind = kind,
                        title = raw?.eventTitle,
                        snippet = raw?.eventSnippet ?: participant.evidence,
                        suggestedLabel = participant.displayNameRaw
                            ?: participant.emailRaw
                            ?: participant.phoneRaw
                            ?: participant.organizationRaw
                            ?: participant.normalizedValue,
                        occurredAt = occurredAt,
                    )
                }
                return
            }
            upsertIdentity(participant, occurredAt)
            upsertInteraction(
                personId = participant.personId,
                sourceType = participant.sourceType,
                sourceRef = sourceRef,
                kind = kind,
                role = participant.role,
                direction = raw?.folder?.let(::folderDirection),
                status = null,
                occurredAt = occurredAt,
                title = raw?.eventTitle,
                snippet = raw?.eventSnippet ?: participant.evidence,
                confidence = participant.confidence,
            )
        }

        fun addCommitmentParticipant(
            participant: CommitmentParticipantEntity,
            commitment: CommitmentEntity,
        ) {
            if (participant.personId.isBlank()) return
            upsertInteraction(
                personId = participant.personId,
                sourceType = commitment.sourceType,
                sourceRef = "commitment:${commitment.id}",
                kind = "commitment",
                role = commitment.itemType,
                direction = commitment.direction,
                status = when (commitment.itemType) {
                    CommitmentItemType.SCHEDULE -> commitment.scheduleStatus
                    CommitmentItemType.DECISION -> commitment.decisionStatus
                    else -> commitment.actionState
                },
                occurredAt = commitment.sourceEventOccurredAt,
                title = commitment.title,
                snippet = commitment.quote,
                confidence = commitment.confidence.coerceAtLeast(participant.confidence),
            )
        }

        fun snapshot(): Snapshot =
            Snapshot(
                identities = identities.values.toList(),
                interactions = interactions.values.toList(),
                unmatched = unmatched.values.toList(),
            )

        private fun upsertIdentity(
            participant: SourceEventParticipantEntity,
            lastSeenAt: kotlinx.datetime.Instant,
        ) {
            val personId = participant.personId ?: return
            val identityType = participant.identityType ?: return
            val normalized = participant.normalizedValue ?: return
            val identityKey = "$identityType:$normalized"
            val rawValue = when (identityType) {
                "email" -> participant.emailRaw ?: normalized
                "phone" -> participant.phoneRaw ?: normalized
                "organization" -> participant.organizationRaw ?: normalized
                "name" -> participant.displayNameRaw ?: normalized
                else -> normalized
            }
            val previous = identities[identityKey]
            identities[identityKey] = PersonIdentityEntity(
                id = PersonIdentityResolver.stableIdentityId(userId, identityKey),
                userId = userId,
                personId = personId,
                identityKey = identityKey,
                identityType = identityType,
                rawValue = rawValue,
                displayNameHint = participant.displayNameRaw ?: participant.organizationRaw ?: rawValue,
                identityValue = rawValue,
                normalizedValue = normalized,
                displayName = participant.displayNameRaw,
                sourceType = participant.sourceType,
                sourceRef = participant.sourceRef,
                confidence = maxOf(previous?.confidence ?: 0.0, participant.confidence),
                isPrimary = true,
                verified = participant.resolutionStatus == "resolved",
                lastSeenAt = maxOf(previous?.lastSeenAt ?: lastSeenAt, lastSeenAt),
                createdAt = previous?.createdAt ?: participant.createdAt,
                updatedAt = maxOf(previous?.updatedAt ?: participant.createdAt, participant.createdAt),
            )
        }

        private fun shouldSuppress(raw: String?): Boolean =
            PersonIdentityResolver.isLikelyAutomated(raw) ||
                PersonIdentityResolver.isBlocked(raw, blockedPersonRefs)

        private fun upsertInteraction(
            personId: String,
            sourceType: String,
            sourceRef: String,
            kind: String,
            role: String,
            direction: String?,
            status: String?,
            occurredAt: kotlinx.datetime.Instant,
            title: String?,
            snippet: String?,
            confidence: Double,
        ) {
            val id = UUID.nameUUIDFromBytes(
                "interaction:$userId:$sourceType:$sourceRef:$personId:$kind".toByteArray(Charsets.UTF_8),
            ).toString()
            interactions[id] = PersonInteractionEntity(
                id = id,
                userId = userId,
                personId = personId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                interactionKind = kind,
                role = role,
                direction = direction,
                status = status,
                occurredAt = occurredAt,
                title = title,
                snippet = snippet,
                confidence = confidence.coerceIn(0.0, 1.0),
            )
        }

        private fun upsertUnmatched(
            sourceType: String,
            sourceRef: String,
            kind: String,
            title: String?,
            snippet: String?,
            suggestedLabel: String?,
            occurredAt: kotlinx.datetime.Instant,
        ) {
            val hasUserVisibleContent = listOf(title, snippet, suggestedLabel).any { !it.isNullOrBlank() }
            if (!hasUserVisibleContent) return
            val id = UUID.nameUUIDFromBytes(
                "unmatched:$userId:$sourceType:$sourceRef:$kind".toByteArray(Charsets.UTF_8),
            ).toString()
            unmatched[id] = UnmatchedPersonInteractionEntity(
                id = id,
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                interactionKind = kind,
                title = title,
                snippet = snippet,
                suggestedLabel = suggestedLabel,
                occurredAt = occurredAt,
                createdAt = now,
            )
        }

        private fun folderDirection(folder: String?): String? = when (folder?.uppercase()) {
            "INBOX" -> "received"
            "SENT" -> "sent"
            else -> null
        }

    }

    private data class Snapshot(
        val identities: List<PersonIdentityEntity>,
        val interactions: List<PersonInteractionEntity>,
        val unmatched: List<UnmatchedPersonInteractionEntity>,
    )

    private data class ProjectionInput(
        val mode: String,
        val dirtySources: List<PersonIndexDirtySourceEntity>,
        val rawEvents: List<RawIngestionEventEntity>,
        val commitments: List<CommitmentEntity>,
        val sourceParticipants: List<SourceEventParticipantEntity>,
        val commitmentParticipants: List<CommitmentParticipantEntity>,
    )

    private data class SourceKey(
        val sourceType: String,
        val sourceRef: String,
        val interactionKind: String,
    )

    private data class SourceRecord(
        val key: SourceKey,
        val applyTo: (PersonIndexBuild) -> Unit,
    )

    private fun PersonIndexDirtySourceEntity.toSourceKey(): SourceKey =
        SourceKey(sourceType = sourceType, sourceRef = sourceRef, interactionKind = interactionKind)

    private suspend fun PersonIndexDao.findInteractionsForSourceKey(
        userId: String,
        key: SourceKey,
    ): List<PersonInteractionEntity> =
        if (key.sourceType == SourceInteractionKind.COMMITMENT && key.interactionKind == SourceInteractionKind.COMMITMENT) {
            findInteractionsForSourceRefKind(
                userId = userId,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
            )
        } else {
            findInteractionsForSource(
                userId = userId,
                sourceType = key.sourceType,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
            )
        }

    private suspend fun PersonIndexDao.deleteInteractionsForSourceKey(
        userId: String,
        key: SourceKey,
    ): Int =
        if (key.sourceType == SourceInteractionKind.COMMITMENT && key.interactionKind == SourceInteractionKind.COMMITMENT) {
            deleteInteractionsForSourceRefKind(
                userId = userId,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
            )
        } else {
            deleteInteractionsForSource(
                userId = userId,
                sourceType = key.sourceType,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
            )
        }

    private suspend fun PersonIndexDao.deleteUnmatchedInteractionsForSourceKey(
        userId: String,
        key: SourceKey,
    ): Int =
        if (key.sourceType == SourceInteractionKind.COMMITMENT && key.interactionKind == SourceInteractionKind.COMMITMENT) {
            deleteUnmatchedInteractionsForSourceRefKind(
                userId = userId,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
            )
        } else {
            deleteUnmatchedInteractionsForSource(
                userId = userId,
                sourceType = key.sourceType,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
            )
        }

    private companion object {
        private const val TAG = "PersonIndexWorker"
        private const val DIRTY_LIMIT = 500
    }
}
