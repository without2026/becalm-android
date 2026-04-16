package com.becalm.android.ui.today

import app.cash.turbine.test
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.commitment.CommitmentState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val commitmentRepository: CommitmentRepository = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val authRepository: AuthRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private val fakeSession = SupabaseSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        userId = "user-123",
        email = "test@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    private val fakeSourceStatuses = SourceType.ALL.map { sourceType ->
        SourceStatus(
            sourceType = sourceType,
            status = SourceConnectionStatus.CONNECTED,
            lastSyncedAt = Clock.System.now(),
            errorMessage = null,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sourceStatusRepository.observeAll() } returns flowOf(fakeSourceStatuses)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── TDY-VM-01: empty state when no commitments or calendar events ──────────

    @Test
    fun `state emits empty timeline when repositories return no data`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            authRepository = authRepository,
            logger = logger,
        )

        viewModel.state.test {
            // Advance until loading=false
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            assertTrue("Timeline should be empty", emission.timeline.isEmpty())
            assertNull("No error expected", emission.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-02: timeline is sorted by time ────────────────────────────────

    @Test
    fun `state emits timeline sorted by time ascending`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession

        val earlier = Instant.fromEpochMilliseconds(1_000_000L)
        val later = Instant.fromEpochMilliseconds(2_000_000L)

        val commitment = buildCommitment(id = "c1", occurredAt = later)
        val calEvent = buildCalendarEvent(id = "e1", startAt = earlier)

        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(listOf(commitment))
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(listOf(calEvent))

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            authRepository = authRepository,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            assertEquals("Timeline should have 2 items", 2, emission.timeline.size)
            // Calendar event (earlier) should come first
            assertTrue(
                "First item must be CalendarEvent",
                emission.timeline[0] is TimelineItem.CalendarEvent,
            )
            assertTrue(
                "Second item must be Commitment",
                emission.timeline[1] is TimelineItem.Commitment,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-03: unauthenticated state ────────────────────────────────────

    @Test
    fun `state emits error when currentSession returns null`() = runTest {
        coEvery { authRepository.currentSession() } returns null
        // These should not be called, but stub defensively so the ViewModel doesn't crash.
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            authRepository = authRepository,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            assertNotNull("Error should be set when unauthenticated", emission.error)
            assertEquals("not authenticated", emission.error)
            assertTrue("Timeline should be empty on auth error", emission.timeline.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildCommitment(
        id: String,
        occurredAt: Instant,
    ) = CommitmentEntity(
        id = id,
        userId = "user-123",
        direction = "give",
        counterpartyRaw = null,
        personRef = null,
        title = "Do something",
        description = null,
        quote = "I'll do something",
        sourceEventTitle = null,
        sourceEventOccurredAt = occurredAt,
        dueDate = LocalDate(2026, 4, 16),
        actionState = "pending",
        sourceType = SourceType.VOICE,
        sourceRef = null,
        confidence = 0.9,
        commitmentState = CommitmentState.CONFIRMED,
        syncStatus = "synced",
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun buildCalendarEvent(
        id: String,
        startAt: Instant,
        attendeesRaw: String? = null,
    ) = CalendarEventEntity(
        id = id,
        userId = "user-123",
        sourceType = SourceType.GOOGLE_CALENDAR,
        sourceRef = id,
        title = "Team Meeting",
        startAt = startAt,
        endAt = Instant.fromEpochMilliseconds(startAt.toEpochMilliseconds() + 3_600_000L),
        attendeesRaw = attendeesRaw,
        syncStatus = "synced",
    )
}
