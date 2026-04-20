package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ForegroundCatchUpScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
 * @param lastSyncedAt Wall-clock instant of the most-recently completed successful sync,
 *                     or `null` when no sync has ever finished successfully. Drives the
 *                     TDY-003 synced chip HH:mm label.
 */
public data class SourceStatusUi(
    val syncing: Boolean,
    val statusLabel: String,
    val errorMessage: String?,
    val lastSyncedAt: Instant?,
)

/**
 * Full UI state for the Today screen.
 *
 * @param loading True during initial load before any data has been emitted.
 * @param timeline Merged, time-sorted list of commitments and calendar items for today.
 * @param sourceStatus Per-source-type sync health snapshot keyed by [SourceType] string.
 * @param overallSyncing True when at least one source is actively syncing.
 *                       Kept for backwards compatibility with existing consumers
 *                       (top-bar spinner in [com.becalm.android.ui.today.TodayTimelineScreen]).
 * @param overall  Aggregate sync state driving the TDY-008 banner.
 * @param refreshing True while a user-initiated pull-to-refresh (TDY-006) is in flight.
 * @param error Non-null when an unrecoverable error has occurred (e.g. not authenticated).
 */
// spec: TDY-008 — aggregate sync status
public data class TodayUiState(
    val loading: Boolean = true,
    val timeline: List<TimelineItem> = emptyList(),
    val sourceStatus: Map<String, SourceStatusUi> = emptyMap(),
    val overallSyncing: Boolean = false,
    val overall: OverallSyncState = OverallSyncState.Idle,
    val refreshing: Boolean = false,
    val error: String? = null,
)

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "TodayViewModel"
private const val COUNTERPARTY_DISPLAY_MAX = 30

/**
 * ViewModel for the Today screen (TDY-001..010).
 *
 * Combines commitments pending today, calendar events starting today, per-source
 * sync health, and the on-device person-enrichment map (PIPA Room-only) into a
 * single [TodayUiState] flow. If the user is not authenticated the state immediately
 * shows an error and no downstream repository flows are subscribed.
 *
 * Architecture:
 * 1. A [userIdFlow] resolves the session once and emits either a userId string or null.
 * 2. [commitmentFlow] and [calendarFlow] flatMapLatest on [userIdFlow], emitting empty
 *    lists when userId is null so the combine always has active flows.
 * 3. Six upstream flows are combined into [state]: [userIdFlow], [commitmentFlow],
 *    [calendarFlow], [SourceStatusRepository.observeAll],
 *    [PersonEnrichmentRepository.observeEnrichmentMap], and the [refreshingFlow]
 *    side-channel.
 *
 * Enrichment resolution for commitment counterparty display (TDY-001):
 * 1. `enrichment[personRef]?.displayName` (ContactsContract DISPLAY_NAME), then
 * 2. `enrichment[personRef]?.nickname`, then
 * 3. `personRef` itself (already canonicalized — phone E.164 / email / display name), then
 * 4. `counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)` for legacy rows with no personRef.
 */
