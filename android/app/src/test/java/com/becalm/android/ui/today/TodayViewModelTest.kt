package com.becalm.android.ui.today

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.entities.CalendarEvent
import com.becalm.android.data.local.entities.Commitment
import com.becalm.android.data.repository.CatchUpResult
import com.becalm.android.data.repository.ForegroundCatchUpCoordinator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: TDY-001 — today timeline items
// spec: TDY-002 — empty state
// spec: TDY-009 — pull-to-refresh triggers catch-up

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val calendarEventDao: CalendarEventDao = mockk()
    private val commitmentDao: CommitmentDao = mockk()
    private val catchUpCoordinator: ForegroundCatchUpCoordinator = mockk()

    private fun makeCalEvent(id: String, startAt: Long = System.currentTimeMillis()) =
        CalendarEvent(id = id, sourceType = "google_calendar", sourceRef = id,
            title = "Meeting", startAt = startAt, endAt = startAt + 3600000)

    private fun makeCommitment(id: String) = Commitment(
        id = id, direction = "give", title = "Test", quote = "verbatim",
        sourceEventOccurredAt = System.currentTimeMillis(), sourceType = "voice"
    )

    // spec: TDY-001 — timeline contains both calendar events and commitments
    @Test
    fun `uiState contains calendar events and commitments`() = runTest {
        val calEvents = listOf(makeCalEvent("cal-1"), makeCalEvent("cal-2"))
        val commitments = listOf(makeCommitment("cmt-1"))

        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(calEvents)
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(commitments)
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(
            CatchUpResult("voice", 0, true)
        )

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(3, state.items.size)
    }

    // spec: TDY-002 — empty state when no items
    @Test
    fun `uiState shows empty when no events or commitments today`() = runTest {
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(emptyList())
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(
            CatchUpResult("voice", 0, true)
        )

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isEmpty)
    }

    // spec: TDY-009 — pull-to-refresh triggers catch-up coordinator
    @Test
    fun `onPullToRefresh triggers catch-up coordinator`() = runTest {
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(emptyList())
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        var catchUpCallCount = 0
        coEvery { catchUpCoordinator.performCatchUp() } answers {
            catchUpCallCount++
            listOf(CatchUpResult("voice", 0, true))
        }

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()
        viewModel.onPullToRefresh()
        advanceUntilIdle()

        // Called once on init + once on pull-to-refresh
        assertEquals(2, catchUpCallCount)
    }
}
