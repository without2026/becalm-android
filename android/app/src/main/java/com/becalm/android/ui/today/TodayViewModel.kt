package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.NoopUserCorrectionRepository
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.NoopScheduleRowTombstoneRepository
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.ScheduleRowTombstoneRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.domain.schedule.ScheduleRowRef
import com.becalm.android.worker.CalendarRelationRefresh
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.SourceParticipantRefreshScope
import com.becalm.android.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import javax.inject.Inject

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * A single entry in the Today timeline, sorted by its logical timestamp.
 */
public sealed class TimelineItem {
    public abstract val sortKey: Instant
    public abstract val timelineAt: Instant?
    public abstract val isTimed: Boolean

    /** Display title rendered as the row headline by the Today timeline. */
    public abstract val title: String

    /**
     * A trackable commitment item that belongs on today's date.
     *
     * Only display-safe fields are exposed to the UI layer; raw entity fields
     * (quote, body, personRef, etc.) are intentionally excluded to avoid leaking
     * legally sensitive content through the view model boundary.
     */
    public data class Commitment(
        val id: String,
        val itemType: String,
        override val title: String,
        val direction: String?,
        val sourceType: String? = null,
        val scheduleStatus: String?,
        val rowTreatment: TodayCommitmentRowTreatment,
        val counterpartyDisplayName: String?,
        val sourceTitle: String?,
        val quote: String?,
        val dueAt: Instant?,
        val dueIsApproximate: Boolean,
        val dueHint: String?,
        override val sortKey: Instant,
        override val timelineAt: Instant?,
        override val isTimed: Boolean,
    ) : TimelineItem()

    /**
     * A calendar event that starts today and has no recorded attendees.
     *
     * Display-safe projection — see [Commitment] KDoc for rationale.
     */
    public data class CalendarEvent(
        val id: String,
        val sourceType: String,
        val sourceRef: String?,
        override val title: String,
        val relatedSourceTypes: List<String> = emptyList(),
        val location: String? = null,
        val status: String = "confirmed",
        val availability: String? = null,
        val isAllDay: Boolean = false,
        override val sortKey: Instant,
        override val timelineAt: Instant? = if (isAllDay) null else sortKey,
        override val isTimed: Boolean = !isAllDay,
    ) : TimelineItem()

    /**
     * A calendar event that starts today and has at least one attendee.
     *
     * Display-safe projection — see [Commitment] KDoc for rationale.
     */
    public data class Meeting(
        val id: String,
        val sourceType: String,
        val sourceRef: String?,
        override val title: String,
        val attendeesRaw: String?,
        val relatedSourceTypes: List<String> = emptyList(),
        val location: String? = null,
        val status: String = "confirmed",
        val availability: String? = null,
        val isAllDay: Boolean = false,
        override val sortKey: Instant,
        override val timelineAt: Instant? = if (isAllDay) null else sortKey,
        override val isTimed: Boolean = !isAllDay,
    ) : TimelineItem()
}

public enum class TodayCommitmentRowTreatment {
    ACTION,
    SCHEDULE,
}

public enum class ScheduleRangeFilter {
    TODAY,
    THIS_WEEK,
    NEXT_7_DAYS,
    ALL,
}

public data class TodayPersonFocus(
    val displayName: String?,
    val commitmentCount: Int,
)

public data class TodayProcessingStatusUi(
    val activeCount: Int = 0,
    val actionCount: Int = 0,
    val activeItemCount: Int = 0,
    val latestPhase: ProcessingPhase? = null,
    val latestUpdatedAt: Instant? = null,
    val dismissKey: String? = null,
    val dismissed: Boolean = false,
) {
    val visible: Boolean
        get() = !dismissed && (activeCount > 0 || actionCount > 0)

    val activeWorkCount: Int
        get() = activeItemCount.takeIf { it > 0 } ?: activeCount
}

public data class ScheduleConflictReviewItem(
    val linkId: String,
    val calendarTitle: String,
    val calendarStartAt: Instant?,
    val calendarStatus: String?,
    val sourceTitle: String,
    val sourceStartAt: Instant?,
    val sourceStatus: String?,
    val sourceType: String,
    val evidence: String?,
)

