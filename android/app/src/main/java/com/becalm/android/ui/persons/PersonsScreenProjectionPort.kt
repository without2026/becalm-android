package com.becalm.android.ui.persons

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.PersonInteractionAggregateRow
import com.becalm.android.data.local.db.dao.PersonIndexAggregateRow
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.SourcePersonCandidateEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.person.PersonIdentityResolver
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/** Projection row used by [PersonsViewModel] to expose SRC-001/002 contracts. */
public data class PersonListProjection(
    val personRef: String,
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
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val userPrefsStore: UserPrefsStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PersonsScreenProjectionPort {

    override fun observePeople(userId: String): Flow<PersonsListPageProjection> =
        combine(
            personEnrichmentRepository.observeAll(),
            personIndexDao.observeAggregates(userId, PAGE_SIZE + 1),
            rawIngestionRepository.observePersonInteractionAggregates(userId, PAGE_SIZE + 1),
            userPrefsStore.observeBlockedPersonRefs(),
        ) { enrichmentRows, indexAggregateRows, legacyAggregateRows, blockedPersonRefs ->
            val filteredIndexRows = indexAggregateRows.filterNot { row ->
                PersonIdentityResolver.isBlocked(row.primaryIdentityKey, blockedPersonRefs) ||
                    PersonIdentityResolver.isLikelyAutomated(row.primaryIdentityKey) ||
                    PersonIdentityResolver.isLikelyAutomated(row.displayNameHint)
            }
            if (filteredIndexRows.isNotEmpty()) {
                buildProjectionPage(
                    enrichmentRows = enrichmentRows,
                    aggregateRows = filteredIndexRows,
                )
            } else {
                buildLegacyProjectionPage(
                    enrichmentRows = enrichmentRows,
                    aggregateRows = legacyAggregateRows.filterNot { row ->
                        PersonIdentityResolver.isBlocked(row.personRef, blockedPersonRefs) ||
                            PersonIdentityResolver.isLikelyAutomated(row.personRef)
                    },
                )
            }
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeUnassigned(userId: String, limit: Int): Flow<List<UnassignedEventSummary>> =
        combine(
            personIndexDao.observeUnmatchedInteractions(userId, limit),
            personIndexDao.observeCandidatesForUser(userId),
            userPrefsStore.observeBlockedPersonRefs(),
        ) { events, candidates, blockedPersonRefs ->
            events
                .filterNot { event ->
                    PersonIdentityResolver.isBlocked(event.suggestedLabel, blockedPersonRefs) ||
                        PersonIdentityResolver.isLikelyAutomated(event.suggestedLabel)
                }
                .map { event ->
                    val eventCandidates = candidateSourceRefs(event.sourceRef)
                        .flatMap { ref ->
                            candidates.filter { candidate ->
                                candidate.sourceType == event.sourceType &&
                                    candidate.sourceRef == ref
                            }
                        }
                        .mapNotNull { it.toMatchCandidateSummary(blockedPersonRefs) }
                        .distinctBy { it.anchor }
                        .sortedByDescending { it.confidence }
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
                    personRef = aggregate.personId,
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

    private fun buildLegacyProjectionPage(
        enrichmentRows: List<PersonEnrichmentEntity>,
        aggregateRows: List<PersonInteractionAggregateRow>,
    ): PersonsListPageProjection {
        val enrichmentByRef = enrichmentRows.associateBy { it.personRef }
        val hasMore = aggregateRows.size > PAGE_SIZE
        val pageRows = aggregateRows
            .take(PAGE_SIZE)
            .map { aggregate ->
                val enrichment = enrichmentByRef[aggregate.personRef]
                PersonListProjection(
                    personRef = aggregate.personRef,
                    displayName = enrichment?.displayName,
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
        "${row.lastInteractionAt?.toEpochMilliseconds() ?: 0}|${row.personRef}"

    private fun String.removeIdentityPrefix(): String =
        substringAfter(':', this)

    private fun candidateSourceRefs(sourceRef: String): List<String> =
        listOf(
            sourceRef,
            sourceRef.removePrefix("raw:"),
            sourceRef.takeIf { !it.startsWith("raw:") }?.let { "raw:$it" }.orEmpty(),
        ).filter { it.isNotBlank() }.distinct()

    private fun SourcePersonCandidateEntity.toMatchCandidateSummary(
        blockedPersonRefs: Set<String>,
    ): PersonMatchCandidateSummary? {
        val anchor = email ?: phone ?: name ?: return null
        if (
            PersonIdentityResolver.isBlocked(anchor, blockedPersonRefs) ||
            PersonIdentityResolver.isLikelyAutomated(anchor)
        ) {
            return null
        }
        val displayName = name ?: email ?: phone ?: return null
        val detail = listOfNotNull(
            email?.takeUnless { it == displayName },
            phone?.takeUnless { it == displayName },
            organization,
        ).firstOrNull()
        return PersonMatchCandidateSummary(
            anchor = anchor,
            displayName = displayName,
            detail = detail,
            role = role,
            evidence = evidence,
            confidence = confidence,
        )
    }

    private fun String?.toSourceSet(): Set<String> =
        this
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toCollection(linkedSetOf())
            ?: emptySet()

    private companion object {
        const val PAGE_SIZE: Int = 20
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
