package com.becalm.android.ui.persons

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.db.dao.PersonInteractionAggregateRow
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
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

/** Unassigned bucket item surfaced on the persons screen. */
public data class UnassignedEventSummary(
    val id: String,
    val sourceType: String,
    val title: String?,
    val timestamp: Instant,
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
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PersonsScreenProjectionPort {

    override fun observePeople(userId: String): Flow<PersonsListPageProjection> =
        combine(
            personEnrichmentRepository.observeAll(),
            rawIngestionRepository.observePersonInteractionAggregates(userId, PAGE_SIZE + 1),
        ) { enrichmentRows, aggregateRows ->
            buildProjectionPage(
                enrichmentRows = enrichmentRows,
                aggregateRows = aggregateRows,
            )
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override fun observeUnassigned(userId: String, limit: Int): Flow<List<UnassignedEventSummary>> =
        rawIngestionRepository.observeTimelineForUser(userId, limit).map { events ->
            events.map { event ->
                UnassignedEventSummary(
                    id = event.id,
                    sourceType = event.sourceType,
                    title = event.eventTitle,
                    timestamp = event.timestamp,
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
        return PersonsRefreshSnapshot(
            roomRequeryTriggered = true,
            catchUpTriggered = true,
            enrichmentTriggered = true,
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
