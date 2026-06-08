package com.becalm.android.ui.persons

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonActionDao
import com.becalm.android.data.local.db.dao.PersonIndexAggregateRow
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.email.OutgoingEmailSalutationExtractor
import com.becalm.android.domain.person.PersonIdentityTypes
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonMatchingEventPolicy
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.SourceParticipantReviewPolicy
import com.becalm.android.worker.WorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.Instant

/** Projection row used by [PersonsViewModel] to expose SRC-001/002 contracts. */
public data class PersonListProjection(
    val personId: String,
    val displayName: String?,
    val nickname: String?,
    val companyName: String?,
    val jobTitle: String?,
    val eventCount: Int,
    val pendingCommitmentCount: Int,
    val channelSources: Set<String>,
    val lastInteractionAt: Instant?,
    val lastInteractionSnippet: String?,
    val topAction: PersonActionSummary? = null,
)

/** Candidate surfaced for an unresolved person interaction. */
public data class PersonMatchCandidateSummary(
    val anchor: String,
    val displayName: String,
    val detail: String?,
    val role: String,
    val evidence: String?,
    val confidence: Double,
    val recommended: Boolean = false,
    val reasons: List<String> = emptyList(),
    val isSelfSuggestion: Boolean = false,
)

/** Unassigned bucket item surfaced on the persons screen. */
public data class UnassignedEventSummary(
    val id: String,
    val sourceType: String,
    val title: String?,
    val timestamp: Instant,
    val sourceRef: String = id,
    val interactionKind: String = sourceType,
    val snippet: String? = null,
    val suggestedLabel: String? = null,
    val candidates: List<PersonMatchCandidateSummary> = emptyList(),
    val isSpeakerReviewCandidate: Boolean = false,
)

/** Offline badge contract for the persons screen. */
public data class PersonsOfflineStatus(
    val isOffline: Boolean,
    val lastSyncAt: Instant?,
)

/** Stable sort semantics owner for SRC-001. */
public enum class PersonsSortOrder {
    MOST_RECENT_EVENT_DESC,
}

/** Cursor/page metadata for the persons list. */
public data class PersonsListPageProjection(
    val rows: List<PersonListProjection>,
    val hasMorePages: Boolean,
    val nextCursor: String?,
    val sortOrder: PersonsSortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
)

/** Observable refresh fan-out contract for SRC-006. */
public data class PersonsRefreshSnapshot(
    val roomRequeryTriggered: Boolean,
    val catchUpTriggered: Boolean,
    val enrichmentTriggered: Boolean,
    val personIndexTriggered: Boolean = false,
)

/**
 * Read/write seam that supplies the persons-screen projection contract.
 *
 * The production implementation owns the Room-backed aggregate used by PersonsScreen.
 * Tests can replace the port with a fake when they only need contract-level assertions.
 */
public interface PersonsScreenProjectionPort {
    public fun observePeople(userId: String): Flow<PersonsListPageProjection>
    public fun observeSearchableContacts(userId: String): Flow<List<PersonListProjection>> = flowOf(emptyList())
    public fun observeUnassigned(userId: String, limit: Int = 20): Flow<List<UnassignedEventSummary>>
    public fun observeOfflineStatus(): Flow<PersonsOfflineStatus>
}

/** Owner seam for the SRC-006 refresh fan-out contract. */
public interface PersonsRefreshCoordinator {
    public fun refresh(): PersonsRefreshSnapshot
}

