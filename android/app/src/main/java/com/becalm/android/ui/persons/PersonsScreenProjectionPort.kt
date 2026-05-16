package com.becalm.android.ui.persons

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonIndexAggregateRow
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.person.PersonIdentityTypes
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.domain.person.PersonMatchingEventPolicy
import com.becalm.android.worker.ForegroundCatchUpScheduler
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
    private val personIndexDao: PersonIndexDao,
    private val selfIdentityAnchorDao: SelfIdentityAnchorDao,
    private val sourceStatusRepository: SourceStatusRepository,
    private val userPrefsStore: UserPrefsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PersonsScreenProjectionPort {

    override fun observePeople(userId: String): Flow<PersonsListPageProjection> =
        combine(
            personEnrichmentRepository.observeAll(),
            personIndexDao.observeAggregates(userId, PAGE_SIZE + 1),
            selfIdentityAnchorDao.observeActive(userId),
            userPrefsStore.observeBlockedPersonRefs(),
        ) { enrichmentRows, indexAggregateRows, selfAnchors, blockedPersonRefs ->
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
                        displayName = enrichment.displayName,
                        nickname = enrichment.nickname,
                        companyName = enrichment.company,
                        jobTitle = enrichment.title,
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
        }.let { matchingContextFlow ->
        combine(
            personIndexDao.observeUnmatchedInteractions(userId, limit),
            personIndexDao.observeSourceEventParticipantsForUser(userId),
            matchingContextFlow,
        ) { events, participants, matchingContext ->
            events
                .filterNot { event -> event.shouldHideFromManualMatching(matchingContext.blockedPersonRefs) }
                .map { event ->
                    val eventCandidates = candidateSourceRefs(event.sourceRef)
                        .flatMap { ref ->
                            participants.filter { participant ->
                                participant.sourceType == event.sourceType &&
                                    participant.resolutionStatus != "resolved" &&
                                    participant.relationToUser != "self" &&
                                    (
                                        participant.sourceRef == ref ||
                                            "raw:${participant.sourceEventId}" == ref ||
                                            participant.sourceEventId == ref.removePrefix("raw:")
                                )
                            }
                        }
                        .flatMap { participant ->
                            participant.toRecommendedCandidateSummaries(
                                event = event,
                                matchingContext = matchingContext,
                            ) + listOfNotNull(participant.toMatchCandidateSummary(matchingContext.blockedPersonRefs))
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
    ): PersonsListPageProjection {
        val enrichmentByRef = enrichmentRows.associateBy { it.personRef }
        val hasMore = aggregateRows.size > PAGE_SIZE
        val pageRows = aggregateRows
            .take(PAGE_SIZE)
            .map { aggregate ->
                val enrichment = aggregate.primaryIdentityKey
                    ?.removeIdentityPrefix()
                    ?.let(enrichmentByRef::get)
                PersonListProjection(
                    personId = aggregate.personId,
                    displayName = enrichment?.displayName ?: aggregate.displayNameHint,
                    nickname = enrichment?.nickname,
                    companyName = enrichment?.company,
                    jobTitle = enrichment?.title,
                    eventCount = aggregate.eventCount,
                    pendingCommitmentCount = aggregate.pendingCommitmentCount,
                    channelSources = aggregate.channelSources.toSourceSet(),
                    lastInteractionAt = aggregate.lastInteractionAt,
                    lastInteractionSnippet = aggregate.lastInteractionSnippet,
                )
            }

        return PersonsListPageProjection(
            rows = pageRows,
            hasMorePages = hasMore,
            nextCursor = pageRows.lastOrNull()?.let(::toCursor),
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

    private fun SourceEventParticipantEntity.toMatchCandidateSummary(
        blockedPersonRefs: Set<String>,
    ): PersonMatchCandidateSummary? {
        val anchor = emailRaw
            ?: phoneRaw
            ?: displayNameRaw
            ?: normalizedValue
            ?: organizationRaw
            ?: return null
        if (
            PersonIdentityResolver.isBlocked(anchor, blockedPersonRefs) ||
            PersonIdentityResolver.isLikelyAutomated(anchor)
        ) {
            return null
        }
        val displayName = displayNameRaw ?: emailRaw ?: phoneRaw ?: normalizedValue ?: organizationRaw ?: return null
        val detail = listOfNotNull(
            emailRaw?.takeUnless { it == displayName },
            phoneRaw?.takeUnless { it == displayName },
            organizationRaw,
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

    private fun String?.toSourceSet(): Set<String> =
        this
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toCollection(linkedSetOf())
            ?: emptySet()

    private fun UnmatchedPersonInteractionEntity.shouldHideFromManualMatching(
        blockedPersonRefs: Set<String>,
    ): Boolean {
        if (PersonMatchingEventPolicy.isLikelyServiceAccountNotification(title, snippet, suggestedLabel)) return true
        if (PersonIdentityResolver.isBlocked(suggestedLabel, blockedPersonRefs)) return true
        if (sourceType in AUDIO_MANUAL_MATCH_SOURCE_TYPES && suggestedLabel?.matches(SPEAKER_LABEL_REGEX) == true) {
            return false
        }
        return PersonIdentityResolver.isLikelyAutomated(suggestedLabel)
    }

    private fun PersonIndexAggregateRow.shouldHideFromPeopleList(): Boolean =
        PersonMatchingEventPolicy.isLikelyServiceAccountNotification(
            title = displayNameHint,
            snippet = lastInteractionSnippet,
            suggestedLabel = primaryIdentityKey,
        )

    private companion object {
        const val PAGE_SIZE: Int = 20
        const val MATCH_CANDIDATE_LIMIT: Int = 200
        val AUDIO_MANUAL_MATCH_SOURCE_TYPES: Set<String> = setOf(
            SourceType.MEETING,
            SourceType.VOICE,
            SourceType.CALL_RECORDING,
        )
        val SPEAKER_LABEL_REGEX: Regex = Regex("""(?i)^SPEAKER[_\s-]?\d+$""")
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
