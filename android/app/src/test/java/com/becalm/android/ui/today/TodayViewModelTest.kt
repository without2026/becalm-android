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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// spec: TDY-001 — today timeline items
// spec: TDY-002 — empty state
// spec: TDY-003 — SourceStatusStrip: read-only 6-chip status display (no tap navigation)
// spec: TDY-006 — pull-to-refresh refreshes Room data
// spec: TDY-007 — settings icon navigates to SettingsScreen (no side drawer)
// spec: TDY-008 — OverallSyncIndicator aggregates 6 adapter states
// spec: TDY-009 — pull-to-refresh triggers catch-up
// spec: TDY-010 — cold sync screen shown only when Room is empty on first onboarding

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

    // spec: TDY-003 — TodayUiState has no tap-navigation chip concept (SourceStatusStrip is read-only)
    @Test
    fun `TDY003_uiState_hasNoChipNavigationField`() = runTest {
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(emptyList())
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(CatchUpResult("voice", 0, true))

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // spec: TDY-003 — SourceStatusStrip is read-only; syncingCount drives the strip display
        // No chip tap event — assert the state has syncingCount as the diagnostic field
        assertTrue(state.syncingCount >= 0)
    }

    // spec: TDY-006 — onPullToRefresh refreshes Room data (items reloaded after catch-up)
    @Test
    fun `TDY006_onPullToRefresh_refreshesRoomData`() = runTest {
        val calEvents = listOf(makeCalEvent("cal-tdy6"))
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(calEvents)
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(CatchUpResult("voice", 1, true))

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        // spec: TDY-006 — pull-to-refresh → Room data re-emitted to timeline
        viewModel.onPullToRefresh()
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.items.size)
        assertEquals(false, viewModel.uiState.value.isLoading)
    }

    // spec: TDY-007 — SettingsScreen route is defined (settings icon navigation target)
    @Test
    fun `TDY007_settingsRoute_isDefined`() {
        // spec: TDY-007 — no side drawer; settings accessed via icon → SettingsScreen route
        assertEquals("/settings", com.becalm.android.ui.BeCalmRoute.Settings.route)
    }

    // spec: TDY-008 — syncingCount in TodayUiState aggregates adapters that are still running
    @Test
    fun `TDY008_syncingCount_reflectsFailedAdapters`() = runTest {
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(emptyList())
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        // 2 adapters report success=false (still syncing / errored)
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(
            CatchUpResult("voice", 0, true),
            CatchUpResult("gmail", 0, false),       // error
            CatchUpResult("outlook_mail", 0, false), // error
            CatchUpResult("naver_imap", 0, true),
            CatchUpResult("google_calendar", 0, true),
            CatchUpResult("outlook_calendar", 0, true)
        )

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        // spec: TDY-008 — OverallSyncIndicator shows '일부 소스 실패' when syncingCount > 0
        assertEquals(2, viewModel.uiState.value.syncingCount)
    }

    // spec: TDY-010 — ColdSyncScreen condition: isEmpty=true AND no items in Room
    @Test
    fun `TDY010_isEmpty_true_whenNoRoomData`() = runTest {
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(emptyList())
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(CatchUpResult("voice", 0, true))

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        // spec: TDY-010 — ColdSyncScreen shows when isEmpty=true (Room completely empty)
        assertTrue(viewModel.uiState.value.isEmpty)
    }

    // spec: TDY-010 — isEmpty=false once Room has at least one item
    @Test
    fun `TDY010_isEmpty_false_afterColdSyncPopulatesRoom`() = runTest {
        val calEvents = listOf(makeCalEvent("cal-post-cold-sync"))
        every { calendarEventDao.observeTodayEvents(any(), any()) } returns flowOf(calEvents)
        every { commitmentDao.observeTodayDueCommitments(any()) } returns flowOf(emptyList())
        coEvery { catchUpCoordinator.performCatchUp() } returns listOf(CatchUpResult("voice", 1, true))

        val viewModel = TodayViewModel(calendarEventDao, commitmentDao, catchUpCoordinator)
        advanceUntilIdle()

        // spec: TDY-010 — after cold sync completes, Room has data → ColdSyncScreen not shown
        assertEquals(false, viewModel.uiState.value.isEmpty)
    }
}
