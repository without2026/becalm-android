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
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonIndexSourceStateEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.domain.person.PersonIdentityResolver
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

private fun interactionKindFor(sourceType: String): String = when {
    sourceType.contains("calendar") -> "calendar"
    sourceType.contains("mail") || sourceType.contains("imap") || sourceType == "gmail" -> "email"
    sourceType == "voice" || sourceType == "call_recording" -> "call"
    else -> sourceType
}

@HiltWorker
public class PersonInteractionIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val databaseProvider: Provider<BeCalmDatabase>,
    private val rawDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val personIndexDaoProvider: Provider<PersonIndexDao>,
    private val userPrefsStore: UserPrefsStore,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "no active user — skipping person index")
            return@withContext Result.success()
        }

        val rawEvents = rawDaoProvider.get().findAllForUser(userId)
        val commitments = commitmentDaoProvider.get().findLiveForPersonIndex(userId)
        val sourceParticipants = personIndexDaoProvider.get().findSourceEventParticipantsForUser(userId)
        val commitmentParticipants = personIndexDaoProvider.get().findCommitmentParticipantsForUser(userId)
        val existingStates = personIndexDaoProvider.get().findSourceStatesForUser(userId)
        val blockedPersonRefs = userPrefsStore.observeBlockedPersonRefs().first()

        val suppressionFingerprint = suppressionFingerprint(blockedPersonRefs)
        val sourceRecords = buildSourceRecords(
            suppressionFingerprint = suppressionFingerprint,
            rawEvents = rawEvents,
            commitments = commitments,
            sourceParticipants = sourceParticipants,
            commitmentParticipants = commitmentParticipants,
        )
        val recordsByKey = sourceRecords.associateBy { it.key }
        val existingStateByKey = existingStates.associateBy { it.key() }
        val changedRecords = sourceRecords.filter { record ->
            existingStateByKey[record.key]?.fingerprint != record.fingerprint
        }
        val obsoleteStates = existingStates.filter { state ->
            state.key() !in recordsByKey
        }

        if (changedRecords.isEmpty() && obsoleteStates.isEmpty()) {
            logger.d(TAG, "person index unchanged sources=${sourceRecords.size}")
            notifyMatchingState(userId)
            return@withContext Result.success()
        }

        val builder = PersonIndexBuild(
            userId = userId,
            blockedPersonRefs = blockedPersonRefs,
        )
        changedRecords.forEach { it.applyTo(builder) }

        val snapshot = builder.snapshot()
        databaseProvider.get().withTransaction {
            val dao = personIndexDaoProvider.get()
            (changedRecords.map { it.key } + obsoleteStates.map { it.key() })
                .distinct()
                .forEach { key ->
                    dao.deleteInteractionsForSource(
                        userId = userId,
                        sourceType = key.sourceType,
                        sourceRef = key.sourceRef,
                        interactionKind = key.interactionKind,
                    )
                    dao.deleteUnmatchedInteractionsForSource(
                        userId = userId,
                        sourceType = key.sourceType,
                        sourceRef = key.sourceRef,
                        interactionKind = key.interactionKind,
                    )
                    if (key !in recordsByKey) {
                        dao.deleteSourceState(
                            userId = userId,
                            sourceType = key.sourceType,
                            sourceRef = key.sourceRef,
                            interactionKind = key.interactionKind,
                        )
                    }
                }
            if (snapshot.identities.isNotEmpty()) dao.upsertIdentities(snapshot.identities)
            if (snapshot.interactions.isNotEmpty()) dao.upsertInteractions(snapshot.interactions)
            if (snapshot.unmatched.isNotEmpty()) dao.upsertUnmatchedInteractions(snapshot.unmatched)
            val sourceStates = changedRecords.map { it.toState(userId) }
            if (sourceStates.isNotEmpty()) dao.upsertSourceStates(sourceStates)
        }

        logger.d(
            TAG,
                "indexed changedSources=${changedRecords.size} obsoleteSources=${obsoleteStates.size} " +
                "identities=${snapshot.identities.size} interactions=${snapshot.interactions.size} " +
                "unmatched=${snapshot.unmatched.size}",
        )
        notifyMatchingState(userId)
        Result.success()
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
        suppressionFingerprint: String,
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
                    fingerprint = fingerprintOf(
                        suppressionFingerprint,
                        raw?.eventTitle,
                        raw?.eventSnippet,
                        raw?.timestamp?.toString(),
                        *participants
                            .map {
                                "p|${it.id}|${it.personId}|${it.role}|${it.relationToUser}|${it.identityType}|" +
                                    "${it.normalizedValue}|${it.displayNameRaw}|${it.emailRaw}|${it.phoneRaw}|" +
                                    "${it.organizationRaw}|${it.resolutionStatus}|${it.confidence}|${it.createdAt}"
                            }
                            .sorted()
                            .toTypedArray(),
                    ),
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
                    fingerprint = fingerprintOf(
                        suppressionFingerprint,
                        commitment.itemType,
                        commitment.direction,
                        commitment.scheduleStatus,
                        commitment.decisionStatus,
                        commitment.title,
                        commitment.quote,
                        commitment.sourceEventOccurredAt.toString(),
                        commitment.actionState,
                        commitment.confidence.toString(),
                        commitment.updatedAt.toString(),
                        commitment.deletedAt?.toString(),
                        *participants
                            .map { "cp|${it.id}|${it.personId}|${it.role}|${it.evidence}|${it.confidence}|${it.createdAt}" }
                            .sorted()
                            .toTypedArray(),
                    ),
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
                    fingerprint = fingerprintOf(*grouped.map { it.fingerprint }.sorted().toTypedArray()),
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

    private data class SourceKey(
        val sourceType: String,
        val sourceRef: String,
        val interactionKind: String,
    )

    private data class SourceRecord(
        val key: SourceKey,
        val fingerprint: String,
        val applyTo: (PersonIndexBuild) -> Unit,
    ) {
        fun toState(userId: String): PersonIndexSourceStateEntity {
            val id = UUID.nameUUIDFromBytes(
                "person-index-source:$userId:${key.sourceType}:${key.sourceRef}:${key.interactionKind}"
                    .toByteArray(Charsets.UTF_8),
            ).toString()
            return PersonIndexSourceStateEntity(
                id = id,
                userId = userId,
                sourceType = key.sourceType,
                sourceRef = key.sourceRef,
                interactionKind = key.interactionKind,
                fingerprint = fingerprint,
                updatedAt = Clock.System.now(),
            )
        }
    }

    private fun PersonIndexSourceStateEntity.key(): SourceKey =
        SourceKey(
            sourceType = sourceType,
            sourceRef = sourceRef,
            interactionKind = interactionKind,
        )

    private fun suppressionFingerprint(
        blockedPersonRefs: Set<String>,
    ): String = fingerprintOf(
        *blockedPersonRefs.sorted().map { "b|$it" }.toTypedArray(),
    )

    private fun fingerprintOf(vararg parts: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val value = part.orEmpty().toByteArray(Charsets.UTF_8)
            digest.update(value.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(value)
            digest.update(0.toByte())
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private companion object {
        private const val TAG = "PersonIndexWorker"
    }
}
