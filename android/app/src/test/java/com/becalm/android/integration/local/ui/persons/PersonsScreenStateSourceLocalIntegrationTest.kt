package com.becalm.android.integration.local.ui.persons

import app.cash.turbine.test
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.person.PersonIdentityResolver
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.persons.EnrichmentBackedPersonsScreenProjectionPort
import com.becalm.android.ui.persons.PersonsScreenStateSource
import com.becalm.android.ui.persons.PersonsSortOrder
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PersonsScreenStateSourceLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val userPrefsStore = UserPrefsStoreImpl(
        dataStore = LocalIntegrationSupport.prefsDataStore("persons-state-source"),
    )
    private val sourceStatusRepository = mockk<SourceStatusRepository> {
        every { observeAll() } returns MutableStateFlow(emptyList())
    }

    private val enrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )
    private val projectionPort = EnrichmentBackedPersonsScreenProjectionPort(
        personEnrichmentRepository = enrichmentRepository,
        personIndexDao = db.personIndexDao(),
        sourceStatusRepository = sourceStatusRepository,
        userPrefsStore = userPrefsStore,
    )

    @Before
    fun setUp() = runTest {
        userPrefsStore.setCurrentUserId(USER_ID)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `SRC-001 SRC-005 and ENR-006 room aggregate flows into persons state`() = runTest {
        val stateSource = PersonsScreenStateSource(
            userPrefsStore = userPrefsStore,
            projectionPort = projectionPort,
        )
        val query = MutableStateFlow("")

        db.personEnrichmentDao().upsert(
            PersonEnrichmentEntity(
                personRef = "+821012345678",
                displayName = "김철수",
                nickname = "철수",
                company = "ABC Corp",
                title = "팀장",
                lastSyncedAt = Instant.parse("2026-04-23T00:00:00Z"),
            ),
        )
        db.rawIngestionEventDao().insertAll(
            listOf(
                rawEvent(
                    id = "raw-1",
                    counterpartyRef = "+821012345678",
                    sourceType = SourceType.VOICE,
                    eventTitle = "Voice recap",
                    eventSnippet = "가장 최근 음성 메모",
                    timestamp = Instant.parse("2026-04-23T03:00:00Z"),
                ),
                rawEvent(
                    id = "raw-2",
                    counterpartyRef = "unknown@corp.com",
                    sourceType = SourceType.GMAIL,
                    eventTitle = "Follow-up email",
                    eventSnippet = "메일 미리보기",
                    timestamp = Instant.parse("2026-04-23T02:00:00Z"),
                ),
                rawEvent(
                    id = "raw-unassigned",
                    counterpartyRef = null,
                    sourceType = SourceType.OUTLOOK_MAIL,
                    eventTitle = "No counterparty",
                    eventSnippet = null,
                    timestamp = Instant.parse("2026-04-23T01:00:00Z"),
                ),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "c-1",
                    counterpartyRef = "+821012345678",
                    title = "김철수에게 회신",
                    actionState = "pending",
                    sourceType = SourceType.VOICE,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T03:00:00Z"),
                ),
                commitment(
                    id = "c-2",
                    counterpartyRef = "unknown@corp.com",
                    title = "Unknown follow-up",
                    actionState = "completed",
                    sourceType = SourceType.GMAIL,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T02:00:00Z"),
                ),
            ),
        )
        upsertIdentityAndInteraction(
            anchor = "+821012345678",
            sourceType = SourceType.VOICE,
            sourceRef = "raw:raw-1",
            kind = "call",
            role = "counterparty",
            occurredAt = Instant.parse("2026-04-23T03:00:00Z"),
            title = "Voice recap",
            snippet = "가장 최근 음성 메모",
        )
        upsertIdentityAndInteraction(
            anchor = "+821012345678",
            sourceType = SourceType.VOICE,
            sourceRef = "commitment:c-1",
            kind = "commitment",
            role = CommitmentItemType.ACTION,
            direction = "give",
            status = "pending",
            occurredAt = Instant.parse("2026-04-23T03:00:00Z"),
            title = "김철수에게 회신",
            snippet = "quote-c-1",
        )
        upsertIdentityAndInteraction(
            anchor = "unknown@corp.com",
            sourceType = SourceType.GMAIL,
            sourceRef = "raw:raw-2",
            kind = "email",
            role = "counterparty",
            occurredAt = Instant.parse("2026-04-23T02:00:00Z"),
            title = "Follow-up email",
            snippet = "메일 미리보기",
        )
        db.personIndexDao().upsertUnmatchedInteractions(
            listOf(
                UnmatchedPersonInteractionEntity(
                    id = "unmatched-raw-unassigned",
                    userId = USER_ID,
                    sourceType = SourceType.OUTLOOK_MAIL,
                    sourceRef = "raw:raw-unassigned",
                    interactionKind = "email",
                    title = "No counterparty",
                    snippet = null,
                    suggestedLabel = "No counterparty",
                    occurredAt = Instant.parse("2026-04-23T01:00:00Z"),
                    createdAt = Instant.parse("2026-04-23T01:00:00Z"),
                ),
            ),
        )
        val phonePersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "+821012345678")).personId
        val unknownPersonId = requireNotNull(PersonIdentityResolver.resolve(USER_ID, "unknown@corp.com")).personId

        stateSource.observe(query, pageSize = 20, queryDebounceMs = 0L).test {
            var updated = awaitItem()
            while (
                updated.people.size < 2 ||
                    updated.unassignedEvents.isEmpty() ||
                    updated.people.firstOrNull { it.personId == phonePersonId }?.pendingCommitmentCount != 1
            ) {
                updated = awaitItem()
            }

            assertEquals(listOf(phonePersonId, unknownPersonId), updated.people.map { it.personId })
            val first = updated.people.first()
            assertEquals("김철수", first.displayLabel)
            assertEquals(1, first.interactionCount)
            assertEquals(1, first.pendingCommitmentCount)
            assertEquals(setOf(SourceType.VOICE), first.channelSources)
            assertEquals("가장 최근 음성 메모", first.lastInteractionSnippet)

            val second = updated.people.last()
            assertEquals("unknown@corp.com", second.displayLabel)
            assertEquals(0, second.pendingCommitmentCount)
            assertEquals("메일 미리보기", second.lastInteractionSnippet)

            assertEquals(PersonsSortOrder.MOST_RECENT_EVENT_DESC, updated.sortOrder)
            assertEquals(listOf("raw:raw-unassigned"), updated.unassignedEvents.map { it.sourceRef })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `SRC-001 first page exposes hasMorePages and nextCursor when more than twenty rows exist`() = runTest {
        val stateSource = PersonsScreenStateSource(
            userPrefsStore = userPrefsStore,
            projectionPort = projectionPort,
        )
        val query = MutableStateFlow("")

        val rows = (1..21).map { index ->
            rawEvent(
                id = "raw-$index",
                counterpartyRef = "person-$index@corp.com",
                sourceType = SourceType.GMAIL,
                eventTitle = "subject-$index",
                eventSnippet = "snippet-$index",
                timestamp = Instant.fromEpochMilliseconds(1_000L * 60 * 60 * index),
            )
        }
        db.rawIngestionEventDao().insertAll(rows)
        rows.forEach { row ->
            upsertIdentityAndInteraction(
                anchor = requireNotNull(row.counterpartyRef),
                sourceType = row.sourceType,
                sourceRef = "raw:${row.id}",
                kind = "email",
                role = "counterparty",
                occurredAt = row.timestamp,
                title = row.eventTitle,
                snippet = row.eventSnippet,
            )
        }
        stateSource.observe(query, pageSize = 20, queryDebounceMs = 0L).test {
            var state = awaitItem()
            while (state.people.size < 20) {
                state = awaitItem()
            }
            assertEquals(20, state.people.size)
            assertTrue(state.hasMorePages)
            assertFalse(state.nextCursor.isNullOrBlank())
            assertEquals("person-21@corp.com", state.people.first().displayLabel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `persons aggregate counts schedule and decision rows as pending trackables`() = runTest {
        val stateSource = PersonsScreenStateSource(
            userPrefsStore = userPrefsStore,
            projectionPort = projectionPort,
        )
        val query = MutableStateFlow("")

        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "action-1",
                    counterpartyRef = "+821012345678",
                    title = "액션",
                    actionState = "pending",
                    sourceType = SourceType.VOICE,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T01:00:00Z"),
                ),
                commitment(
                    id = "schedule-1",
                    counterpartyRef = "+821012345678",
                    title = "일정 변경",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    actionState = "pending",
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T02:00:00Z"),
                ),
                commitment(
                    id = "decision-1",
                    counterpartyRef = "+821012345678",
                    title = "결정",
                    itemType = CommitmentItemType.DECISION,
                    direction = null,
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    sourceEventOccurredAt = Instant.parse("2026-04-23T03:00:00Z"),
                ),
            ),
        )
        listOf(
            "action-1" to Triple(CommitmentItemType.ACTION, "give", "pending"),
            "schedule-1" to Triple(CommitmentItemType.SCHEDULE, null, "pending"),
            "decision-1" to Triple(CommitmentItemType.DECISION, null, "pending"),
        ).forEachIndexed { index, (id, metadata) ->
            upsertIdentityAndInteraction(
                anchor = "+821012345678",
                sourceType = when (id) {
                    "schedule-1" -> SourceType.GOOGLE_CALENDAR
                    "decision-1" -> SourceType.GMAIL
                    else -> SourceType.VOICE
                },
                sourceRef = "commitment:$id",
                kind = "commitment",
                role = metadata.first,
                direction = metadata.second,
                status = metadata.third,
                occurredAt = Instant.parse("2026-04-23T0${index + 1}:00:00Z"),
                title = when (id) {
                    "schedule-1" -> "일정 변경"
                    "decision-1" -> "결정"
                    else -> "액션"
                },
                snippet = "quote-$id",
            )
        }
        stateSource.observe(query, pageSize = 20, queryDebounceMs = 0L).test {
            var state = awaitItem()
            while (state.people.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(3, state.people.single().pendingCommitmentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun rawEvent(
        id: String,
        counterpartyRef: String?,
        sourceType: String,
        eventTitle: String?,
        eventSnippet: String?,
        timestamp: Instant,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = USER_ID,
        clientEventId = "client-$id",
        sourceType = sourceType,
        sourceRef = "source-$id",
        counterpartyRef = counterpartyRef,
        eventTitle = eventTitle,
        eventSnippet = eventSnippet,
        commitmentsExtractedCount = 0,
        timestamp = timestamp,
        syncStatus = "synced",
    )

    private fun commitment(
        id: String,
        counterpartyRef: String?,
        title: String,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        actionState: String,
        sourceType: String,
        sourceEventOccurredAt: Instant,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = USER_ID,
        itemType = itemType,
        direction = direction,
        counterpartyRaw = counterpartyRef,
        counterpartyRef = counterpartyRef,
        title = title,
        description = null,
        quote = "quote-$id",
        sourceEventTitle = "event-$id",
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = null,
        dueHint = null,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = "source-$id",
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = sourceEventOccurredAt,
        updatedAt = sourceEventOccurredAt,
    )

    private suspend fun upsertIdentityAndInteraction(
        anchor: String,
        sourceType: String,
        sourceRef: String,
        kind: String,
        role: String,
        occurredAt: Instant,
        title: String?,
        snippet: String?,
        direction: String? = null,
        status: String? = null,
    ) {
        val resolved = requireNotNull(PersonIdentityResolver.resolve(USER_ID, anchor))
        db.personIndexDao().upsertIdentities(
            listOf(
                PersonIdentityEntity(
                    id = PersonIdentityResolver.stableIdentityId(USER_ID, resolved.identityKey),
                    userId = USER_ID,
                    personId = resolved.personId,
                    identityKey = resolved.identityKey,
                    identityType = resolved.identityType,
                    rawValue = anchor,
                    displayNameHint = anchor,
                    sourceType = sourceType,
                    confidence = 1.0,
                    verified = true,
                    lastSeenAt = occurredAt,
                ),
            ),
        )
        db.personIndexDao().upsertInteractions(
            listOf(
                PersonInteractionEntity(
                    id = UUID.nameUUIDFromBytes("test-interaction:$sourceRef:${resolved.personId}:$kind".toByteArray()).toString(),
                    userId = USER_ID,
                    personId = resolved.personId,
                    sourceType = sourceType,
                    sourceRef = sourceRef,
                    interactionKind = kind,
                    role = role,
                    direction = direction,
                    status = status,
                    occurredAt = occurredAt,
                    title = title,
                    snippet = snippet,
                    confidence = 1.0,
                ),
            ),
        )
    }

    private companion object {
        const val USER_ID: String = "user-1"
    }
}
