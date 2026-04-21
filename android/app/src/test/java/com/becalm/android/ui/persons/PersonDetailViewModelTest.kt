package com.becalm.android.ui.persons

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.domain.commitment.CommitmentState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val commitmentRepository: CommitmentRepository = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk()
    private val userPrefsStore: UserPrefsStore = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private val now: Instant = Clock.System.now()
    private val personRef = "dave@example.com"

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Default: signed in as "user-1".
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): PersonDetailViewModel {
        val handle = SavedStateHandle(mapOf(ARG_PERSON_REF to personRef))
        return PersonDetailViewModel(
            personEnrichmentRepository = personEnrichmentRepository,
            rawIngestionRepository = rawIngestionRepository,
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            userPrefsStore = userPrefsStore,
            savedStateHandle = handle,
            logger = logger,
        )
    }

    private fun makeRawEvent(ref: String = personRef): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = "raw-1",
            userId = "user-1",
            clientEventId = "client-1",
            sourceType = "gmail",
            personRef = ref,
            eventTitle = "Project sync",
            timestamp = now,
        )

    private fun makeCommitment(ref: String = personRef): CommitmentEntity =
        CommitmentEntity(
            id = "cmt-1",
            userId = "user-1",
            direction = "give",
            counterpartyRaw = ref,
            personRef = ref,
            title = "Send report",
            description = null,
            quote = "I will send the report",
            sourceEventTitle = "Project sync",
            sourceEventOccurredAt = now,
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = "gmail",
            sourceRef = null,
            confidence = 0.9,
            commitmentState = CommitmentState.DRAFT,
            createdAt = now,
            updatedAt = now,
        )

    private fun makeCalendarEvent(): CalendarEventEntity =
        CalendarEventEntity(
            id = "cal-1",
            userId = "user-1",
            sourceType = "google_calendar",
            sourceRef = "gcal-ref-1",
            title = "Quarterly review",
            startAt = now,
            endAt = now,
            attendeesRaw = personRef,
        )

    // ─── Test 1: combines all three flows into interactions ───────────────────

    @Test
    fun `userPrefsStore emits userId combines enrichment events commitments and calendar`() =
        runTest(testDispatcher) {
            val enrichment = PersonEnrichmentEntity(personRef = personRef, lastSyncedAt = now)

            every { personEnrichmentRepository.observeByPersonRef(personRef) } returns
                flowOf(enrichment)
            every { rawIngestionRepository.observeForPerson("user-1", personRef, any()) } returns
                flowOf(listOf(makeRawEvent()))
            every { commitmentRepository.observeAllForPerson("user-1", personRef) } returns
                flowOf(listOf(makeCommitment()))
            every { calendarEventRepository.observeForPerson("user-1", personRef, any()) } returns
                flowOf(listOf(makeCalendarEvent()))

            val vm = buildViewModel()

            vm.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()

                assertEquals(personRef, state.personRef)
                assertEquals(enrichment, state.enrichment)
                assertEquals(3, state.interactions.size)
                assertTrue(state.interactions.any { it is InteractionRow.Event })
                assertTrue(state.interactions.any { it is InteractionRow.Commitment })
                assertTrue(state.interactions.any { it is InteractionRow.CalendarMeeting })
                assertNull(state.error)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Test 2: null userId → loading=false, empty interactions ─────────────

    @Test
    fun `userPrefsStore emits null userId emits loading false and empty interactions`() =
        runTest(testDispatcher) {
            every { userPrefsStore.observeCurrentUserId() } returns flowOf(null)

            val vm = buildViewModel()

            vm.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()

                assertEquals(personRef, state.personRef)
                assertNull(state.enrichment)
                assertTrue(state.interactions.isEmpty())
                assertNull(state.error)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Test 3: empty sources → empty interactions ───────────────────────────

    @Test
    fun `empty state when all sources emit empty lists`() = runTest(testDispatcher) {
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns flowOf(null)
        every { rawIngestionRepository.observeForPerson(any(), eq(personRef), any()) } returns
            flowOf(emptyList())
        every { commitmentRepository.observeAllForPerson(any(), eq(personRef)) } returns
            flowOf(emptyList())
        every { calendarEventRepository.observeForPerson(any(), eq(personRef), any()) } returns
            flowOf(emptyList())

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()

            assertEquals(personRef, state.personRef)
            assertNull(state.enrichment)
            assertTrue(state.interactions.isEmpty())
            assertNull(state.error)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Test 4: inner combine flow throws → uiState.error set via .catch ────

    @Test
    fun `inner combine flow emits error sets uiState error via catch`() =
        runTest(testDispatcher) {
            every { personEnrichmentRepository.observeByPersonRef(personRef) } returns
                flow { throw RuntimeException("DB exploded") }
            every { rawIngestionRepository.observeForPerson(any(), any(), any()) } returns
                flowOf(emptyList())
            every { commitmentRepository.observeAllForPerson(any(), any()) } returns
                flowOf(emptyList())
            every { calendarEventRepository.observeForPerson(any(), any(), any()) } returns
                flowOf(emptyList())

            val vm = buildViewModel()

            vm.uiState.test {
                var state = awaitItem()
                while (state.loading) state = awaitItem()

                assertNotNull(state.error)

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ─── Test 5: onErrorDismissed clears error ────────────────────────────────

    @Test
    fun `onErrorDismissed clears error`() = runTest(testDispatcher) {
        every { personEnrichmentRepository.observeByPersonRef(personRef) } returns
            flow { throw RuntimeException("fail") }
        every { rawIngestionRepository.observeForPerson(any(), any(), any()) } returns
            flowOf(emptyList())
        every { commitmentRepository.observeAllForPerson(any(), any()) } returns
            flowOf(emptyList())
        every { calendarEventRepository.observeForPerson(any(), any(), any()) } returns
            flowOf(emptyList())

        val vm = buildViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            assertNotNull(state.error)

            vm.onErrorDismissed()
            val afterDismiss = awaitItem()
            assertNull(afterDismiss.error)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
