package com.becalm.android.unit.ui.persons

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.FirstMemoryRepository
import com.becalm.android.data.repository.PersonManualMatchRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.PersonListRemoteRepository
import com.becalm.android.ui.persons.PersonActionFeedStatusKind
import com.becalm.android.ui.persons.PersonActionSummary
import com.becalm.android.ui.persons.PersonListProjection
import com.becalm.android.ui.persons.PersonRow
import com.becalm.android.ui.persons.PersonSectionKind
import com.becalm.android.ui.persons.PersonsListPageProjection
import com.becalm.android.ui.persons.PersonsOfflineStatus
import com.becalm.android.ui.persons.PersonsRefreshCoordinator
import com.becalm.android.ui.persons.PersonsRefreshSnapshot
import com.becalm.android.ui.persons.PersonsSortOrder
import com.becalm.android.ui.persons.PersonsScreenProjectionPort
import com.becalm.android.ui.persons.PersonsViewModel
import com.becalm.android.ui.persons.UnassignedEventSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonsViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val projectionPort = FakePersonsScreenProjectionPort()
    private val refreshCoordinator = FakePersonsRefreshCoordinator()
    private val manualMatchRepository: PersonManualMatchRepository = mockk(relaxed = true)
    private val firstMemoryRepository: FirstMemoryRepository = mockk(relaxed = true)
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)
    private val personListRemoteRepository = FakePersonListRemoteRepository()
    private val actionSyncState = MutableStateFlow<PersonActionSyncStateEntity?>(null)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        personListRemoteRepository.reset()
        actionSyncState.value = null
        every { personActionRepository.observeSyncState(any(), any(), any()) } returns actionSyncState
        coEvery { personActionRepository.refresh(any(), any()) } returns BecalmResult.Success(
            PersonActionRefreshStats(
                fetched = 0,
                deleted = 0,
                serverWatermark = null,
                recomputeState = null,
            ),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `SRC-001 exposes card aggregate fields from projection`() = runTest {
        projectionPort.people.value = pageOf(
            person(
                ref = "display@example.com",
                displayName = "Display Name",
                nickname = "Nick",
                companyName = "ABC Corp",
                jobTitle = "Lead",
                eventCount = 4,
                pendingCommitmentCount = 2,
                channelSources = setOf(SourceType.VOICE, SourceType.GMAIL),
                lastInteractionSnippet = "latest snippet",
            ),
            person(
                ref = "nick@example.com",
                displayName = null,
                nickname = "Nick Only",
            ),
            person(
                ref = "+821012345678",
                displayName = null,
                nickname = null,
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val rowsByRef = viewModel.uiState.value.people.associateBy(PersonRow::personId)
        val primary = rowsByRef.getValue("display@example.com")
        assertEquals("Display Name", primary.displayLabel)
        assertEquals("Nick", primary.nickname)
        assertEquals("ABC Corp", primary.companyName)
        assertEquals("Lead", primary.jobTitle)
        assertEquals(4, primary.interactionCount)
        assertEquals(2, primary.pendingCommitmentCount)
        assertEquals(setOf(SourceType.VOICE, SourceType.GMAIL), primary.channelSources)
        assertEquals(null, primary.lastInteractionSnippet)

        val sections = viewModel.uiState.value.personSections.associateBy { it.kind }
        assertEquals(
            listOf("display@example.com"),
            sections.getValue(PersonSectionKind.PENDING_COMMITMENTS).people.map { it.personId },
        )
        assertEquals(
            listOf("nick@example.com", "+821012345678"),
            sections.getValue(PersonSectionKind.RECENT_CONTACTS).people.map { it.personId },
        )
    }

    @Test
    fun `NAP-UI person action becomes primary row context and pending section driver`() = runTest {
        val action = PersonActionSummary(
            id = "act-1",
            title = "오늘 16시 전 제안서 확인 요청에 답장",
            primaryVerb = "답장",
            shortReason = "김민홍이 제안서 확인을 기다리고 있습니다.",
            actionKind = "reply",
            dueAt = Instant.fromEpochMilliseconds(10_000),
            urgencyScore = 0.92,
        )
        projectionPort.people.value = pageOf(
            person(
                ref = "person-minhong",
                displayName = "김민홍",
                eventCount = 3,
                pendingCommitmentCount = 0,
                lastInteractionSnippet = "지난 미팅 요약",
                topAction = action,
            ),
            person(
                ref = "person-week",
                displayName = "이준호",
                topAction = action.copy(
                    id = "act-week",
                    title = "분기 미팅 날짜 제안하기",
                    urgencyScore = 25.0,
                ),
            ),
            person(ref = "person-recent", displayName = "최근 연락처"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val rows = viewModel.uiState.value.people.associateBy(PersonRow::personId)
        val actionRow = rows.getValue("person-minhong")
        assertEquals(action, actionRow.topAction)
        assertEquals("오늘 16시 전 제안서 확인 요청에 답장", actionRow.lastInteractionSnippet)
        assertEquals(
            listOf("person-minhong"),
            viewModel.uiState.value.personSections
                .first { it.kind == PersonSectionKind.PENDING_COMMITMENTS }
                .people
                .map(PersonRow::personId),
        )
        assertEquals(
            listOf("person-week"),
            viewModel.uiState.value.personSections
                .first { it.kind == PersonSectionKind.THIS_WEEK_ACTIONS }
                .people
                .map(PersonRow::personId),
        )
        assertEquals(
            listOf("person-recent"),
            viewModel.uiState.value.personSections
                .first { it.kind == PersonSectionKind.RECENT_CONTACTS }
                .people
                .map(PersonRow::personId),
        )
    }

    @Test
    fun `NAP-UI action feed degraded sync state becomes visible readiness state`() = runTest {
        actionSyncState.value = syncState(
            recomputeState = "degraded",
            capacityState = "quota_degraded",
            backlogLagSeconds = 600,
        )
        projectionPort.people.value = pageOf(
            person(
                ref = "person-minhong",
                displayName = "김민홍",
                topAction = PersonActionSummary(
                    id = "act-urgent",
                    title = "수정 계약서 회신",
                    primaryVerb = "답장",
                    shortReason = "오늘까지 보내기로 한 약속입니다.",
                    actionKind = "reply",
                    dueAt = null,
                    urgencyScore = 92.0,
                ),
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val status = viewModel.uiState.value.actionFeedStatus
        assertEquals(PersonActionFeedStatusKind.QUOTA_DELAY, status?.kind)
        assertEquals(600, status?.backlogLagSeconds)

        actionSyncState.value = syncState(recomputeState = "caught_up", capacityState = "normal")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.actionFeedStatus)
    }

    @Test
    fun `SRC-001 ENR-006 nickname fallback should surface nickname before redacted raw ref`() = runTest {
        projectionPort.people.value = pageOf(
            person(
                ref = "nick@example.com",
                displayName = null,
                nickname = "Nick Only",
            ),
            person(
                ref = "+821012345678",
                displayName = null,
                nickname = null,
            ),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val rowsByRef = viewModel.uiState.value.people.associateBy(PersonRow::personId)
        assertEquals("Nick Only", rowsByRef.getValue("nick@example.com").displayLabel)
        assertEquals("", rowsByRef.getValue("+821012345678").displayLabel)
    }

    @Test
    fun `SRC-003 query debounce filters by name email phone role and company substrings and clears back to full list`() = runTest {
        projectionPort.people.value = pageOf(
            person(ref = "+821012345678", displayName = "Kim Chulsoo"),
            person(ref = "lee@corp.com", displayName = "Minji Lee"),
            person(ref = "tax-pro-1", displayName = "Park Hana", companyName = "Calm Tax", jobTitle = "세무사"),
            person(ref = "legal-pro-1", displayName = "Jung Mirae", companyName = "법무법인 조용"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.people.size)

        viewModel.onQueryChange("lee")
        assertEquals("lee", viewModel.uiState.value.query)
        assertEquals(4, viewModel.uiState.value.people.size)
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("lee", viewModel.uiState.value.query)
        assertEquals(listOf("lee@corp.com"), viewModel.uiState.value.people.map { it.personId })

        viewModel.onQueryChange("1234")
        assertEquals("1234", viewModel.uiState.value.query)
        assertEquals(listOf("lee@corp.com"), viewModel.uiState.value.people.map { it.personId })
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("1234", viewModel.uiState.value.query)
        assertEquals(listOf("+821012345678"), viewModel.uiState.value.people.map { it.personId })

        viewModel.onQueryChange("세무사")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("세무사", viewModel.uiState.value.query)
        assertEquals(listOf("tax-pro-1"), viewModel.uiState.value.people.map { it.personId })

        viewModel.onQueryChange("법무법인")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("법무법인", viewModel.uiState.value.query)
        assertEquals(listOf("legal-pro-1"), viewModel.uiState.value.people.map { it.personId })

        viewModel.onQueryChange("")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.query)
        assertEquals(4, viewModel.uiState.value.people.size)
    }

    @Test
    fun `people search includes contact-only rows without showing them by default`() = runTest {
        projectionPort.people.value = pageOf(
            person(ref = "lee@corp.com", displayName = "Minji Lee"),
        )
        projectionPort.searchableContacts.value = listOf(
            person(ref = "+82109998888", displayName = "Kim Contact", eventCount = 0),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(listOf("lee@corp.com"), viewModel.uiState.value.people.map { it.personId })
        assertEquals(
            listOf("lee@corp.com", "+82109998888"),
            viewModel.uiState.value.matchChoices.map { it.anchor },
        )

        viewModel.onQueryChange("Kim")
        advanceTimeBy(300)
        advanceUntilIdle()

        assertEquals(listOf("+82109998888"), viewModel.uiState.value.people.map { it.personId })
        assertEquals(0, viewModel.uiState.value.people.single().interactionCount)
    }

    @Test
    fun `ENR-004 keeps phone and email person refs as separate rows for the same contact`() = runTest {
        projectionPort.people.value = pageOf(
            person(ref = "+821012345678", displayName = "Kim Chulsoo"),
            person(ref = "kim@corp.com", displayName = "Kim Chulsoo"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(
            listOf("+821012345678", "kim@corp.com"),
            viewModel.uiState.value.people.map { it.personId }.sorted(),
        )

        viewModel.onQueryChange("Kim Chulsoo")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.people.size)
    }

    @Test
    fun `SRC-005 exposes unassigned bucket content with expanded review page contract`() = runTest {
        projectionPort.unassigned.value = listOf(
            unassigned(id = "evt-1", sourceType = SourceType.VOICE, title = "Voice note"),
            unassigned(id = "evt-2", sourceType = SourceType.GMAIL, title = "Email subject"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("user-1", projectionPort.lastUserId)
        assertEquals(120, projectionPort.lastUnassignedLimit)
        assertEquals(120, state.pageSize)
        assertEquals(PersonsSortOrder.MOST_RECENT_EVENT_DESC, state.sortOrder)
        assertEquals(listOf("evt-1", "evt-2"), state.unassignedEvents.map { it.id })
        assertEquals(listOf("Voice note", "Email subject"), state.unassignedEvents.map { it.title })
    }

    @Test
    fun `SRC-001 owner seam exposes pagination cursor hasMorePages and sort order in state`() = runTest {
        projectionPort.people.value = pageOf(
            person(ref = "alpha@example.com", displayName = "Alpha"),
            person(ref = "beta@example.com", displayName = "Beta"),
            hasMorePages = true,
            nextCursor = "cursor-2",
            sortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("alpha@example.com", "beta@example.com"), state.people.map { it.personId })
        assertTrue(state.hasMorePages)
        assertEquals("cursor-2", state.nextCursor)
        assertEquals(PersonsSortOrder.MOST_RECENT_EVENT_DESC, state.sortOrder)
    }

    @Test
    fun `SRC-006 onPullRefresh stores refresh fanout snapshot from coordinator`() = runTest {
        refreshCoordinator.snapshot = PersonsRefreshSnapshot(
            roomRequeryTriggered = true,
            catchUpTriggered = false,
            enrichmentTriggered = true,
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertEquals(0, refreshCoordinator.refreshCount)
        viewModel.onPullRefresh()
        advanceUntilIdle()
        assertEquals(1, refreshCoordinator.refreshCount)
        assertTrue(viewModel.uiState.value.lastRefreshSnapshot?.roomRequeryTriggered == true)
        assertFalse(viewModel.uiState.value.lastRefreshSnapshot?.catchUpTriggered == true)
        assertTrue(viewModel.uiState.value.lastRefreshSnapshot?.enrichmentTriggered == true)
    }

    @Test
    fun `SRC-006 onPullRefresh clears refreshing and reports error when coordinator fails`() = runTest {
        refreshCoordinator.failure = RuntimeException("scheduler failed")

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onPullRefresh()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.refreshing)
        assertTrue(viewModel.uiState.value.error != null)
        assertEquals(1, refreshCoordinator.refreshCount)
    }

    @Test
    // spec: ERR-001
    fun `SRC-007 offline badge state follows offline projection`() = runTest {
        val offlineAt = Instant.fromEpochMilliseconds(9_000)
        projectionPort.offline.value = PersonsOfflineStatus(isOffline = true, lastSyncAt = offlineAt)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showOfflineBadge)
        assertEquals(offlineAt, viewModel.uiState.value.offlineLastSyncAt)

        projectionPort.offline.value = PersonsOfflineStatus(isOffline = false, lastSyncAt = null)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showOfflineBadge)
        assertNull(viewModel.uiState.value.offlineLastSyncAt)
    }

    @Test
    fun `manual match exposes saving state and hides item only after repository success`() = runTest {
        val event = unassigned(id = "evt-match", sourceType = SourceType.GMAIL, title = "Email subject")
        projectionPort.unassigned.value = listOf(event)
        coEvery {
            manualMatchRepository.matchInteraction(
                userId = "user-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "evt-match",
                interactionKind = SourceType.GMAIL,
                personAnchor = "minji@corp.com",
                nickname = "Minji",
            )
        } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onManualMatch(event, personAnchor = "minji@corp.com", nickname = "Minji")
        assertEquals(setOf("evt-match"), viewModel.uiState.value.savingMatchEventIds)
        assertTrue(viewModel.uiState.value.resolvedMatchEventIds.isEmpty())

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savingMatchEventIds.isEmpty())
        assertEquals(setOf("evt-match"), viewModel.uiState.value.resolvedMatchEventIds)
    }

    @Test
    fun `manual match failure keeps item retryable and reports error`() = runTest {
        val event = unassigned(id = "evt-fail", sourceType = SourceType.GMAIL, title = "Email subject")
        projectionPort.unassigned.value = listOf(event)
        coEvery {
            manualMatchRepository.matchInteraction(any(), any(), any(), any(), any(), any())
        } returns BecalmResult.Failure(BecalmError.NotFound("source_event_participant"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onManualMatch(event, personAnchor = "minji@corp.com", nickname = "Minji")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savingMatchEventIds.isEmpty())
        assertTrue(viewModel.uiState.value.resolvedMatchEventIds.isEmpty())
        assertTrue(viewModel.uiState.value.error != null)
    }

    @Test
    fun `manual match ignores duplicate taps while save is in flight`() = runTest {
        val event = unassigned(id = "evt-dup", sourceType = SourceType.GMAIL, title = "Email subject")
        projectionPort.unassigned.value = listOf(event)
        coEvery {
            manualMatchRepository.matchInteraction(any(), any(), any(), any(), any(), any())
        } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onManualMatch(event, personAnchor = "minji@corp.com", nickname = "Minji")
        viewModel.onManualMatch(event, personAnchor = "minji@corp.com", nickname = "Minji")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            manualMatchRepository.matchInteraction(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `not self match only opens manual follow-up after repository success`() = runTest {
        val event = unassigned(id = "evt-not-self", sourceType = SourceType.GMAIL, title = "Email subject")
        projectionPort.unassigned.value = listOf(event)
        coEvery {
            manualMatchRepository.rejectInteractionAsSelf(
                userId = "user-1",
                sourceType = SourceType.GMAIL,
                sourceRef = "evt-not-self",
                interactionKind = SourceType.GMAIL,
            )
        } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onNotSelfMatch(event)
        assertTrue(viewModel.uiState.value.notSelfMatchEventIds.isEmpty())
        assertEquals(setOf("evt-not-self"), viewModel.uiState.value.savingMatchEventIds)

        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savingMatchEventIds.isEmpty())
        assertEquals(setOf("evt-not-self"), viewModel.uiState.value.notSelfMatchEventIds)
        assertTrue(viewModel.uiState.value.resolvedMatchEventIds.isEmpty())
    }

    @Test
    fun `not self match failure keeps suggested self action retryable`() = runTest {
        val event = unassigned(id = "evt-not-self-fail", sourceType = SourceType.GMAIL, title = "Email subject")
        projectionPort.unassigned.value = listOf(event)
        coEvery {
            manualMatchRepository.rejectInteractionAsSelf(any(), any(), any(), any())
        } returns BecalmResult.Failure(BecalmError.NotFound("source_event_participant"))

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onNotSelfMatch(event)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.savingMatchEventIds.isEmpty())
        assertTrue(viewModel.uiState.value.notSelfMatchEventIds.isEmpty())
        assertTrue(viewModel.uiState.value.error != null)
    }

    @Test
    fun `initial load refreshes remote person mirror for reinstall recovery`() = runTest {
        buildViewModel()
        advanceUntilIdle()

        assertEquals(listOf("user-1"), personListRemoteRepository.refreshUserIds)
    }

    @Test
    fun `pull refresh includes remote person restore before local fanout`() = runTest {
        val viewModel = buildViewModel()
        advanceUntilIdle()
        personListRemoteRepository.reset()

        viewModel.onPullRefresh()
        advanceUntilIdle()

        assertEquals(listOf("user-1"), personListRemoteRepository.refreshUserIds)
        assertEquals(1, refreshCoordinator.refreshCount)
    }

    private fun buildViewModel(): PersonsViewModel = PersonsViewModel(
        userPrefsStore = userPrefsStore,
        projectionPort = projectionPort,
        refreshCoordinator = refreshCoordinator,
        manualMatchRepository = manualMatchRepository,
        firstMemoryRepository = firstMemoryRepository,
        personListRemoteRepository = personListRemoteRepository,
        personActionRepository = personActionRepository,
        ioDispatcher = testDispatcher,
    )

    private fun person(
        ref: String,
        displayName: String?,
        nickname: String? = null,
        companyName: String? = null,
        jobTitle: String? = null,
        eventCount: Int = 1,
        pendingCommitmentCount: Int = 0,
        channelSources: Set<String> = emptySet(),
        lastInteractionSnippet: String? = null,
        topAction: PersonActionSummary? = null,
    ): PersonListProjection = PersonListProjection(
        personId = ref,
        displayName = displayName,
        nickname = nickname,
        companyName = companyName,
        jobTitle = jobTitle,
        eventCount = eventCount,
        pendingCommitmentCount = pendingCommitmentCount,
        channelSources = channelSources,
        lastInteractionAt = Instant.fromEpochMilliseconds(1_000),
        lastInteractionSnippet = lastInteractionSnippet,
        topAction = topAction,
    )

    private fun pageOf(
        vararg rows: PersonListProjection,
        hasMorePages: Boolean = false,
        nextCursor: String? = null,
        sortOrder: PersonsSortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
    ): PersonsListPageProjection = PersonsListPageProjection(
        rows = rows.toList(),
        hasMorePages = hasMorePages,
        nextCursor = nextCursor,
        sortOrder = sortOrder,
    )

    private fun unassigned(
        id: String,
        sourceType: String,
        title: String,
    ): UnassignedEventSummary = UnassignedEventSummary(
        id = id,
        sourceType = sourceType,
        title = title,
        timestamp = Instant.fromEpochMilliseconds(1_000),
    )

    private fun syncState(
        recomputeState: String?,
        capacityState: String?,
        backlogLagSeconds: Int? = null,
    ): PersonActionSyncStateEntity = PersonActionSyncStateEntity(
        userId = "user-1",
        surfaceKey = "person",
        status = "active",
        serverWatermark = Instant.parse("2026-06-04T01:00:00Z"),
        recomputeState = recomputeState,
        capacityState = capacityState,
        capacityBacklogLagSeconds = backlogLagSeconds,
        lastSyncedAt = Instant.parse("2026-06-04T01:00:01Z"),
        updatedAt = Instant.parse("2026-06-04T01:00:01Z"),
    )

    private class FakePersonsScreenProjectionPort : PersonsScreenProjectionPort {
        val people = MutableStateFlow(
            PersonsListPageProjection(
                rows = emptyList(),
                hasMorePages = false,
                nextCursor = null,
                sortOrder = PersonsSortOrder.MOST_RECENT_EVENT_DESC,
            ),
        )
        val unassigned = MutableStateFlow<List<UnassignedEventSummary>>(emptyList())
        val searchableContacts = MutableStateFlow<List<PersonListProjection>>(emptyList())
        val offline = MutableStateFlow(PersonsOfflineStatus(isOffline = false, lastSyncAt = null))
        var lastUserId: String? = null
        var lastUnassignedLimit: Int? = null

        override fun observePeople(userId: String): Flow<PersonsListPageProjection> {
            lastUserId = userId
            return people
        }

        override fun observeSearchableContacts(userId: String): Flow<List<PersonListProjection>> {
            lastUserId = userId
            return searchableContacts
        }

        override fun observeUnassigned(userId: String, limit: Int): Flow<List<UnassignedEventSummary>> {
            lastUserId = userId
            lastUnassignedLimit = limit
            return unassigned
        }

        override fun observeOfflineStatus(): Flow<PersonsOfflineStatus> = offline
    }

    private class FakePersonsRefreshCoordinator : PersonsRefreshCoordinator {
        var refreshCount: Int = 0
        var snapshot: PersonsRefreshSnapshot = PersonsRefreshSnapshot(
            roomRequeryTriggered = true,
            catchUpTriggered = true,
            enrichmentTriggered = true,
        )
        var failure: RuntimeException? = null

        override fun refresh(): PersonsRefreshSnapshot {
            refreshCount += 1
            failure?.let { throw it }
            return snapshot
        }
    }

    private class FakePersonListRemoteRepository : PersonListRemoteRepository {
        val refreshUserIds = mutableListOf<String>()

        fun reset() {
            refreshUserIds.clear()
        }

        override suspend fun refreshPeople(
            userId: String,
            limit: Int,
        ): BecalmResult<PersonListRemoteRepository.RefreshStats> {
            refreshUserIds += userId
            return BecalmResult.Success(
                PersonListRemoteRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    eventRowsFetched = 0,
                    eventRowsUpserted = 0,
                    eventRefreshFailures = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
        }
    }
}
