package com.becalm.android.unit.ui.persons

import androidx.lifecycle.SavedStateHandle
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.ui.persons.ARG_PERSON_REF
import com.becalm.android.ui.persons.InteractionRow
import com.becalm.android.ui.persons.PersonDetailViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class PersonDetailViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val commitmentRepository: CommitmentRepository = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)

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
    fun `missing person id yields immediate error state`() = runTest {
        val viewModel = buildViewModel(personRef = "")

        assertEquals("Person ID missing", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `SRC-002 SRC-008 detail header exposes nickname counts channels and completed is collapsed by default`() = runTest {
        val personRef = "alice@example.com"
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns
            flowOf(
                PersonEnrichmentEntity(
                    personRef = personRef,
                    displayName = "Alice",
                    nickname = "Al",
                    company = "BeCalm",
                    title = "PM",
                    lastSyncedAt = Instant.fromEpochMilliseconds(0),
                ),
            )
        every { rawIngestionRepository.observeForPerson("user-1", personRef, 100) } returns
            flowOf(
                listOf(
                    rawEvent(
                        id = "voice-1",
                        sourceType = SourceType.VOICE,
                        timestamp = Instant.fromEpochMilliseconds(3_000),
                        snippet = "voice snippet",
                    ),
                    rawEvent(
                        id = "mail-1",
                        sourceType = SourceType.GMAIL,
                        timestamp = Instant.fromEpochMilliseconds(1_000),
                        snippet = "mail snippet",
                    ),
                ),
            )
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns
            flowOf(
                listOf(
                    commitment(id = "pending", actionState = "pending", timestamp = 2_000),
                    commitment(id = "done", actionState = "COMPLETED", timestamp = 4_000),
                ),
            )
        every { calendarEventRepository.observeForPerson("user-1", personRef, 50) } returns flowOf(emptyList())

        val viewModel = buildViewModel(personRef = personRef)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("Alice", state.displayName)
        assertEquals("Al", state.nickname)
        assertEquals("BeCalm", state.companyName)
        assertEquals("PM", state.jobTitle)
        assertEquals(2, state.eventCount)
        assertEquals(1, state.emailInteractionCount)
        assertEquals(0, state.callInteractionCount)
        assertEquals(0, state.meetingCount)
        assertEquals(1, state.pendingCommitmentCount)
        assertEquals(setOf(SourceType.VOICE, SourceType.GMAIL), state.channelSources)
        assertFalse(state.completedExpanded)
        assertEquals(listOf("pending"), state.pendingCommitments.map { it.title })
        assertEquals(listOf("done"), state.completedCommitments.map { it.title })

        viewModel.onToggleCompletedExpanded()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.completedExpanded)

        viewModel.onToggleCompletedExpanded()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.completedExpanded)
    }

    @Test
    fun `SRC-008 history sorts raw events newest first and carries extracted badge count`() = runTest {
        val personRef = "alice@example.com"
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns
            flowOf(
                PersonEnrichmentEntity(
                    personRef = personRef,
                    displayName = "Alice",
                    lastSyncedAt = Instant.fromEpochMilliseconds(0),
                ),
            )
        every { rawIngestionRepository.observeForPerson("user-1", personRef, 100) } returns
            flowOf(
                listOf(
                    rawEvent(
                        id = "raw-new",
                        sourceType = SourceType.VOICE,
                        timestamp = Instant.fromEpochMilliseconds(3_000),
                        snippet = "x".repeat(240),
                    ),
                    rawEvent(
                        id = "raw-old",
                        sourceType = SourceType.GMAIL,
                        timestamp = Instant.fromEpochMilliseconds(1_000),
                        snippet = "short",
                    ),
                ),
            )
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForPerson("user-1", personRef, 50) } returns flowOf(emptyList())

        val viewModel = buildViewModel(personRef = personRef)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.interactionHistory.none { it is InteractionRow.Commitment })
        assertEquals(
            listOf("raw-new", "raw-old"),
            state.interactionHistory.map {
                when (it) {
                    is InteractionRow.Event -> it.id
                    is InteractionRow.CalendarMeeting -> error("calendar history not part of this contract")
                    is InteractionRow.Commitment -> error("unreachable")
                }
            },
        )
        val newestEvent = state.interactionHistory.first() as InteractionRow.Event
        assertEquals("raw-new", newestEvent.summary)
        assertEquals(200, newestEvent.snippet!!.length)
        assertEquals(2, newestEvent.commitmentsExtractedCount)
    }

    @Test
    fun `schedule and decision items stay in pending section`() = runTest {
        val personRef = "alice@example.com"
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns flowOf(
            PersonEnrichmentEntity(
                personRef = personRef,
                displayName = "Alice",
                lastSyncedAt = Instant.fromEpochMilliseconds(0),
            ),
        )
        every { rawIngestionRepository.observeForPerson("user-1", personRef, 100) } returns flowOf(emptyList())
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(
            listOf(
                commitment(id = "action", actionState = "pending", timestamp = 1_000),
                commitment(
                    id = "schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    actionState = "pending",
                    scheduleStatus = "changed",
                    timestamp = 2_000,
                ),
                commitment(
                    id = "decision",
                    itemType = CommitmentItemType.DECISION,
                    direction = null,
                    actionState = "pending",
                    decisionStatus = "approved",
                    timestamp = 3_000,
                ),
            ),
        )
        every { calendarEventRepository.observeForPerson("user-1", personRef, 50) } returns flowOf(emptyList())

        val viewModel = buildViewModel(personRef = personRef)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.pendingCommitmentCount)
        assertEquals(listOf("decision", "schedule", "action"), state.pendingCommitments.map { it.title })
        assertTrue(state.completedCommitments.isEmpty())
    }

    @Test
    fun `SRC-002 ENR-006 detail falls back to raw person ref when enrichment display name is absent`() = runTest {
        val personRef = "unknown@domain.com"
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns
            flowOf(
                PersonEnrichmentEntity(
                    personRef = personRef,
                    displayName = null,
                    nickname = null,
                    company = null,
                    title = null,
                    lastSyncedAt = Instant.fromEpochMilliseconds(0),
                ),
            )
        every { rawIngestionRepository.observeForPerson("user-1", personRef, 100) } returns flowOf(emptyList())
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForPerson("user-1", personRef, 50) } returns flowOf(emptyList())

        val viewModel = buildViewModel(personRef = personRef)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(personRef, state.displayName)
        assertEquals(emptyList<InteractionRow.Commitment>(), state.pendingCommitments)
        assertEquals(emptyList<InteractionRow.Commitment>(), state.completedCommitments)
    }

    @Test
    fun `inner observe failure surfaces error and dismiss action clears it`() = runTest {
        val personRef = "alice@example.com"
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns flow {
            throw IllegalStateException("observe failed")
        }
        every { rawIngestionRepository.observeForPerson("user-1", personRef, 100) } returns flowOf(emptyList())
        every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForPerson("user-1", personRef, 50) } returns flowOf(emptyList())

        val viewModel = buildViewModel(personRef = personRef)
        advanceUntilIdle()

        assertEquals("observe failed", viewModel.uiState.value.error)
        viewModel.onErrorDismissed()
        assertNull(viewModel.uiState.value.error)
    }

    private fun buildViewModel(personRef: String): PersonDetailViewModel = PersonDetailViewModel(
        personEnrichmentRepository = personEnrichmentRepository,
        rawIngestionRepository = rawIngestionRepository,
        commitmentRepository = commitmentRepository,
        calendarEventRepository = calendarEventRepository,
        userPrefsStore = userPrefsStore,
        savedStateHandle = SavedStateHandle(mapOf(ARG_PERSON_REF to personRef)),
        logger = logger,
    )

    private fun rawEvent(
        id: String,
        sourceType: String,
        timestamp: Instant,
        snippet: String,
    ): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = sourceType,
        personRef = "alice@example.com",
        eventTitle = id,
        eventSnippet = snippet,
        commitmentsExtractedCount = 2,
        timestamp = timestamp,
    )

    private fun commitment(
        id: String,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        actionState: String,
        scheduleStatus: String? = null,
        decisionStatus: String? = null,
        timestamp: Long,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        decisionStatus = decisionStatus,
        counterpartyRaw = "alice@example.com",
        personRef = "alice@example.com",
        title = id,
        description = null,
        quote = "quote",
        sourceEventTitle = "Call",
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(timestamp),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "gmail",
        sourceRef = null,
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
        lastEditedBy = null,
        lastEditedAt = null,
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = null,
    )

    private fun calendarEvent(
        id: String,
        timestamp: Long,
    ): CalendarEventEntity = CalendarEventEntity(
        id = id,
        userId = "user-1",
        sourceType = "google_calendar",
        sourceRef = id,
        title = id,
        startAt = Instant.fromEpochMilliseconds(timestamp),
        endAt = Instant.fromEpochMilliseconds(timestamp + 1),
        attendeesRaw = "alice@example.com",
        syncStatus = "synced",
    )
}