@HiltViewModel
public class TodayViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val personEnrichmentRepository: PersonEnrichmentRepository,
    private val authRepository: AuthRepository,
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler,
    private val clock: Clock,
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

    /** Drives the [PullRefreshIndicator] while [onPullRefresh] is in flight (TDY-006). */
    private val refreshingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * Observable state consumed by the Today screen composable.
     *
     * Transitions from [TodayUiState.loading]=true to the first real emission as soon
     * as all upstream flows produce their first values. When userId is null the
     * combined emission sets [TodayUiState.error].
     */
    public val state: StateFlow<TodayUiState> = combine(
        userIdFlow,
        commitmentFlow,
        calendarFlow,
        sourceStatusRepository.observeAll(),
        personEnrichmentRepository.observeEnrichmentMap(),
        refreshingFlow,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val userId = values[0] as String?
        @Suppress("UNCHECKED_CAST")
        val commitments = values[1] as List<CommitmentEntity>
        @Suppress("UNCHECKED_CAST")
        val calendarEvents = values[2] as List<CalendarEventEntity>
        @Suppress("UNCHECKED_CAST")
        val sourceStatuses = values[3] as List<SourceStatus>
        @Suppress("UNCHECKED_CAST")
        val enrichment = values[4] as Map<String, PersonEnrichmentEntity>
        val refreshing = values[5] as Boolean

        if (userId == null) {
            return@combine TodayUiState(
                loading = false,
                refreshing = refreshing,
                error = "not authenticated",
            )
        }
        val timeline = buildTimeline(commitments, calendarEvents, enrichment)
        val statusMap = sourceStatuses.associate { s ->
            s.sourceType to SourceStatusUi(
                syncing = s.status == SourceConnectionStatus.SYNCING,
                statusLabel = s.status.name,
                errorMessage = s.errorMessage,
                lastSyncedAt = s.lastSyncedAt,
            )
        }
        TodayUiState(
            loading = false,
            timeline = timeline,
            sourceStatus = statusMap,
            overallSyncing = sourceStatuses.any { it.status == SourceConnectionStatus.SYNCING },
            overall = deriveOverallState(sourceStatuses),
            refreshing = refreshing,
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

    // ─── Public intents ──────────────────────────────────────────────────────

    /**
     * Pull-to-refresh handler (TDY-006 / TDY-009).
     *
     * Per TDY-009 the pull gesture both:
     * 1. Refreshes Room-backed state from Railway for all three repositories so the
     *    timeline reflects the server truth once the round-trip completes.
     * 2. Fires [ForegroundCatchUpScheduler.triggerCatchUp] to enqueue the ING-011
     *    parallel 6-source catch-up + SYNC-006 immediate upload pass without waiting
     *    for the next foreground transition.
     *
     * The timeline itself is driven by Room (offline-first), so the user sees cached data
     * immediately while the server refresh and catch-up run in the background. Failures do
     * not clobber local state.
     */
    public fun onPullRefresh() {
        // TDY-009: tap-driven catch-up on the strip is explicitly prohibited ("칩 탭
        // 인터랙션 없음") — this pull gesture is the single user-facing trigger.
        foregroundCatchUpScheduler.triggerCatchUp()
        viewModelScope.launch {
            val userId = authRepository.currentSession()?.userId
            refreshingFlow.value = true
            try {
                sourceStatusRepository.refreshFromServer()
                if (userId != null) {
                    commitmentRepository.refreshSince(userId = userId, since = null)
                    calendarEventRepository.refreshSince(userId = userId, since = null)
                }
            } finally {
                refreshingFlow.value = false
            }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun buildTimeline(
        commitments: List<CommitmentEntity>,
        calendarEvents: List<CalendarEventEntity>,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): List<TimelineItem> =
        (commitments.map { it.toTimelineItem(enrichment) } + calendarEvents.map { it.toTimelineItem() })
            .sortedBy { it.sortKey }

    /** Projection of [CommitmentEntity] into the Today timeline. Field mapping is display-safe. */
    private fun CommitmentEntity.toTimelineItem(
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): TimelineItem.Commitment =
        TimelineItem.Commitment(
            id = id,
            title = title,
            direction = direction,
            counterpartyDisplayName = resolveCounterpartyDisplay(this, enrichment),
            sortKey = sourceEventOccurredAt,
        )

    /**
     * TDY-001 counterparty display resolution. Fallback chain:
     * 1. `enrichment[personRef].displayName`
     * 2. `enrichment[personRef].nickname`
     * 3. `personRef` itself (canonicalized identifier is acceptable as last-mile label)
     * 4. `counterpartyRaw.take(COUNTERPARTY_DISPLAY_MAX)` for legacy rows without personRef
     *
     * Returns `null` only when the commitment has no personRef AND no counterpartyRaw.
     */
    private fun resolveCounterpartyDisplay(
        commitment: CommitmentEntity,
        enrichment: Map<String, PersonEnrichmentEntity>,
    ): String? {
        val ref = commitment.personRef
        return if (ref != null) {
            val hit = enrichment[ref]
            hit?.displayName ?: hit?.nickname ?: ref
        } else {
            commitment.counterpartyRaw?.take(COUNTERPARTY_DISPLAY_MAX)
        }
    }

    /**
     * Projection of [CalendarEventEntity] into the Today timeline. `attendeesRaw` 존재 여부로
     * Meeting / CalendarEvent 를 분기하며, 원본 인라인 분기와 byte-identical 하게 필드를 매핑한다.
     */
    private fun CalendarEventEntity.toTimelineItem(): TimelineItem =
        if (!attendeesRaw.isNullOrBlank()) {
            TimelineItem.Meeting(
                id = id,
                title = title,
                attendeesRaw = attendeesRaw,
                sortKey = startAt,
            )
        } else {
            TimelineItem.CalendarEvent(
                id = id,
                title = title,
                sortKey = startAt,
            )
        }

    private fun todayIso(): String {
        val tz = TimeZone.currentSystemDefault()
        return clock.today(tz).toString()
    }

    private fun todayRange(): Pair<Instant, Instant> {
        val tz = TimeZone.currentSystemDefault()
        val today = clock.today(tz)
        val start = today.atStartOfDayIn(tz)
        val end = today.plus(DatePeriod(days = 1)).atStartOfDayIn(tz)
        return start to end
    }
}
