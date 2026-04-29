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
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.PersonAliasRuleEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonIndexSourceStateEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.PersonManualMatchEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourcePersonCandidateEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.domain.person.PersonIdentityResolution
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

@HiltWorker
public class PersonInteractionIndexWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val databaseProvider: Provider<BeCalmDatabase>,
    private val rawDaoProvider: Provider<RawIngestionEventDao>,
    private val commitmentDaoProvider: Provider<CommitmentDao>,
    private val calendarDaoProvider: Provider<CalendarEventDao>,
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
        val calendarEvents = calendarDaoProvider.get().findAllForUser(userId)
        val existingCandidates = personIndexDaoProvider.get().findCandidatesForUser(userId)
        val manualMatches = personIndexDaoProvider.get().findManualMatchesForUser(userId)
        val aliasRules = personIndexDaoProvider.get().findEnabledAliasRulesForUser(userId)
        val existingStates = personIndexDaoProvider.get().findSourceStatesForUser(userId)

        val generatedAliasRules = buildGeneratedAliasRules(userId, existingCandidates)
        val resolverFingerprint = resolverFingerprint(
            manualMatches = manualMatches,
            aliasRules = aliasRules,
            generatedAliasRules = generatedAliasRules,
            candidates = existingCandidates,
        )
        val sourceRecords = buildSourceRecords(
            userId = userId,
            resolverFingerprint = resolverFingerprint,
            rawEvents = rawEvents,
            commitments = commitments,
            calendarEvents = calendarEvents,
            candidates = existingCandidates,
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
            return@withContext Result.success()
        }

        val builder = PersonIndexBuild(
            userId = userId,
            manualMatches = manualMatches,
            aliasRules = aliasRules + generatedAliasRules,
            sourceCandidates = existingCandidates,
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
            if (snapshot.candidates.isNotEmpty()) dao.upsertCandidates(snapshot.candidates)
            if (snapshot.unmatched.isNotEmpty()) dao.upsertUnmatchedInteractions(snapshot.unmatched)
            val sourceStates = changedRecords.map { it.toState(userId) }
            if (sourceStates.isNotEmpty()) dao.upsertSourceStates(sourceStates)
        }

        logger.d(
            TAG,
            "indexed changedSources=${changedRecords.size} obsoleteSources=${obsoleteStates.size} " +
                "identities=${snapshot.identities.size} interactions=${snapshot.interactions.size} " +
                "candidates=${snapshot.candidates.size} unmatched=${snapshot.unmatched.size}",
        )
        Result.success()
    }

    private fun buildSourceRecords(
        userId: String,
        resolverFingerprint: String,
        rawEvents: List<RawIngestionEventEntity>,
        commitments: List<CommitmentEntity>,
        calendarEvents: List<CalendarEventEntity>,
        candidates: List<SourcePersonCandidateEntity>,
    ): List<SourceRecord> {
        val records = mutableListOf<SourceRecord>()
        rawEvents.forEach { row ->
            val key = SourceKey(
                sourceType = row.sourceType,
                sourceRef = "raw:${row.id}",
                interactionKind = interactionKindFor(row.sourceType),
            )
            records += SourceRecord(
                key = key,
                fingerprint = fingerprintOf(
                    resolverFingerprint,
                    row.personRef,
                    row.eventTitle,
                    row.eventSnippet,
                    row.folder,
                    row.timestamp.toString(),
                ),
                applyTo = { it.addRawEvent(row) },
            )
        }
        commitments.forEach { row ->
            val key = SourceKey(
                sourceType = row.sourceType,
                sourceRef = "commitment:${row.id}",
                interactionKind = "commitment",
            )
            records += SourceRecord(
                key = key,
                fingerprint = fingerprintOf(
                    resolverFingerprint,
                    row.itemType,
                    row.direction,
                    row.scheduleStatus,
                    row.decisionStatus,
                    row.counterpartyRaw,
                    row.personRef,
                    row.title,
                    row.description,
                    row.quote,
                    row.sourceEventOccurredAt.toString(),
                    row.actionState,
                    row.confidence.toString(),
                    row.updatedAt.toString(),
                    row.deletedAt?.toString(),
                ),
                applyTo = { it.addCommitment(row) },
            )
        }
        calendarEvents.forEach { row ->
            attendeeRefs(row.attendeesRaw).forEach { attendee ->
                val provisional = PersonIdentityResolver.resolve(userId, attendee)
                val key = SourceKey(
                    sourceType = row.sourceType,
                    sourceRef = "calendar:${row.id}:${provisional?.identityKey ?: attendee.hashCode()}",
                    interactionKind = "calendar",
                )
                records += SourceRecord(
                    key = key,
                    fingerprint = fingerprintOf(
                        resolverFingerprint,
                        attendee,
                        row.title,
                        row.attendeesRaw,
                        row.startAt.toString(),
                        row.endAt.toString(),
                    ),
                    applyTo = { it.addCalendarAttendee(row, attendee, key.sourceRef) },
                )
            }
            if (!row.title.isNullOrBlank()) {
                val key = SourceKey(
                    sourceType = row.sourceType,
                    sourceRef = "calendar:${row.id}:title",
                    interactionKind = "calendar",
                )
                records += SourceRecord(
                    key = key,
                    fingerprint = fingerprintOf(
                        resolverFingerprint,
                        row.title,
                        row.attendeesRaw,
                        row.startAt.toString(),
                        row.endAt.toString(),
                    ),
                    applyTo = { it.addCalendarTitle(row, key.sourceRef) },
                )
            }
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
        manualMatches: List<PersonManualMatchEntity>,
        aliasRules: List<PersonAliasRuleEntity>,
        sourceCandidates: List<SourcePersonCandidateEntity>,
    ) {
        private val now = Clock.System.now()
        private val identities = linkedMapOf<String, PersonIdentityEntity>()
        private val interactions = linkedMapOf<String, PersonInteractionEntity>()
        private val candidates = linkedMapOf<String, SourcePersonCandidateEntity>()
        private val unmatched = linkedMapOf<String, UnmatchedPersonInteractionEntity>()
        private val manualMatchesBySource = manualMatches.associateBy {
            manualKey(it.sourceType, it.sourceRef, it.interactionKind)
        }
        private val aliasRules = aliasRules.filter { it.enabled && it.normalizedAlias.isNotBlank() }
        private val candidatesBySource = sourceCandidates
            .filter { it.confidence >= 0.8 }
            .groupBy { candidateKey(it.sourceType, it.sourceRef) }

        fun addRawEvent(row: RawIngestionEventEntity) {
            if (PersonIdentityResolver.isLikelyAutomated(row.personRef)) return
            val sourceRef = "raw:${row.id}"
            val candidateSourceRefs = candidateSourceRefs(sourceRef, row.sourceRef)
            val kind = interactionKindFor(row.sourceType)
            val resolved = resolveForSource(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = kind,
                explicitAnchor = row.personRef ?: candidateAnchorFor(row.sourceType, candidateSourceRefs),
                texts = listOf(row.personRef, row.eventTitle, row.eventSnippet),
                lastSeenAt = row.timestamp,
            ) ?: return upsertUnmatched(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = kind,
                title = row.eventTitle,
                snippet = row.eventSnippet,
                suggestedLabel = row.personRef,
                occurredAt = row.timestamp,
            )
            upsertCandidate(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                candidateRef = row.personRef,
                role = "counterparty",
                confidence = resolved.confidence,
            )
            upsertInteraction(
                personId = resolved.personId,
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = kind,
                role = "counterparty",
                direction = folderDirection(row.folder),
                status = null,
                occurredAt = row.timestamp,
                title = row.eventTitle,
                snippet = row.eventSnippet,
                confidence = resolved.confidence,
            )
        }

        fun addCommitment(row: CommitmentEntity) {
            val sourceRef = "commitment:${row.id}"
            val candidateSourceRefs = candidateSourceRefs(sourceRef, row.sourceRef)
            val candidateFallback = if (row.itemType == CommitmentItemType.DECISION) {
                null
            } else {
                candidateAnchorFor(row.sourceType, candidateSourceRefs)
            }
            val resolved = resolveForSource(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "commitment",
                explicitAnchor = row.personRef ?: row.counterpartyRaw ?: candidateFallback,
                texts = listOf(row.personRef, row.counterpartyRaw, row.title, row.description, row.quote),
                lastSeenAt = row.sourceEventOccurredAt,
            ) ?: return upsertUnmatched(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "commitment",
                title = row.title,
                snippet = row.quote,
                suggestedLabel = row.personRef ?: row.counterpartyRaw,
                occurredAt = row.sourceEventOccurredAt,
            )
            upsertCandidate(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                candidateRef = row.personRef ?: row.counterpartyRaw,
                role = "commitment_counterparty",
                confidence = resolved.confidence,
            )
            upsertInteraction(
                personId = resolved.personId,
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "commitment",
                role = row.itemType,
                direction = row.direction,
                status = when (row.itemType) {
                    CommitmentItemType.SCHEDULE -> row.scheduleStatus
                    CommitmentItemType.DECISION -> row.decisionStatus
                    else -> row.actionState
                },
                occurredAt = row.sourceEventOccurredAt,
                title = row.title,
                snippet = row.quote,
                confidence = row.confidence.coerceAtLeast(resolved.confidence),
            )
        }

        fun addCalendarAttendee(row: CalendarEventEntity, attendee: String, sourceRef: String) {
            if (PersonIdentityResolver.isLikelyAutomated(attendee)) return
            val resolved = resolveForSource(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "calendar",
                explicitAnchor = attendee,
                texts = listOf(attendee, row.title, row.attendeesRaw),
                lastSeenAt = row.startAt,
            ) ?: return upsertUnmatched(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "calendar",
                title = row.title,
                snippet = row.attendeesRaw,
                suggestedLabel = attendee,
                occurredAt = row.startAt,
            )
            upsertCandidate(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                candidateRef = attendee,
                role = "attendee",
                confidence = resolved.confidence,
            )
            upsertInteraction(
                personId = resolved.personId,
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "calendar",
                role = "attendee",
                direction = null,
                status = null,
                occurredAt = row.startAt,
                title = row.title,
                snippet = row.attendeesRaw,
                confidence = resolved.confidence,
            )
        }

        fun addCalendarTitle(row: CalendarEventEntity, sourceRef: String) {
            val resolved = resolveForSource(
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "calendar",
                explicitAnchor = null,
                texts = listOf(row.title, row.attendeesRaw),
                lastSeenAt = row.startAt,
            ) ?: return
            upsertInteraction(
                personId = resolved.personId,
                sourceType = row.sourceType,
                sourceRef = sourceRef,
                kind = "calendar",
                role = "title_mention",
                direction = null,
                status = null,
                occurredAt = row.startAt,
                title = row.title,
                snippet = row.attendeesRaw,
                confidence = resolved.confidence,
            )
        }

        fun snapshot(): Snapshot =
            Snapshot(
                identities = identities.values.toList(),
                interactions = interactions.values.toList(),
                candidates = candidates.values.toList(),
                unmatched = unmatched.values.toList(),
            )

        private fun resolveForSource(
            sourceType: String,
            sourceRef: String,
            kind: String,
            explicitAnchor: String?,
            texts: List<String?>,
            lastSeenAt: kotlinx.datetime.Instant,
        ): PersonIdentityResolution? {
            manualMatchesBySource[manualKey(sourceType, sourceRef, kind)]?.let { match ->
                return upsertIdentityResolution(
                    resolved = PersonIdentityResolver.resolveIdentityKey(
                        userId = userId,
                        identityKey = match.matchedIdentityKey,
                        rawValue = match.nickname,
                        displayNameHint = match.nickname,
                        confidence = 1.0,
                        verified = true,
                    ) ?: return null,
                    sourceType = sourceType,
                    lastSeenAt = lastSeenAt,
                )
            }

            upsertIdentity(explicitAnchor, sourceType, lastSeenAt)?.let { return it }

            val aliasResolved = resolveAlias(sourceType, texts)
            return if (aliasResolved != null) {
                upsertIdentityResolution(aliasResolved, sourceType, lastSeenAt)
            } else {
                null
            }
        }

        private fun upsertIdentity(
            raw: String?,
            sourceType: String,
            lastSeenAt: kotlinx.datetime.Instant,
        ): PersonIdentityResolution? {
            val resolved = PersonIdentityResolver.resolve(userId, raw) ?: return null
            return upsertIdentityResolution(resolved, sourceType, lastSeenAt)
        }

        private fun upsertIdentityResolution(
            resolved: PersonIdentityResolution,
            sourceType: String,
            lastSeenAt: kotlinx.datetime.Instant,
        ): PersonIdentityResolution {
            val id = UUID.nameUUIDFromBytes("identity:$userId:${resolved.identityKey}".toByteArray(Charsets.UTF_8)).toString()
            val previous = identities[resolved.identityKey]
            identities[resolved.identityKey] = PersonIdentityEntity(
                id = id,
                userId = userId,
                personId = resolved.personId,
                identityKey = resolved.identityKey,
                identityType = resolved.identityType,
                rawValue = resolved.rawValue,
                displayNameHint = resolved.displayNameHint,
                sourceType = sourceType,
                confidence = maxOf(previous?.confidence ?: 0.0, resolved.confidence),
                verified = (previous?.verified == true) || resolved.verified,
                lastSeenAt = maxOf(previous?.lastSeenAt ?: lastSeenAt, lastSeenAt),
            )
            return resolved
        }

        private fun resolveAlias(sourceType: String, texts: List<String?>): PersonIdentityResolution? {
            val haystack = PersonIdentityResolver.normalizeAlias(
                texts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" "),
            ) ?: return null
            val rule = aliasRules.firstOrNull { rule ->
                (rule.sourceScope == null || rule.sourceScope == sourceType) &&
                    haystack.contains(rule.normalizedAlias)
            } ?: return null
            return PersonIdentityResolver.resolveIdentityKey(
                userId = userId,
                identityKey = rule.identityKey,
                rawValue = rule.alias,
                displayNameHint = rule.alias,
                confidence = 0.99,
                verified = true,
            )
        }

        private fun candidateAnchorFor(sourceType: String, vararg sourceRefs: String?): String? =
            candidatesFor(sourceType, *sourceRefs)
                .mapNotNull { it.email ?: it.phone ?: it.name }
                .filterNot(PersonIdentityResolver::isLikelyAutomated)
                .distinct()
                .singleOrNull()

        private fun candidateAnchorFor(sourceType: String, sourceRefs: List<String?>): String? =
            candidateAnchorFor(sourceType, *sourceRefs.toTypedArray())

        private fun candidateSourceRefs(recordSourceRef: String, providerSourceRef: String?): List<String?> =
            listOf(
                recordSourceRef,
                providerSourceRef,
                providerSourceRef?.takeIf { !it.startsWith("raw:") }?.let { "raw:$it" },
            )

        private fun candidatesFor(sourceType: String, vararg sourceRefs: String?): List<SourcePersonCandidateEntity> =
            sourceRefs
                .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .flatMap { ref -> candidatesBySource[candidateKey(sourceType, ref)].orEmpty() }
                .distinctBy { it.id }

        private fun upsertCandidate(
            sourceType: String,
            sourceRef: String,
            candidateRef: String?,
            role: String,
            confidence: Double,
        ) {
            val ref = candidateRef?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val key = "$sourceType|$sourceRef|$role|$ref"
            val id = UUID.nameUUIDFromBytes("candidate:$userId:$key".toByteArray(Charsets.UTF_8)).toString()
            candidates[id] = SourcePersonCandidateEntity(
                id = id,
                userId = userId,
                sourceType = sourceType,
                sourceRef = sourceRef,
                candidateRef = "$role:$ref",
                role = role,
                name = ref.takeUnless { it.contains('@') || it.any(Char::isDigit) },
                email = ref.takeIf { it.contains('@') },
                phone = ref.takeIf { it.any(Char::isDigit) && !it.contains('@') },
                organization = null,
                evidence = null,
                confidence = confidence,
                createdAt = now,
            )
        }

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

        private fun interactionKindFor(sourceType: String): String = when {
            sourceType.contains("calendar") -> "calendar"
            sourceType.contains("mail") || sourceType.contains("imap") || sourceType == "gmail" -> "email"
            sourceType == "voice" || sourceType == "call_recording" -> "call"
            else -> sourceType
        }

        private fun folderDirection(folder: String?): String? = when (folder?.uppercase()) {
            "INBOX" -> "received"
            "SENT" -> "sent"
            else -> null
        }

        private fun attendeeRefs(raw: String?): List<String> =
            raw
                ?.split(',', ';', '\n')
                ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                ?.distinct()
                ?: emptyList()

        private fun manualKey(sourceType: String, sourceRef: String, kind: String): String =
            "$sourceType|$sourceRef|$kind"

        private fun candidateKey(sourceType: String, sourceRef: String): String =
            "$sourceType|$sourceRef"
    }

    private data class Snapshot(
        val identities: List<PersonIdentityEntity>,
        val interactions: List<PersonInteractionEntity>,
        val candidates: List<SourcePersonCandidateEntity>,
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

    private fun resolverFingerprint(
        manualMatches: List<PersonManualMatchEntity>,
        aliasRules: List<PersonAliasRuleEntity>,
        generatedAliasRules: List<PersonAliasRuleEntity>,
        candidates: List<SourcePersonCandidateEntity>,
    ): String = fingerprintOf(
        *(
            manualMatches.map {
                "m|${it.sourceType}|${it.sourceRef}|${it.interactionKind}|${it.matchedIdentityKey}|${it.nickname}|${it.updatedAt}"
            } +
                aliasRules.map {
                    "a|${it.normalizedAlias}|${it.identityKey}|${it.sourceScope}|${it.enabled}|${it.updatedAt}"
                } +
                generatedAliasRules.map {
                    "g|${it.normalizedAlias}|${it.identityKey}|${it.sourceScope}|${it.enabled}|${it.updatedAt}"
                } +
                candidates.map {
                    "c|${it.sourceType}|${it.sourceRef}|${it.candidateRef}|${it.role}|${it.name}|${it.email}|${it.phone}|${it.evidence}|${it.confidence}|${it.createdAt}"
                }
            ).sorted().toTypedArray(),
    )

    private fun buildGeneratedAliasRules(
        userId: String,
        candidates: List<SourcePersonCandidateEntity>,
    ): List<PersonAliasRuleEntity> =
        candidates.mapNotNull { candidate ->
            if (candidate.confidence < 0.8) return@mapNotNull null
            val name = candidate.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val anchor = candidate.email ?: candidate.phone ?: return@mapNotNull null
            if (PersonIdentityResolver.isLikelyAutomated(anchor)) return@mapNotNull null
            val resolved = PersonIdentityResolver.resolve(userId, anchor) ?: return@mapNotNull null
            val normalizedAlias = PersonIdentityResolver.normalizeAlias(name) ?: return@mapNotNull null
            val id = UUID.nameUUIDFromBytes(
                "generated-alias:$userId:$normalizedAlias:${resolved.identityKey}".toByteArray(Charsets.UTF_8),
            ).toString()
            PersonAliasRuleEntity(
                id = id,
                userId = userId,
                alias = name,
                normalizedAlias = normalizedAlias,
                personId = resolved.personId,
                identityKey = resolved.identityKey,
                sourceScope = null,
                enabled = true,
                createdAt = candidate.createdAt,
                updatedAt = candidate.createdAt,
            )
        }

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

    private fun interactionKindFor(sourceType: String): String = when {
        sourceType.contains("calendar") -> "calendar"
        sourceType.contains("mail") || sourceType.contains("imap") || sourceType == "gmail" -> "email"
        sourceType == "voice" || sourceType == "call_recording" -> "call"
        else -> sourceType
    }

    private fun attendeeRefs(raw: String?): List<String> =
        raw
            ?.split(',', ';', '\n')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.distinct()
            ?: emptyList()

    private companion object {
        private const val TAG = "PersonIndexWorker"
    }
}