public fun buildTodayPersonFocus(timeline: List<TimelineItem>): List<TodayPersonFocus> {
    val countsByName = linkedMapOf<String?, Int>()
    timeline.forEach { item ->
        if (item is TimelineItem.Commitment) {
            val name = item.counterpartyDisplayName?.takeIf { it.isNotBlank() }
            if (name == null && item.itemType == CommitmentItemType.SCHEDULE) {
                return@forEach
            }
            countsByName[name] = countsByName.getOrDefault(name, 0) + 1
        }
    }
    return countsByName
        .map { (displayName, count) ->
            TodayPersonFocus(
                displayName = displayName,
                commitmentCount = count,
            )
        }
        .sortedWith(
            compareByDescending<TodayPersonFocus> { it.commitmentCount }
                .thenBy { it.displayName ?: "~" },
        )
}

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
 * @param message One-shot user feedback for refresh completion/failure.
 * @param error Non-null when an unrecoverable error has occurred.
 */
// spec: TDY-008 — aggregate sync status
public data class TodayUiState(
    val loading: Boolean = true,
    val timeline: List<TimelineItem> = emptyList(),
    val personFocus: List<TodayPersonFocus> = buildTodayPersonFocus(timeline),
    val scheduleRangeFilter: ScheduleRangeFilter = ScheduleRangeFilter.NEXT_7_DAYS,
    val today: LocalDate? = null,
    val scheduleActions: List<PersonActionItemUi> = emptyList(),
    val sourceStatus: Map<String, SourceStatusUi> = emptyMap(),
    val overallSyncing: Boolean = false,
    val overall: OverallSyncState = OverallSyncState.Idle,
    val processingStatus: TodayProcessingStatusUi = TodayProcessingStatusUi(),
    val scheduleConflictReviewItems: List<ScheduleConflictReviewItem> = emptyList(),
    val processingPaused: Boolean = false,
    val deletingRows: Set<ScheduleRowRef> = emptySet(),
    val refreshing: Boolean = false,
    val message: UiMessage? = null,
    val error: UiMessage? = null,
)

/** One-shot effects emitted by [TodayViewModel]. */
public sealed interface TodayEffect {
    public data object NavigateToSettings : TodayEffect
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

private const val TAG = "TodayViewModel"

/**
 * ViewModel for the Today screen (TDY-001..010).
 *
 * Combines action/schedule commitment projection rows due by today, calendar events starting today,
 * per-source sync health, and processing pause state into a
 * single [TodayUiState] flow. If no signed-in user is available, the state immediately
 * shows an error and no downstream repository flows are subscribed.
 *
 * Architecture:
 * 1. A [userIdFlow] resolves the session once and emits either a userId string or null.
 * 2. [commitmentFlow] and [calendarFlow] flatMapLatest on [userIdFlow], emitting empty
 *    lists when userId is null so the combine always has active flows.
 * 3. Upstream flows are combined into [state]: [userIdFlow], [commitmentFlow],
 *    [calendarFlow], [SourceStatusRepository.observeAll], processing pause state, and
 *    the [refreshingFlow] side-channel.
 *
 * Commitment counterparty display is resolved in SQL so the home screen does not load the full
 * PIPA enrichment map just to render Today rows.
 */
@HiltViewModel
public class TodayViewModel @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val scheduleRowTombstoneRepository: ScheduleRowTombstoneRepository = NoopScheduleRowTombstoneRepository,
    private val sourceEventParticipantRepository: SourceEventParticipantRepository,
    private val commitmentParticipantRepository: CommitmentParticipantRepository,
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository,
    private val userCorrectionRepository: UserCorrectionRepository = NoopUserCorrectionRepository,
    private val personActionRepository: PersonActionRepository = NoopPersonActionRepository,
    private val workScheduler: WorkScheduler,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val authRepository: AuthRepository,
    userPrefsStore: UserPrefsStore,
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler,
    clock: Clock,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    private val _effects: MutableSharedFlow<TodayEffect> = MutableSharedFlow(extraBufferCapacity = 1)

    /** One-shot navigation stream for settings entry. */
    public val effects: SharedFlow<TodayEffect> = _effects.asSharedFlow()

