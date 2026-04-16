package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.dao.CommitmentDao
import com.becalm.android.data.local.entities.CalendarEvent
import com.becalm.android.data.local.entities.Commitment
import com.becalm.android.data.repository.ForegroundCatchUpCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// spec: TDY-001 — today timeline combining calendar events + due commitments
// spec: TDY-002 — empty state handling
// spec: TDY-006, TDY-009 — pull-to-refresh triggers ING-011 + SYNC-006
// spec: TDY-010 — cold sync state check

sealed class TodayItem {
    data class CommitmentItem(val commitment: Commitment) : TodayItem()
    data class CalendarItem(val event: CalendarEvent) : TodayItem()
}

data class TodayUiState(
    val items: List<TodayItem> = emptyList(),
    val isLoading: Boolean = false,
    val syncingCount: Int = 0,
    val lastSyncAt: Long? = null,
    val isEmpty: Boolean = false
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val calendarEventDao: CalendarEventDao,
    private val commitmentDao: CommitmentDao,
    private val catchUpCoordinator: ForegroundCatchUpCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState(isLoading = true))
    val uiState: StateFlow<TodayUiState> = _uiState

    init {
        // spec: TDY-001 — observe today's data from Room on init
        observeTodayData()
        // spec: ING-011 — trigger foreground catch-up on screen entry
        performCatchUp()
    }

    private fun observeTodayData() {
        val (startOfDay, endOfDay) = getTodayBounds()
        val todayDate = getDateString()

        viewModelScope.launch {
            combine(
                calendarEventDao.observeTodayEvents(startOfDay, endOfDay),
                commitmentDao.observeTodayDueCommitments(todayDate)
            ) { calEvents, commitments ->
                val items = buildTimeline(calEvents, commitments)
                // spec: TDY-002 — empty state
                TodayUiState(
                    items = items,
                    isLoading = false,
                    isEmpty = items.isEmpty()
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    // spec: TDY-009 — pull-to-refresh triggers ING-011 parallel catch-up
    fun onPullToRefresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, syncingCount = 6)
        performCatchUp()
    }

    private fun performCatchUp() {
        viewModelScope.launch {
            val results = catchUpCoordinator.performCatchUp()
            val syncingCount = results.count { !it.success }
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                syncingCount = syncingCount,
                lastSyncAt = System.currentTimeMillis()
            )
        }
    }

    // spec: TDY-001 — merge calendar events and commitments sorted by time
    private fun buildTimeline(
        calEvents: List<CalendarEvent>,
        commitments: List<Commitment>
    ): List<TodayItem> {
        val items = mutableListOf<TodayItem>()
        calEvents.forEach { items.add(TodayItem.CalendarItem(it)) }
        commitments.forEach { items.add(TodayItem.CommitmentItem(it)) }
        return items.sortedBy { item ->
            when (item) {
                is TodayItem.CalendarItem -> item.event.startAt
                is TodayItem.CommitmentItem -> item.commitment.sourceEventOccurredAt
            }
        }
    }

    private fun getTodayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val endOfDay = startOfDay + 86400000L
        return Pair(startOfDay, endOfDay)
    }

    private fun getDateString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}
