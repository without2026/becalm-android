package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * A single entry in the Today timeline, sorted by its logical timestamp.
 */
public sealed class TimelineItem {
    public abstract val sortKey: Instant

    /**
     * A commitment that is pending on today's date.
     *
     * Only display-safe fields are exposed to the UI layer; raw entity fields
     * (quote, body, personRef, etc.) are intentionally excluded to avoid leaking
     * legally sensitive content through the view model boundary.
     */
    public data class Commitment(
        val id: String,
        val title: String,
        val direction: String,
        val counterpartyDisplayName: String?,
        override val sortKey: Instant,
    ) : TimelineItem()

    /**
     * A calendar event that starts today and has no recorded attendees.
     *
     * Display-safe projection — see [Commitment] KDoc for rationale.
     */
    public data class CalendarEvent(
        val id: String,
        val title: String,
        override val sortKey: Instant,
    ) : TimelineItem()

    /**
     * A calendar event that starts today and has at least one attendee.
     *
     * Display-safe projection — see [Commitment] KDoc for rationale.
     */
    public data class Meeting(
        val id: String,
        val title: String,
        val attendeesRaw: String?,
        override val sortKey: Instant,
    ) : TimelineItem()
}

/**
 * Per-source summary projected into UI-layer values.
 *
 * @param syncing True when the source is in [SourceConnectionStatus.SYNCING].
 * @param statusLabel Human-readable status string for display.
 * @param errorMessage Non-null only when the source is in [SourceConnectionStatus.ERROR].
 */
public data class SourceStatusUi(
    val syncing: Boolean,
    val statusLabel: String,
    val errorMessage: String?,
)

/**
 * Full UI state for the Today screen.
 *
 * @param loading True during initial load before any data has been emitted.
 * @param timeline Merged, time-sorted list of commitments and calendar items for today.
 * @param sourceStatus Per-source-type sync health snapshot keyed by [SourceType] string.
 * @param overallSyncing True when at least one source is actively syncing.
 * @param error Non-null when an unrecoverable error has occurred (e.g. not authenticated).
 */
// spec: TDY-008 — aggregate sync status
public data class TodayUiState(
    val loading: Boolean = true,
    val timeline: List<TimelineItem> = emptyList(),
    val sourceStatus: Map<String, SourceStatusUi> = emptyMap(),
    val overallSyncing: Boolean = false,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "TodayViewModel"

/**
 * ViewModel for the Today screen (TDY-001..010).
 *
 * Combines commitments pending today, calendar events starting today, and per-source
 * sync health into a single [TodayUiState] flow. If the user is not authenticated the
 * state immediately shows an error and no downstream repository flows are subscribed.
 *
 * Architecture:
 * 1. A [userIdFlow] resolves the session once and emits either a userId string or null.
 * 2. [commitmentFlow] and [calendarFlow] flatMapLatest on [userIdFlow], emitting empty
 *    lists when userId is null so the combine always has three active flows.
 * 3. The three flows are combined into [state].
 */
@HiltViewModel
public class TodayViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val authRepository: AuthRepository,
    private val logger: Logger,
) : ViewModel() {

    /** Emits the authenticated userId once, or null when no session is present. */
    private val userIdFlow: StateFlow<String?> = flow {
        val userId = authRepository.currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "currentSession() returned null — unauthenticated state")
        }
        emit(userId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    private val commitmentFlow = userIdFlow.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        commitmentRepository.observePendingForToday(userId, todayIso())
    }

    private val calendarFlow = userIdFlow.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        val (dayStart, dayEnd) = todayRange()
        calendarEventRepository.observeForUser(userId, dayStart, dayEnd)
    }

    /**
     * Observable state consumed by the Today screen composable.
     *
     * Transitions from [TodayUiState.loading]=true to the first real emission as soon
     * as all three upstream flows produce their first values. When userId is null the
     * combined emission sets [TodayUiState.error].
     */
    public val state: StateFlow<TodayUiState> = combine(
        userIdFlow,
        commitmentFlow,
        calendarFlow,
        sourceStatusRepository.observeAll(),
    ) { userId, commitments, calendarEvents, sourceStatuses ->
        if (userId == null) {
            return@combine TodayUiState(
                loading = false,
                error = "not authenticated",
            )
        }
        val timeline = buildTimeline(commitments, calendarEvents)
        val statusMap = sourceStatuses.associate { s ->
            s.sourceType to SourceStatusUi(
                syncing = s.status == SourceConnectionStatus.SYNCING,
                statusLabel = s.status.name,
                errorMessage = s.errorMessage,
            )
        }
        TodayUiState(
            loading = false,
            timeline = timeline,
            sourceStatus = statusMap,
            overallSyncing = sourceStatuses.any { it.status == SourceConnectionStatus.SYNCING },
            error = null,
        )
    }.catch { e ->
        logger.w(TAG, "timeline flow failed: ${e.message}")
        emit(
            TodayUiState(
                loading = false,
                error = e.message ?: "timeline load failed",
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(loading = true),
    )

    init {
        logger.d(TAG, "init")
    }

    override fun onCleared() {
        super.onCleared()
        logger.d(TAG, "cleared")
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun buildTimeline(
        commitments: List<CommitmentEntity>,
        calendarEvents: List<CalendarEventEntity>,
    ): List<TimelineItem> {
        val commitmentItems = commitments.map { c ->
            TimelineItem.Commitment(
                id = c.id,
                title = c.title,
                direction = c.direction,
                counterpartyDisplayName = c.counterpartyRaw,
                sortKey = c.sourceEventOccurredAt,
            )
        }
        val calendarItems = calendarEvents.map { event ->
            if (!event.attendeesRaw.isNullOrBlank()) {
                TimelineItem.Meeting(
                    id = event.id,
                    title = event.title,
                    attendeesRaw = event.attendeesRaw,
                    sortKey = event.startAt,
                )
            } else {
                TimelineItem.CalendarEvent(
                    id = event.id,
                    title = event.title,
                    sortKey = event.startAt,
                )
            }
        }
        return (commitmentItems + calendarItems).sortedBy { it.sortKey }
    }

    private fun todayIso(): String {
        val tz = TimeZone.currentSystemDefault()
        return Clock.System.now().toLocalDateTime(tz).date.toString()
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val start = today.atStartOfDayIn(tz)
        val end = today.plus(DatePeriod(days = 1)).atStartOfDayIn(tz)
        return start to end
    }
}