    private val stateSource = TodayScreenStateSource(
        commitmentRepository = commitmentRepository,
        calendarEventRepository = calendarEventRepository,
        scheduleEventLinkRepository = scheduleEventLinkRepository,
        personActionRepository = personActionRepository,
        sourceStatusRepository = sourceStatusRepository,
        processingStatusRepository = processingStatusRepository,
        authRepository = authRepository,
        userPrefsStore = userPrefsStore,
        clock = clock,
        logger = logger,
        ioDispatcher = ioDispatcher,
    )
    /** Emits the authenticated userId once, or null when no session is present. */
    private val userIdFlow: StateFlow<String?> = stateSource.userIdFlow(viewModelScope)

    /** Drives the [PullRefreshIndicator] while [onPullRefresh] is in flight (TDY-006). */
    private val refreshingFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val refreshMessageFlow: MutableStateFlow<UiMessage?> = MutableStateFlow(null)
    private val scheduleRangeFilterFlow: MutableStateFlow<ScheduleRangeFilter> =
        MutableStateFlow(ScheduleRangeFilter.ALL)
    private val dismissedProcessingKeyFlow: MutableStateFlow<String?> = MutableStateFlow(null)
    private val deletingRowsFlow: MutableStateFlow<Set<ScheduleRowRef>> = MutableStateFlow(emptySet())

    private val baseState: StateFlow<TodayUiState> = stateSource.observeUiState(
        userIdFlow = userIdFlow,
        refreshingFlow = refreshingFlow,
        scheduleRangeFilterFlow = scheduleRangeFilterFlow,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(loading = true),
    )

