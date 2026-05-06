package com.becalm.android.unit.ui.persons

import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.PersonManualMatchRepository
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

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
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
        assertEquals("+821012345678", rowsByRef.getValue("+821012345678").displayLabel)
    }

    @Test
    fun `SRC-003 query debounce filters by name email and phone substrings and clears back to full list`() = runTest {
        projectionPort.people.value = pageOf(
            person(ref = "+821012345678", displayName = "Kim Chulsoo"),
            person(ref = "lee@corp.com", displayName = "Minji Lee"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.people.size)

        viewModel.onQueryChange("lee")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals(listOf("lee@corp.com"), viewModel.uiState.value.people.map { it.personId })

        viewModel.onQueryChange("1234")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals(listOf("+821012345678"), viewModel.uiState.value.people.map { it.personId })

        viewModel.onQueryChange("")
        advanceTimeBy(300)
        advanceUntilIdle()
        assertEquals("", viewModel.uiState.value.query)
        assertEquals(2, viewModel.uiState.value.people.size)
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
    fun `SRC-005 exposes unassigned bucket content with twenty-item page contract`() = runTest {
        projectionPort.unassigned.value = listOf(
            unassigned(id = "evt-1", sourceType = SourceType.VOICE, title = "Voice note"),
            unassigned(id = "evt-2", sourceType = SourceType.GMAIL, title = "Email subject"),
        )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("user-1", projectionPort.lastUserId)
        assertEquals(20, projectionPort.lastUnassignedLimit)
        assertEquals(20, state.pageSize)
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

    private fun buildViewModel(): PersonsViewModel = PersonsViewModel(
        userPrefsStore = userPrefsStore,
        projectionPort = projectionPort,
        refreshCoordinator = refreshCoordinator,
        manualMatchRepository = manualMatchRepository,
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

        override fun refresh(): PersonsRefreshSnapshot {
            refreshCount += 1
            return snapshot
        }
    }
}