@Singleton
public class EnrichmentBackedPersonsScreenProjectionPort @Inject constructor(
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val personActionDao: PersonActionDao,
    private val personIndexDao: PersonIndexDao,
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao,
    private val sourceStatusRepository: SourceStatusRepository,
    private val userPrefsStore: UserPrefsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PersonsScreenProjectionPort {

    override fun observePeople(userId: String): Flow<PersonsListPageProjection> =
        combine(
            personEnrichmentRepository.observeAll(),
            personIndexDao.observeAggregates(userId, PEOPLE_LIST_LIMIT + 1),
            personActionDao.observeActiveForSurface(userId, surface = "person", limit = PEOPLE_LIST_LIMIT + 1),
            selfIdentityAnchorDao.observeActive(userId),
            userPrefsStore.observeBlockedPersonRefs(),
        ) { enrichmentRows, indexAggregateRows, actionRows, selfAnchors, blockedPersonRefs ->
            val selfMatcher = SelfIdentityAnchorMatcher(selfAnchors)
            val filteredIndexRows = indexAggregateRows.filterNot { row ->
                PersonIdentityResolver.isBlocked(row.primaryIdentityKey, blockedPersonRefs) ||
                    PersonIdentityResolver.isLikelyAutomated(row.primaryIdentityKey) ||
                    PersonIdentityResolver.isLikelyAutomated(row.displayNameHint) ||
                    row.shouldHideFromPeopleList() ||
                    selfMatcher.matches(row.primaryIdentityKey, row.displayNameHint)
            }
            buildProjectionPage(
                enrichmentRows = enrichmentRows.filterNot { row ->
                    selfMatcher.matches(row.personRef, row.displayName, row.nickname)
                },
                aggregateRows = filteredIndexRows,
                actionRows = actionRows,
            )
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeSearchableContacts(userId: String): Flow<List<PersonListProjection>> =
        combine(
            personEnrichmentRepository.observeAll(),
            selfIdentityAnchorDao.observeActive(userId),
            userPrefsStore.observeBlockedPersonRefs(),
        ) { enrichmentRows, selfAnchors, blockedPersonRefs ->
            val selfMatcher = SelfIdentityAnchorMatcher(selfAnchors)
            enrichmentRows
                .filterNot { row ->
                    PersonIdentityResolver.isBlocked(row.personRef, blockedPersonRefs) ||
                        PersonIdentityResolver.isLikelyAutomated(row.personRef) ||
                        PersonIdentityResolver.isLikelyAutomated(row.displayName) ||
                        selfMatcher.matches(row.personRef, row.displayName, row.nickname)
                }
                .map { enrichment ->
                    PersonListProjection(
                        personId = enrichment.personRef,
                        displayName = sanitizeDisplayName(enrichment.displayName),
                        nickname = sanitizeDisplayName(enrichment.nickname),
                        companyName = sanitizeDisplayName(enrichment.company),
                        jobTitle = sanitizeDisplayName(enrichment.title),
                        eventCount = 0,
                        pendingCommitmentCount = 0,
                        channelSources = emptySet(),
                        lastInteractionAt = null,
                        lastInteractionSnippet = null,
                    )
                }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeUnassigned(userId: String, limit: Int): Flow<List<UnassignedEventSummary>> =
        combine(
            personEnrichmentRepository.observeAll(),
            personIndexDao.observeAggregates(userId, MATCH_CANDIDATE_LIMIT),
            personIndexDao.observeIdentitiesForUser(userId),
            personIndexDao.observeSemanticIndexesForUser(userId),
            combine(
                selfIdentityAnchorDao.observeActive(userId),
                userPrefsStore.observeBlockedPersonRefs(),
            ) { selfAnchors, blockedPersonRefs -> selfAnchors to blockedPersonRefs },
        ) { enrichmentRows, aggregateRows, identityRows, semanticIndexRows, selfPolicy ->
            val (selfAnchors, blockedPersonRefs) = selfPolicy
            val selfMatcher = SelfIdentityAnchorMatcher(selfAnchors)
            buildMatchingContext(
                userId = userId,
                enrichmentRows = enrichmentRows.filterNot { row ->
                    selfMatcher.matches(row.personRef, row.displayName, row.nickname)
                },
                aggregateRows = aggregateRows.filterNot { row ->
                    selfMatcher.matches(row.primaryIdentityKey, row.displayNameHint)
                },
                identityRows = identityRows.filterNot { row ->
                    selfMatcher.matches(row.identityKey, row.rawValue, row.displayName, row.displayNameHint)
                },
                semanticIndexRows = semanticIndexRows,
                blockedPersonRefs = blockedPersonRefs,
            )
        }
            .onStart { emit(MatchingProjectionContext(candidates = emptyList(), blockedPersonRefs = emptySet())) }
            .let { matchingContextFlow ->
        combine(
            personIndexDao.observeUnmatchedInteractionsWithEmailBodies(userId, limit),
            personIndexDao.observeSourceEventParticipantsForUser(userId),
            matchingContextFlow,
        ) { events, participants, matchingContext ->
            events
                .mapNotNull { eventRow ->
                    val event = eventRow.toEntity()
                    val matchedParticipants = candidateSourceRefs(event.sourceRef)
                        .flatMap { ref ->
                            participants.filter { participant ->
                                participant.sourceType == event.sourceType &&
                                    (
                                        participant.sourceRef == ref ||
                                            "raw:${participant.sourceEventId}" == ref ||
                                            participant.sourceEventId == ref.removePrefix("raw:")
                                )
                            }
                        }
                        .distinctBy { it.id }
                    val reviewableParticipants = matchedParticipants.filter { participant ->
                        participant.resolutionStatus.isReviewableForManualMatch() &&
                            participant.relationToUser != "self"
                    }
                    val hasSpeakerReviewCandidate =
                        reviewableParticipants.any(SourceParticipantReviewPolicy::isSourceLocalSpeakerReviewCandidate)
                    if (
                        event.shouldHideFromManualMatching(
                            blockedPersonRefs = matchingContext.blockedPersonRefs,
                            isSpeakerReviewCandidate = hasSpeakerReviewCandidate,
                            hasReviewableParticipant = reviewableParticipants.isNotEmpty(),
                        )
                    ) {
                        return@mapNotNull null
                    }
                    if (matchedParticipants.isNotEmpty() && reviewableParticipants.isEmpty()) {
                        return@mapNotNull null
                    }
                    val eventCandidates = reviewableParticipants
                        .flatMap { participant ->
                            participant.toRecommendedCandidateSummaries(
                                event = event,
                                matchingContext = matchingContext,
                                emailFolder = eventRow.emailFolder,
                                emailBodyPlain = eventRow.emailBodyPlain,
                            ) + listOfNotNull(
                                participant.toMatchCandidateSummary(
                                    blockedPersonRefs = matchingContext.blockedPersonRefs,
                                    emailFolder = eventRow.emailFolder,
                                    emailBodyPlain = eventRow.emailBodyPlain,
                                    eventSnippet = event.snippet,
                                ),
                            )
                        }
                        .distinctBy { it.anchor }
                        .sortedWith(compareByDescending<PersonMatchCandidateSummary> { it.recommended }.thenByDescending { it.confidence })
                    UnassignedEventSummary(
                        id = event.id,
                        sourceType = event.sourceType,
                        sourceRef = event.sourceRef,
                        interactionKind = event.interactionKind,
                        title = event.title,
                        snippet = event.snippet,
                        suggestedLabel = event.suggestedLabel,
                        candidates = eventCandidates,
                        isSpeakerReviewCandidate = hasSpeakerReviewCandidate,
                        timestamp = event.occurredAt,
                    )
                }
        }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeOfflineStatus(): Flow<PersonsOfflineStatus> =
        sourceStatusRepository.observeAll().map(::buildOfflineStatus)
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    private fun buildProjectionPage(
        enrichmentRows: List<PersonEnrichmentEntity>,
        aggregateRows: List<PersonIndexAggregateRow>,
        actionRows: List<PersonActionItemCacheEntity>,
    ): PersonsListPageProjection {
        val enrichmentByRef = enrichmentRows.associateBy { it.personRef }
        val topActionByPersonId = actionRows
            .filter { !it.personId.isNullOrBlank() }
            .groupBy { requireNotNull(it.personId) }
            .mapValues { (_, rows) -> rows.maxWith(compareBy<PersonActionItemCacheEntity> { it.urgencyScore }.thenBy { it.updatedAt }) }
        val indexedPersonIds = aggregateRows.map { it.personId }.toSet()
        val indexedRows = aggregateRows
            .map { aggregate ->
                val enrichment = aggregate.primaryIdentityKey
                    ?.removeIdentityPrefix()
                    ?.let(enrichmentByRef::get)
                val topAction = topActionByPersonId[aggregate.personId]?.toActionSummary()
                val safeDisplayName = listOfNotNull(
                    sanitizeDisplayName(topActionByPersonId[aggregate.personId]?.personDisplayName),
                    sanitizeDisplayName(enrichment?.displayName),
                    sanitizeDisplayName(enrichment?.nickname),
                    sanitizeDisplayName(aggregate.displayNameHint),
                    sanitizeDisplayName(aggregate.primaryIdentityKey),
                ).firstOrNull()
                    ?: UNKNOWN_PERSON_DISPLAY_NAME
                PersonListProjection(
                    personId = aggregate.personId,
                    displayName = safeDisplayName,
                    nickname = sanitizeDisplayName(enrichment?.nickname),
                    companyName = sanitizeDisplayName(enrichment?.company),
                    jobTitle = sanitizeDisplayName(enrichment?.title),
                    eventCount = aggregate.eventCount,
                    pendingCommitmentCount = aggregate.pendingCommitmentCount,
                    channelSources = aggregate.channelSources.toSourceSet(),
                    lastInteractionAt = aggregate.lastInteractionAt,
                    lastInteractionSnippet = aggregate.lastInteractionSnippet,
                    topAction = topAction,
                )
            }
        val actionOnlyRows = actionRows
            .filter { !it.personId.isNullOrBlank() && it.personId !in indexedPersonIds }
            .groupBy { requireNotNull(it.personId) }
            .map { (_, rows) -> rows.maxWith(compareBy<PersonActionItemCacheEntity> { it.urgencyScore }.thenBy { it.updatedAt }) }
            .map { action ->
                PersonListProjection(
                    personId = requireNotNull(action.personId),
                    displayName = sanitizeDisplayName(action.personDisplayName) ?: UNKNOWN_PERSON_DISPLAY_NAME,
                    nickname = null,
                    companyName = null,
                    jobTitle = null,
                    eventCount = 0,
                    pendingCommitmentCount = 1,
                    channelSources = listOfNotNull(action.sourceType).toSet(),
                    lastInteractionAt = action.updatedAt,
                    lastInteractionSnippet = action.shortReason,
                    topAction = action.toActionSummary(),
                )
            }
        val mergedRows = (indexedRows + actionOnlyRows)
            .sortedWith(
                compareByDescending<PersonListProjection> { it.topAction?.urgencyScore ?: -1.0 }
                    .thenByDescending { it.lastInteractionAt },
            )
        val hasMore = mergedRows.size > PEOPLE_LIST_LIMIT
        val pageRows = mergedRows.take(PEOPLE_LIST_LIMIT)

        return PersonsListPageProjection(
            rows = pageRows,
            hasMorePages = hasMore,
            nextCursor = pageRows.lastOrNull()?.takeIf { hasMore }?.let(::toCursor),
            sortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
        )
    }

    private fun buildOfflineStatus(
        statuses: List<com.becalm.android.data.repository.SourceStatus>,
    ): PersonsOfflineStatus =
        PersonsOfflineStatus(
            isOffline = false,
            lastSyncAt = statuses.mapNotNull { it.lastSyncedAt }.maxOrNull(),
        )

    private fun toCursor(row: PersonListProjection): String =
        "${row.lastInteractionAt?.toEpochMilliseconds() ?: 0}|${row.personId}"

    private fun String.removeIdentityPrefix(): String =
        substringAfter(':', this)

    private fun candidateSourceRefs(sourceRef: String): List<String> =
        listOf(
            sourceRef,
            sourceRef.removePrefix("raw:"),
            sourceRef.takeIf { !it.startsWith("raw:") }?.let { "raw:$it" }.orEmpty(),
        ).filter { it.isNotBlank() }.distinct()

    private fun String.isReviewableForManualMatch(): Boolean =
        this == "unresolved" || this == "suggested_self"

    private fun SourceEventParticipantEntity.toMatchCandidateSummary(
        blockedPersonRefs: Set<String>,
        emailFolder: String? = null,
        emailBodyPlain: String? = null,
        eventSnippet: String? = null,
    ): PersonMatchCandidateSummary? {
        val anchor = listOf(
            emailRaw,
            phoneRaw,
            normalizedValue,
            organizationRaw,
            displayNameRaw,
        ).firstOrNull { it != null && it.isNotBlank() }
            ?: return null
        if (isSourceLocalSpeakerLabelOnly() || anchor.isSpeakerLabelProjectionValue()) return null
        if (
            PersonIdentityResolver.isBlocked(anchor, blockedPersonRefs) ||
            PersonIdentityResolver.isLikelyAutomated(anchor)
        ) {
                return null
        }
        val salutationDisplayName = OutgoingEmailSalutationExtractor.extractNames(
            folder = emailFolder,
            bodyText = emailBodyPlain ?: eventSnippet,
        ).firstOrNull()
        val displayName = listOfNotNull(
            sanitizeDisplayName(displayNameRaw),
            sanitizeDisplayName(salutationDisplayName),
            sanitizeDisplayName(organizationRaw),
        ).firstOrNull()
            ?: UNKNOWN_PERSON_DISPLAY_NAME
        val detail = listOfNotNull(
            organizationRaw?.trim()
                ?.takeIf { it.isNotBlank() && isDisplaySafeMetadata(it) },
        ).firstOrNull()
        return PersonMatchCandidateSummary(
            anchor = anchor,
            displayName = displayName,
            detail = detail,
            role = role,
            evidence = evidence,
            confidence = confidence,
            isSelfSuggestion = resolutionStatus == "suggested_self",
        )
    }

    private fun sanitizeDisplayName(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value in GENERIC_DISPLAY_NAMES) return null
        if (isTechnicalPersonValue(value)) return null
        return value
    }

    private fun isTechnicalPersonValue(value: String): Boolean {
        if (PersonIdentityResolver.isSpeakerLabelValue(value)) return true
        if (PersonIdentityResolver.normalizeEmailAnchor(value) != null) return true
        if (PersonIdentityResolver.normalizePhoneAnchor(value) != null) return true
        return value.all { it.isDigit() || it == '-' || it == ' ' }
    }

    private fun isDisplaySafeMetadata(raw: String?): Boolean {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return false
        return !isTechnicalPersonValue(value)
    }

    private fun String?.toSourceSet(): Set<String> =
        this
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toCollection(linkedSetOf())
            ?: emptySet()

    private fun PersonActionItemCacheEntity.toActionSummary(): PersonActionSummary =
        PersonActionSummary(
            id = id,
            title = title,
            primaryVerb = primaryVerb,
            shortReason = shortReason,
            actionKind = actionKind,
            dueAt = dueAt,
            urgencyScore = urgencyScore,
            dueHint = dueHint,
        )

    private fun UnmatchedPersonInteractionEntity.shouldHideFromManualMatching(
        blockedPersonRefs: Set<String>,
        isSpeakerReviewCandidate: Boolean = false,
        hasReviewableParticipant: Boolean = false,
    ): Boolean {
        if (!hasReviewableParticipant && PersonMatchingEventPolicy.isLikelyServiceAccountNotification(title, snippet, suggestedLabel)) return true
        if (PersonIdentityResolver.isBlocked(suggestedLabel, blockedPersonRefs)) return true
        if (suggestedLabel.isSpeakerLabelProjectionValue()) return !isSpeakerReviewCandidate
        return PersonIdentityResolver.isLikelyAutomated(suggestedLabel)
    }

    private fun PersonIndexAggregateRow.shouldHideFromPeopleList(): Boolean {
        if (displayNameHint.isSpeakerLabelProjectionValue() || primaryIdentityKey.isSpeakerLabelProjectionValue()) return true
        if (pendingCommitmentCount > 0) return false
        return PersonMatchingEventPolicy.isLikelyServiceAccountNotification(
            title = displayNameHint,
            snippet = listOfNotNull(lastInteractionSnippet, interactionText).joinToString(" ").ifBlank { null },
            suggestedLabel = primaryIdentityKey,
        )
    }

    private fun SourceEventParticipantEntity.isSourceLocalSpeakerLabelOnly(): Boolean {
        val sourceLocalIdentity = identityType?.let(PersonIdentityTypes::isSourceLocal) == true
        if (!sourceLocalIdentity) return false
        val hasRealContact = !emailRaw.isNullOrBlank() || !phoneRaw.isNullOrBlank() || !organizationRaw.isNullOrBlank()
        val hasRealDisplayName = !displayNameRaw.isNullOrBlank() &&
            !PersonIdentityResolver.isSpeakerLabelValue(displayNameRaw)
        return !hasRealContact && !hasRealDisplayName
    }

    private fun String?.isSpeakerLabelProjectionValue(): Boolean {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return PersonIdentityResolver.isSpeakerLabelValue(value) ||
            PersonIdentityResolver.isSpeakerLabelValue(value.substringAfter(':', value))
    }

    private companion object {
        const val PEOPLE_LIST_LIMIT: Int = 200
        const val MATCH_CANDIDATE_LIMIT: Int = 200
        const val UNKNOWN_PERSON_DISPLAY_NAME = "아직 이름을 모르는 연락처"
        val GENERIC_DISPLAY_NAMES: Set<String> = setOf(
            "고객",
            "고객님",
            "담당자",
            "담당자님",
            "대표",
            "대표님",
            "멘토",
            "멘토님",
            "교수",
            "교수님",
            "선생",
            "선생님",
            "팀장",
            "팀장님",
            "박사",
            "박사님",
            "변호사",
            "변호사님",
            "원장",
            "원장님",
            "이사",
            "이사님",
        )
    }
}

private class SelfIdentityAnchorMatcher(
    anchors: List<SelfIdentityAnchorEntity>,
) {
    private val normalizedValues: Set<String> = anchors
        .filter { it.status == "active" }
        .flatMap { anchor ->
            when (anchor.anchorType) {
                "alias",
                "name",
                PersonIdentityTypes.SPEAKER_LABEL,
                -> listOf(anchor.normalizedValue, anchor.displayValue)

                else -> listOf(anchor.normalizedValue)
            }
        }
        .mapNotNull(PersonIdentityResolver::normalizeBlockKey)
        .toSet()

    fun matches(vararg values: String?): Boolean {
        if (normalizedValues.isEmpty()) return false
        return values.any { value ->
            PersonIdentityResolver.normalizeBlockKey(value) in normalizedValues
        }
    }
}

@Singleton
public class WorkManagerPersonsRefreshCoordinator @Inject constructor(
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler,
    private val workScheduler: WorkScheduler,
) : PersonsRefreshCoordinator {

    override fun refresh(): PersonsRefreshSnapshot {
        foregroundCatchUpScheduler.triggerCatchUp()
        workScheduler.enqueueEnrichment()
        workScheduler.enqueuePersonInteractionIndex(initialDelaySeconds = 0L)
        return PersonsRefreshSnapshot(
            roomRequeryTriggered = true,
            catchUpTriggered = true,
            enrichmentTriggered = true,
            personIndexTriggered = true,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
public abstract class PersonsScreenProjectionModule {

    @Binds
    @Singleton
    public abstract fun bindPersonsScreenProjectionPort(
        impl: EnrichmentBackedPersonsScreenProjectionPort,
    ): PersonsScreenProjectionPort

    @Binds
    @Singleton
    public abstract fun bindPersonsRefreshCoordinator(
        impl: WorkManagerPersonsRefreshCoordinator,
    ): PersonsRefreshCoordinator
}