    /**
     * Observable state consumed by the Today screen composable.
     *
     * Transitions from [TodayUiState.loading]=true to the first real emission as soon
     * as all upstream flows produce their first values. When userId is null the
     * combined emission sets [TodayUiState.error].
     */
    public val state: StateFlow<TodayUiState> = combine(
        baseState,
        refreshMessageFlow,
        dismissedProcessingKeyFlow,
        deletingRowsFlow,
    ) { state, message, dismissedKey, deletingRows ->
        val processingStatus = state.processingStatus
        state.copy(
            processingStatus = if (
                processingStatus.dismissKey != null &&
                processingStatus.dismissKey == dismissedKey
            ) {
                processingStatus.copy(dismissed = true)
            } else {
                processingStatus
            },
            message = message,
            deletingRows = deletingRows,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(loading = true),
    )

    public fun onMessageShown() {
        refreshMessageFlow.value = null
    }

    public fun onScheduleRangeChange(filter: ScheduleRangeFilter) {
        scheduleRangeFilterFlow.value = filter
    }

    public fun onDismissProcessingStatus() {
        dismissedProcessingKeyFlow.value = baseState.value.processingStatus.dismissKey
    }

    public fun onDeleteScheduleRow(rowRef: ScheduleRowRef) {
        if (rowRef in deletingRowsFlow.value) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userIdFlow.value ?: authRepository.currentSession()?.userId
            if (userId == null) {
                refreshMessageFlow.value = UiMessage.resource(R.string.today_error_sign_in_required)
                return@launch
            }
            deletingRowsFlow.value = deletingRowsFlow.value + rowRef
            refreshMessageFlow.value = null
            try {
                val result = userCorrectionRepository.submitScheduleHide(userId, rowRef)
                when (result) {
                    is BecalmResult.Success -> {
                        refreshMessageFlow.value = UiMessage.resource(R.string.schedule_row_delete_success)
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "schedule row delete failed: ${result.error}")
                        refreshMessageFlow.value = UiMessage.resource(R.string.schedule_row_delete_failed)
                    }
                }
            } finally {
                deletingRowsFlow.value = deletingRowsFlow.value - rowRef
            }
        }
    }

    init {
        logger.d(TAG, "init")
        viewModelScope.launch {
            var hadActiveWork = false
            baseState
                .map { state -> state.processingStatus.activeWorkCount }
                .distinctUntilChanged()
                .collect { activeWorkCount ->
                    if (hadActiveWork && activeWorkCount == 0) {
                        refreshMessageFlow.value = UiMessage.resource(R.string.today_processing_completed_message)
                    }
                    hadActiveWork = activeWorkCount > 0
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        logger.d(TAG, "cleared")
    }

    // ─── Public intents ──────────────────────────────────────────────────────

    /**
     * Pull-to-refresh handler (TDY-006 / TDY-009).
     *
     * Per TDY-009 the pull gesture still fires [ForegroundCatchUpScheduler.triggerCatchUp].
     * The direct server mirror pull uses the same relation refresh path as source workers:
     * calendar/commitment mirrors, source participants, commitment participants, then the
     * person-index rebuild that feeds People, Today, and Commitments cards.
     */
    public fun onPullRefresh() {
        if (refreshingFlow.value) return
        // TDY-009: tap-driven catch-up on the strip is explicitly prohibited ("칩 탭
        // 인터랙션 없음") — this pull gesture is the single user-facing trigger.
        viewModelScope.launch(ioDispatcher) {
            foregroundCatchUpScheduler.triggerCatchUp()
            val userId = authRepository.currentSession()?.userId
            refreshingFlow.value = true
            refreshMessageFlow.value = null
            var failed = false
            try {
                when (val result = sourceStatusRepository.refreshFromServer()) {
                    is BecalmResult.Success -> Unit
                    is BecalmResult.Failure -> {
                        failed = true
                        logger.w(TAG, "source status refresh failed: ${result.error}")
                    }
                }
                if (userId != null) {
                    when (val result = personActionRepository.refresh(userId = userId, surface = "schedule")) {
                        is BecalmResult.Success -> Unit
                        is BecalmResult.Failure -> {
                            failed = true
                            logger.w(TAG, "schedule action refresh failed: ${result.error}")
                        }
                    }
                    when (val result = relationRefreshCoordinator().refresh(
                        userId = userId,
                        plan = SourceRelationRefreshPlan(
                            sourceType = PULL_REFRESH_SOURCE,
                            calendarRefresh = CalendarRelationRefresh(),
                            sourceParticipantRefreshScope = SourceParticipantRefreshScope.ALL,
                        ),
                    )) {
                        is BecalmResult.Success -> Unit
                        is BecalmResult.Failure -> {
                            failed = true
                            logger.w(TAG, "relation refresh failed: ${result.error}")
                        }
                    }
                }
                refreshMessageFlow.value = UiMessage.resource(
                    if (failed) R.string.today_refresh_failed else R.string.today_refresh_success,
                )
            } catch (t: Exception) {
                logger.w(TAG, "pull refresh failed: ${t.message}")
                refreshMessageFlow.value = UiMessage.resource(R.string.today_refresh_failed)
            } finally {
                refreshingFlow.value = false
            }
        }
    }

    private fun relationRefreshCoordinator(): SourceRelationRefreshCoordinator =
        SourceRelationRefreshCoordinator(
            calendarEventRepository = calendarEventRepository,
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            scheduleEventLinkRepository = scheduleEventLinkRepository,
            userCorrectionRepository = userCorrectionRepository,
            workScheduler = workScheduler,
            logger = logger,
        )

    /** TDY-007 settings entry from the top-right icon. */
    public fun onOpenSettings() {
        _effects.tryEmit(TodayEffect.NavigateToSettings)
    }

    public fun onResolveScheduleConflict(linkId: String, choice: String) {
        viewModelScope.launch(ioDispatcher) {
            val userId = userIdFlow.value ?: authRepository.currentSession()?.userId
            if (userId == null) {
                refreshMessageFlow.value = UiMessage.resource(R.string.today_error_sign_in_required)
                return@launch
            }
            when (val result = scheduleEventLinkRepository.resolve(userId = userId, id = linkId, choice = choice)) {
                is BecalmResult.Success -> {
                    refreshMessageFlow.value = UiMessage.resource(R.string.today_schedule_conflict_resolved)
                }
                is BecalmResult.Failure -> {
                    logger.w(TAG, "schedule conflict resolve failed: ${result.error}")
                    refreshMessageFlow.value = UiMessage.resource(R.string.today_schedule_conflict_resolve_failed)
                }
            }
        }
    }

    private companion object {
        private const val PULL_REFRESH_SOURCE = "today_pull_refresh"
    }

}

private object NoopPersonActionRepository : PersonActionRepository {
    override fun observeActiveForSurface(
        userId: String,
        surface: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override suspend fun refresh(
        userId: String,
        surface: String?,
    ): BecalmResult<PersonActionRefreshStats> =
        BecalmResult.Success(
            PersonActionRefreshStats(
                fetched = 0,
                deleted = 0,
                serverWatermark = null,
                recomputeState = null,
            ),
        )
}
