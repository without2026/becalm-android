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
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIndexDirtySourceEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.repository.coalesceSourceEventParticipantPersons
import com.becalm.android.data.repository.preferStrongestIdentityRows
import com.becalm.android.data.repository.preferStrongestPersonRows
import com.becalm.android.data.repository.toPersonEntityOrNull
import com.becalm.android.data.repository.toPersonIdentityEntities
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonIdentityTypes
import com.becalm.android.domain.person.PersonMatchingEventPolicy
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

private fun SourceEventParticipantEntity.isSourceLocalSpeakerLabelOnly(): Boolean {
    val sourceLocalIdentity = identityType?.let(PersonIdentityTypes::isSourceLocal) == true
    if (!sourceLocalIdentity) return false
    val hasRealContact = !emailRaw.isNullOrBlank() || !phoneRaw.isNullOrBlank() || !organizationRaw.isNullOrBlank()
    val hasRealDisplayName = !displayNameRaw.isNullOrBlank() &&
        !PersonIdentityResolver.isSpeakerLabelValue(displayNameRaw)
    return !hasRealContact && !hasRealDisplayName
}

@HiltWorker
public class PersonInteractionIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val databaseProvider: Provider<BeCalmDatabase>,
    private val rawDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val selfIdentityAnchorDaoProvider: Provider<SelfIdentityAnchorDao>,
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
        val sourceParticipants = projectionInput.sourceParticipants.coalesceSourceEventParticipantPersons()

        val sourceRecords = buildSourceRecords(
            rawEvents = projectionInput.rawEvents,
            commitments = projectionInput.commitments,
            sourceParticipants = sourceParticipants,
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

        val incomingRepairedPersons = sourceParticipants
            .mapNotNull { it.toPersonEntityOrNull() }
            .preferStrongestPersonRows()
        val existingRepairedPersons = incomingRepairedPersons
            .map { it.id }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { dao.findPersonsByIds(userId = userId, personIds = it) }
            .orEmpty()
        val repairedPersons = (existingRepairedPersons + incomingRepairedPersons).preferStrongestPersonRows()

        val incomingRepairedIdentities = sourceParticipants
            .flatMap { it.toPersonIdentityEntities() }
            .preferStrongestIdentityRows()
        val existingRepairedIdentities = incomingRepairedIdentities
            .map { it.id }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { dao.findIdentitiesByIds(userId = userId, identityIds = it) }
            .orEmpty()
        val repairedIdentities = (existingRepairedIdentities + incomingRepairedIdentities).preferStrongestIdentityRows()
        val learnedIdentityRules = LearnedIdentityRules.from(
            userId = userId,
            identities = projectionInput.personIdentities + repairedIdentities,
            sourceParticipants = sourceParticipants,
            selfAnchors = projectionInput.selfIdentityAnchors,
        )
        val commitmentLinkedSourcePeople = projectionInput.commitmentLinkedSourcePeople()
        val builder = PersonIndexBuild(
            userId = userId,
            blockedPersonRefs = blockedPersonRefs,
            learnedIdentityRules = learnedIdentityRules,
            commitmentLinkedSourcePeople = commitmentLinkedSourcePeople,
        )
        changedRecords.forEach { it.applyTo(builder) }

        val snapshot = builder.snapshot()
        val affectedPersonIds = (
            previousAffectedPersonIds +
                repairedPersons.map { it.id } +
                snapshot.persons.map { it.id } +
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
            if (repairedPersons.isNotEmpty()) txDao.upsertPersons(repairedPersons)
            if (snapshot.persons.isNotEmpty()) txDao.upsertPersons(snapshot.persons)
            if (repairedIdentities.isNotEmpty()) txDao.upsertIdentities(repairedIdentities)
            if (snapshot.identities.isNotEmpty()) txDao.upsertIdentities(snapshot.identities)
            if (snapshot.interactions.isNotEmpty()) txDao.upsertInteractions(snapshot.interactions)
            if (snapshot.unmatched.isNotEmpty()) txDao.upsertUnmatchedInteractions(snapshot.unmatched)
            if (dirtySources.isNotEmpty()) txDao.deleteDirtySourcesByIds(userId, dirtySources.map { it.id })
        }

        logger.d(
            TAG,
                "indexed mode=${projectionInput.mode} dirtySources=${dirtySources.size} " +
                "changedSources=${changedRecords.size} " +
                "personsRepaired=${repairedPersons.size} " +
                "identities=${snapshot.identities.size + repairedIdentities.size} interactions=${snapshot.interactions.size} " +
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
                personIdentities = personIndexDaoProvider.get().findIdentitiesForUser(userId),
                selfIdentityAnchors = selfIdentityAnchorDaoProvider.get().observeActive(userId).first(),
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
        val dirtyCommitments = commitmentIds.takeIf { it.isNotEmpty() }
            ?.let { commitmentDaoProvider.get().findLiveByIdsForPersonIndex(userId, it) }
            ?: emptyList()
        val rawEventsByIds = rawEventIds.takeIf { it.isNotEmpty() }
            ?.let { rawDaoProvider.get().findByIdsForUser(userId, it) }
            ?: emptyList()
        val rawEventsByCommitmentSourceRefs = dirtyCommitments
            .mapNotNull { it.sourceRef?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { rawDaoProvider.get().findBySourceRefsForUser(userId, it) }
            ?: emptyList()
        val sourceParticipantRawEventIds = (rawEventIds + rawEventsByCommitmentSourceRefs.map { it.id })
            .distinct()
        val dirtySourceParticipants = sourceParticipantRawEventIds.takeIf { it.isNotEmpty() }
            ?.let { personIndexDaoProvider.get().findSourceEventParticipantsForUserAndEventIds(userId, it) }
            ?: emptyList()
        val rawEventsBySourceParticipantRefs = dirtySourceParticipants
            .mapNotNull { it.sourceRef?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.let { rawDaoProvider.get().findBySourceRefsForUser(userId, it) }
            ?: emptyList()
        return ProjectionInput(
            mode = "dirty",
            dirtySources = dirtySources,
            rawEvents = (rawEventsByIds + rawEventsByCommitmentSourceRefs + rawEventsBySourceParticipantRefs)
                .distinctBy { it.id },
            commitments = dirtyCommitments,
            sourceParticipants = dirtySourceParticipants,
            commitmentParticipants = commitmentIds.takeIf { it.isNotEmpty() }
                ?.let { personIndexDaoProvider.get().findCommitmentParticipantsForUserAndCommitmentIds(userId, it) }
                ?: emptyList(),
            personIdentities = personIndexDaoProvider.get().findIdentitiesForUser(userId),
            selfIdentityAnchors = selfIdentityAnchorDaoProvider.get().observeActive(userId).first(),
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
        val rawBySourceRef = rawEvents
            .mapNotNull { raw -> raw.sourceRef?.let { (raw.sourceType to it) to raw } }
            .toMap()
        val commitmentsById = commitments.associateBy { it.id }

        sourceParticipants
            .groupBy { it.sourceType to it.sourceEventId }
            .forEach { (sourceKey, participants) ->
                val raw = rawById[sourceKey.second] ?: participants.firstNotNullOfOrNull { participant ->
                    participant.sourceRef?.let { sourceRef -> rawBySourceRef[participant.sourceType to sourceRef] }
                }
                val localSourceEventId = raw?.id ?: sourceKey.second
                val key = SourceKey(
                    sourceType = sourceKey.first,
                    sourceRef = "raw:$localSourceEventId",
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
                            val commitmentSourceRef = commitment.sourceRef
                            builder.addCommitmentParticipant(
                                participant = participant,
                                commitment = commitment,
                                raw = commitmentSourceRef?.let { rawBySourceRef[commitment.sourceType to it] },
                            )
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
        private val learnedIdentityRules: LearnedIdentityRules,
        private val commitmentLinkedSourcePeople: Set<SourcePersonKey>,
    ) {
        private val now = Clock.System.now()
        private val persons = linkedMapOf<String, PersonEntity>()
        private val identities = linkedMapOf<String, PersonIdentityEntity>()
        private val interactions = linkedMapOf<String, PersonInteractionEntity>()
        private val unmatched = linkedMapOf<String, UnmatchedPersonInteractionEntity>()

        fun addSourceParticipant(
            participant: SourceEventParticipantEntity,
            raw: RawIngestionEventEntity?,
        ) {
            val sourceLocalSpeakerReviewCandidate =
                SourceParticipantReviewPolicy.isSourceLocalSpeakerReviewCandidate(participant)
            if (participant.isSourceLocalSpeakerLabelOnly() && !sourceLocalSpeakerReviewCandidate) return
            val localSourceEventId = raw?.id ?: participant.sourceEventId
            val sourceRef = "raw:$localSourceEventId"
            val kind = interactionKindFor(participant.sourceType)
            val anchor = participant.emailRaw
                ?: participant.phoneRaw
                ?: participant.normalizedValue
                ?: participant.displayNameRaw
                ?: participant.organizationRaw
            if (shouldSuppress(anchor)) return
            if (
                shouldSuppressServiceLifecyclePersonProjection(participant, raw, anchor) &&
                participant.toSourcePersonKey() !in commitmentLinkedSourcePeople
            ) {
                return
            }
            val occurredAt = raw?.timestamp ?: participant.createdAt
            if (participant.personId.isNullOrBlank()) {
                if (participant.resolutionStatus == "suggested_self" && participant.relationToUser == "self") return
                when (val selfMatch = learnedIdentityRules.matchSelf(participant)) {
                    SelfIdentityRuleMatch.SELF_RESOLVED -> return
                    SelfIdentityRuleMatch.SUGGESTED_SELF -> {
                        if (participant.resolutionStatus !in REVIEWABLE_PARTICIPANT_STATUSES) return
                    }
                    null -> Unit
                }
                learnedIdentityRules.matchPerson(participant)?.let { learnedPerson ->
                    upsertInteraction(
                        personId = learnedPerson.personId,
                        sourceType = participant.sourceType,
                        sourceRef = sourceRef,
                        kind = kind,
                        role = participant.role,
                        direction = raw?.folder?.let(::folderDirection),
                        status = null,
                        occurredAt = occurredAt,
                        title = raw?.eventTitle,
                        snippet = raw?.eventSnippet ?: participant.evidence,
                        confidence = maxOf(participant.confidence, learnedPerson.confidence),
                    )
                    return
                }
                if (
                    participant.resolutionStatus in REVIEWABLE_PARTICIPANT_STATUSES &&
                    SourceParticipantReviewPolicy.isReviewableForManualReview(participant)
                ) {
                    val suggestedLabel = if (sourceLocalSpeakerReviewCandidate) {
                        null
                    } else {
                        participant.displayNameRaw
                            ?: participant.emailRaw
                            ?: participant.phoneRaw
                            ?: participant.organizationRaw
                            ?: participant.normalizedValue
                    }
                    upsertUnmatched(
                        sourceType = participant.sourceType,
                        sourceRef = sourceRef,
                        kind = kind,
                        title = raw?.eventTitle,
                        snippet = raw?.eventSnippet ?: participant.evidence,
                        suggestedLabel = suggestedLabel,
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
            raw: RawIngestionEventEntity?,
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
                sourceEventId = raw?.id ?: commitment.sourceEventIdForInteraction(),
                commitmentId = commitment.id,
                occurredAt = commitment.sourceEventOccurredAt,
                title = commitment.title,
                snippet = commitment.quote,
                confidence = commitment.confidence.coerceAtLeast(participant.confidence),
            )
        }

        fun snapshot(): Snapshot =
            Snapshot(
                persons = persons.values.toList(),
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
            if (PersonIdentityTypes.isSourceLocal(identityType)) return
            val normalized = when (identityType) {
                "email" -> PersonIdentityResolver.normalizeRelationEmailAnchor(participant.emailRaw)
                    ?: PersonIdentityResolver.normalizeRelationEmailAnchor(participant.normalizedValue)
                    ?: return
                "phone" -> PersonIdentityResolver.normalizePhoneAnchor(participant.phoneRaw)
                    ?: PersonIdentityResolver.normalizePhoneAnchor(participant.normalizedValue)
                    ?: return
                else -> participant.normalizedValue ?: return
            }
            val identityKey = "$identityType:$normalized"
            val rawValue = when (identityType) {
                "email" -> participant.emailRaw ?: normalized
                "phone" -> participant.phoneRaw ?: normalized
                "organization" -> participant.organizationRaw ?: normalized
                "name" -> participant.displayNameRaw ?: normalized
                else -> normalized
            }
            val previous = identities[identityKey]
            val candidate = PersonIdentityEntity(
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
            val strongest = listOfNotNull(previous, candidate).preferStrongestIdentityRows().single()
            identities[identityKey] = strongest.copy(
                confidence = maxOf(previous?.confidence ?: 0.0, candidate.confidence),
                verified = previous?.verified == true || candidate.verified,
                lastSeenAt = maxOf(previous?.lastSeenAt ?: lastSeenAt, lastSeenAt),
                createdAt = previous?.createdAt ?: candidate.createdAt,
                updatedAt = maxOf(previous?.updatedAt ?: candidate.updatedAt, candidate.updatedAt),
            )
        }

        private fun shouldSuppress(raw: String?): Boolean =
            PersonIdentityResolver.isLikelyAutomated(raw) ||
                PersonIdentityResolver.isBlocked(raw, blockedPersonRefs)

        private fun shouldSuppressServiceLifecyclePersonProjection(
            participant: SourceEventParticipantEntity,
            raw: RawIngestionEventEntity?,
            anchor: String?,
        ): Boolean =
            PersonMatchingEventPolicy.isLikelyServiceAccountNotification(
                title = raw?.eventTitle,
                snippet = raw?.eventSnippet ?: participant.evidence,
                suggestedLabel = participant.displayNameRaw
                    ?: participant.organizationRaw
                    ?: participant.emailRaw
                    ?: anchor,
            )

        private fun upsertInteraction(
            personId: String,
            sourceType: String,
            sourceRef: String,
            kind: String,
            role: String,
            direction: String?,
            status: String?,
            sourceEventId: String? = sourceRef.removePrefix("raw:").takeIf { sourceRef.startsWith("raw:") },
            commitmentId: String? = sourceRef.removePrefix("commitment:").takeIf { sourceRef.startsWith("commitment:") },
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
                sourceEventId = sourceEventId,
                commitmentId = commitmentId,
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
            val safeSuggestedLabel = suggestedLabel?.takeUnless(PersonIdentityResolver::isSpeakerLabelValue)
            if (PersonMatchingEventPolicy.isLikelyServiceAccountNotification(title, snippet, suggestedLabel)) return
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
                suggestedLabel = safeSuggestedLabel,
                occurredAt = occurredAt,
                createdAt = now,
            )
        }

        private fun folderDirection(folder: String?): String? = when (folder?.uppercase()) {
            "INBOX" -> "received"
            "SENT" -> "sent"
            else -> null
        }

        private fun CommitmentEntity.sourceEventIdForInteraction(): String? =
            sourceRef?.trim()
                ?.takeIf { it.startsWith("raw:") }
                ?.removePrefix("raw:")
                ?.takeIf { it.isNotBlank() }

        private fun SourceEventParticipantEntity.toSourcePersonKey(): SourcePersonKey? {
            val participantPersonId = personId ?: return null
            return SourcePersonKey(
                sourceType = sourceType,
                sourceEventId = sourceEventId,
                personId = participantPersonId,
            )
        }

    }

    private data class Snapshot(
        val persons: List<PersonEntity>,
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
        val personIdentities: List<PersonIdentityEntity>,
        val selfIdentityAnchors: List<SelfIdentityAnchorEntity>,
    )

    private fun ProjectionInput.commitmentLinkedSourcePeople(): Set<SourcePersonKey> {
        if (commitments.isEmpty() || commitmentParticipants.isEmpty()) return emptySet()
        val rawBySourceRef = rawEvents
            .mapNotNull { raw -> raw.sourceRef?.let { (raw.sourceType to it) to raw } }
            .toMap()
        val commitmentsById = commitments.associateBy { it.id }
        return commitmentParticipants.mapNotNull { participant ->
            val commitment = commitmentsById[participant.commitmentId] ?: return@mapNotNull null
            val sourceEventId = commitment.sourceRef
                ?.let { rawBySourceRef[commitment.sourceType to it]?.id }
                ?: commitment.sourceRef
                    ?.trim()
                    ?.takeIf { it.startsWith("raw:") }
                    ?.removePrefix("raw:")
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SourcePersonKey(
                sourceType = commitment.sourceType,
                sourceEventId = sourceEventId,
                personId = participant.personId,
            )
        }.toSet()
    }

    private data class SourcePersonKey(
        val sourceType: String,
        val sourceEventId: String,
        val personId: String,
    )

    private data class LearnedPersonIdentityRule(
        val personId: String,
        val identityType: String,
        val normalizedValue: String,
        val confidence: Double,
    )

    private enum class SelfIdentityRuleMatch {
        SELF_RESOLVED,
        SUGGESTED_SELF,
    }

    private data class LearnedSelfIdentityRule(
        val identityType: String,
        val normalizedValue: String,
        val match: SelfIdentityRuleMatch,
    )

    private class LearnedIdentityRules(
        private val personRulesByKey: Map<String, LearnedPersonIdentityRule>,
        private val selfRulesByKey: Map<String, SelfIdentityRuleMatch>,
    ) {
        fun matchPerson(participant: SourceEventParticipantEntity): LearnedPersonIdentityRule? =
            identityKeysForRuleMatching(participant, AUTO_PERSON_IDENTITY_TYPES)
                .firstNotNullOfOrNull { personRulesByKey[it] }

        fun matchSelf(participant: SourceEventParticipantEntity): SelfIdentityRuleMatch? =
            identityKeysForRuleMatching(participant, AUTO_SELF_IDENTITY_TYPES)
                .firstNotNullOfOrNull { selfRulesByKey[it] }

        companion object {
            private val AUTO_PERSON_IDENTITY_TYPES = setOf("email", "phone")
            private val AUTO_SELF_IDENTITY_TYPES = setOf("email", "phone", "alias")
            private val STRONG_SELF_IDENTITY_TYPES = setOf("email", "phone")

            fun from(
                userId: String,
                identities: List<PersonIdentityEntity>,
                sourceParticipants: List<SourceEventParticipantEntity>,
                selfAnchors: List<SelfIdentityAnchorEntity>,
            ): LearnedIdentityRules {
                val personRules = linkedMapOf<String, LearnedPersonIdentityRule>()
                identities.asSequence()
                    .filter { it.userId == userId && it.identityType in AUTO_PERSON_IDENTITY_TYPES }
                    .forEach { identity ->
                        val normalized = normalizeIdentityForRule(identity.identityType, identity.normalizedValue)
                            ?: return@forEach
                        val key = identityRuleKey(identity.identityType, normalized)
                        val previous = personRules[key]
                        if (previous == null || identity.confidence > previous.confidence) {
                            personRules[key] = LearnedPersonIdentityRule(
                                personId = identity.personId,
                                identityType = identity.identityType,
                                normalizedValue = normalized,
                                confidence = identity.confidence,
                            )
                        }
                    }

                val selfRules = linkedMapOf<String, SelfIdentityRuleMatch>()
                selfAnchors.asSequence()
                    .filter { it.userId == userId && it.status == "active" && it.scope != "source_event" }
                    .mapNotNull { it.toLearnedSelfIdentityRule() }
                    .forEach { rule ->
                        selfRules[identityRuleKey(rule.identityType, rule.normalizedValue)] = rule.match
                    }
                sourceParticipants.asSequence()
                    .filter { it.userId == userId && it.resolutionStatus == "self_resolved" }
                    .flatMap { identityKeysForRuleMatching(it, AUTO_SELF_IDENTITY_TYPES).asSequence() }
                    .forEach { key -> selfRules[key] = SelfIdentityRuleMatch.SELF_RESOLVED }

                return LearnedIdentityRules(
                    personRulesByKey = personRules,
                    selfRulesByKey = selfRules,
                )
            }

            private fun identityKeysForRuleMatching(
                participant: SourceEventParticipantEntity,
                types: Set<String>,
            ): List<String> =
                buildList {
                    if ("email" in types) {
                        PersonIdentityResolver.normalizeRelationEmailAnchor(participant.emailRaw)
                            ?.let { add(identityRuleKey("email", it)) }
                        participant.normalizedValue
                            .takeIf { participant.identityType == "email" }
                            ?.let(PersonIdentityResolver::normalizeRelationEmailAnchor)
                            ?.let { add(identityRuleKey("email", it)) }
                    }
                    if ("phone" in types) {
                        PersonIdentityResolver.normalizePhoneAnchor(participant.phoneRaw)
                            ?.let { add(identityRuleKey("phone", it)) }
                        participant.normalizedValue
                            .takeIf { participant.identityType == "phone" }
                            ?.let(PersonIdentityResolver::normalizePhoneAnchor)
                            ?.let { add(identityRuleKey("phone", it)) }
                    }
                    if ("alias" in types) {
                        participant.normalizedValue
                            .takeIf { participant.identityType in setOf("alias", "name") }
                            ?.let(PersonIdentityResolver::normalizeAlias)
                            ?.let { add(identityRuleKey("alias", it)) }
                        participant.displayNameRaw
                            ?.let(PersonIdentityResolver::normalizeAlias)
                            ?.let { add(identityRuleKey("alias", it)) }
                    }
                }.distinct()

            private fun SelfIdentityAnchorEntity.toLearnedSelfIdentityRule(): LearnedSelfIdentityRule? {
                val identityType = when (anchorType) {
                    "auth_email",
                    "provider_email",
                    "email",
                    -> "email"

                    "phone" -> "phone"
                    "alias",
                    "name",
                    -> "alias"

                    else -> return null
                }
                val normalized = normalizeIdentityForRule(identityType, normalizedValue) ?: return null
                val match = if (identityType in STRONG_SELF_IDENTITY_TYPES || trust == "user_confirmed") {
                    SelfIdentityRuleMatch.SELF_RESOLVED
                } else {
                    SelfIdentityRuleMatch.SUGGESTED_SELF
                }
                return LearnedSelfIdentityRule(
                    identityType = identityType,
                    normalizedValue = normalized,
                    match = match,
                )
            }

            private fun normalizeIdentityForRule(identityType: String, value: String?): String? =
                when (identityType) {
                    "email" -> PersonIdentityResolver.normalizeRelationEmailAnchor(value)
                    "phone" -> PersonIdentityResolver.normalizePhoneAnchor(value)
                    "alias",
                    "name",
                    -> PersonIdentityResolver.normalizeAlias(value)

                    else -> null
                }

            private fun identityRuleKey(identityType: String, normalizedValue: String): String =
                "$identityType:$normalizedValue"
        }
    }

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
        private val REVIEWABLE_PARTICIPANT_STATUSES = setOf("unresolved", "suggested_self")
    }
}
