package com.becalm.android.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.CalendarWriteJobPrefsSnapshot
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarWriteJobStatus
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
import com.becalm.android.data.repository.SourceOriginalContext
import com.becalm.android.data.repository.SourceOriginalResolver
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.ui.actions.PersonActionEvidenceDetailUi
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.actions.PersonActionFeedStatusUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.toPersonActionEvidenceDetailUi
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

// ─── UI types ─────────────────────────────────────────────────────────────────

/**
 * A single entry in the schedule timeline, sorted by its logical timestamp.
 */
public sealed class TimelineItem {
    public abstract val sortKey: Instant
    public abstract val timelineAt: Instant?
    public abstract val isTimed: Boolean

    /** Display title rendered as the row headline by the schedule timeline. */
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

public enum class CalendarWriteJobStatusKind {
    QUEUED,
    RUNNING,
    RETRY,
    SUCCEEDED,
    FAILED,
    NEEDS_REAUTH,
    CANCELLED,
    CHECK_FAILED,
}

public data class CalendarWriteJobUi(
    val jobId: String,
    val actionItemId: String,
    val title: String,
    val provider: String?,
    val scheduleEventLinkId: String?,
    val status: CalendarWriteJobStatusKind,
    val retryAfterSeconds: Int? = null,
    val attempts: Int = 0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val clientAction: String? = null,
    val checking: Boolean = false,
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
    val scheduleRangeFilter: ScheduleRangeFilter = ScheduleRangeFilter.THIS_WEEK,
    val today: LocalDate? = null,
    val scheduleActions: List<PersonActionItemUi> = emptyList(),
    val scheduleActionFeedStatus: PersonActionFeedStatusUi? = null,
    val evidenceDetail: PersonActionEvidenceDetailUi? = null,
    val loadingEvidenceActionId: String? = null,
    val loadingScheduleActionId: String? = null,
    val loadingScheduleDismissActionId: String? = null,
    val calendarWriteJobs: List<CalendarWriteJobUi> = emptyList(),
    val sourceStatus: Map<String, SourceStatusUi> = emptyMap(),
    val overallSyncing: Boolean = false,
    val overall: OverallSyncState = OverallSyncState.Idle,
    val processingStatus: TodayProcessingStatusUi = TodayProcessingStatusUi(),
    val scheduleConflictReviewItems: List<ScheduleConflictReviewItem> = emptyList(),
    val processingPaused: Boolean = false,
    val deletingRows: Set<ScheduleRowRef> = emptySet(),
    val disabledReminderIds: Set<String> = emptySet(),
    val customReminderIds: Set<String> = emptySet(),
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
private const val SCHEDULE_ACTION_DISMISS_REASON = "not_actionable"

private data class TodayEvidenceState(
    val detail: PersonActionEvidenceDetailUi?,
    val loadingActionId: String?,
    val loadingScheduleActionId: String?,
    val loadingScheduleDismissActionId: String?,
)

private data class TodayScheduleTransientState(
    val deletingRows: Set<ScheduleRowRef>,
    val calendarWriteJobs: List<CalendarWriteJobUi>,
    val disabledReminderIds: Set<String>,
    val customReminderIds: Set<String>,
)

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
    private val rawIngestionEventDao: RawIngestionEventDao? = null,
    private val sourceOriginalResolver: SourceOriginalResolver? = null,
    private val workScheduler: WorkScheduler,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val authRepository: AuthRepository,
    private val userPrefsStore: UserPrefsStore,
    private val reminderScheduler: ReminderScheduler,
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
        MutableStateFlow(ScheduleRangeFilter.THIS_WEEK)
    private val evidenceDetailFlow: MutableStateFlow<PersonActionEvidenceDetailUi?> = MutableStateFlow(null)
    private val loadingEvidenceActionIdFlow: MutableStateFlow<String?> = MutableStateFlow(null)
    private val loadingScheduleActionIdFlow: MutableStateFlow<String?> = MutableStateFlow(null)
    private val loadingScheduleDismissActionIdFlow: MutableStateFlow<String?> = MutableStateFlow(null)
    private val evidenceStateFlow = combine(
        evidenceDetailFlow,
        loadingEvidenceActionIdFlow,
        loadingScheduleActionIdFlow,
        loadingScheduleDismissActionIdFlow,
    ) { detail, loadingActionId, loadingScheduleActionId, loadingScheduleDismissActionId ->
        TodayEvidenceState(
            detail = detail,
            loadingActionId = loadingActionId,
            loadingScheduleActionId = loadingScheduleActionId,
            loadingScheduleDismissActionId = loadingScheduleDismissActionId,
        )
    }
    private val dismissedProcessingKeyFlow: MutableStateFlow<String?> = MutableStateFlow(null)
    private val deletingRowsFlow: MutableStateFlow<Set<ScheduleRowRef>> = MutableStateFlow(emptySet())
    private val calendarWriteJobsFlow: MutableStateFlow<List<CalendarWriteJobUi>> = MutableStateFlow(emptyList())
    private val customScheduleReminderIdsFlow: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private val calendarWritePollingJobs: MutableMap<String, Job> = mutableMapOf()
    private val scheduleTransientFlow = combine(
        deletingRowsFlow,
        calendarWriteJobsFlow,
        userPrefsStore.observeDisabledCommitmentReminderIds(),
        customScheduleReminderIdsFlow,
    ) { deletingRows, calendarWriteJobs, disabledReminderIds, customReminderIds ->
        TodayScheduleTransientState(
            deletingRows = deletingRows,
            calendarWriteJobs = calendarWriteJobs,
            disabledReminderIds = disabledReminderIds,
            customReminderIds = customReminderIds,
        )
    }

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
        evidenceStateFlow,
        dismissedProcessingKeyFlow,
        scheduleTransientFlow,
    ) { state, message, evidenceState, dismissedKey, scheduleTransient ->
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
            evidenceDetail = evidenceState.detail,
            loadingEvidenceActionId = evidenceState.loadingActionId,
            loadingScheduleActionId = evidenceState.loadingScheduleActionId,
            loadingScheduleDismissActionId = evidenceState.loadingScheduleDismissActionId,
            deletingRows = scheduleTransient.deletingRows,
            calendarWriteJobs = scheduleTransient.calendarWriteJobs,
            disabledReminderIds = scheduleTransient.disabledReminderIds,
            customReminderIds = scheduleTransient.customReminderIds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayUiState(loading = true),
    )

    public fun onMessageShown() {
        refreshMessageFlow.value = null
    }

    public fun onOpenScheduleActionEvidence(
        actionItemId: String,
        evidenceKind: String?,
        evidenceId: String?,
    ) {
        if (actionItemId.isBlank()) {
            refreshMessageFlow.value = UiMessage.resource(R.string.commitments_error_evidence_failed)
            return
        }
        viewModelScope.launch(ioDispatcher) {
            val userId = userIdFlow.value ?: authRepository.currentSession()?.userId
            if (userId == null) {
                refreshMessageFlow.value = UiMessage.resource(R.string.today_error_sign_in_required)
                return@launch
            }
            loadingEvidenceActionIdFlow.value = actionItemId
            refreshMessageFlow.value = null
            val action = state.value.scheduleActions.firstOrNull { it.id == actionItemId }
            try {
                val result = if (!evidenceKind.isNullOrBlank() && !evidenceId.isNullOrBlank()) {
                    personActionRepository.fetchEvidenceOriginal(
                        userId = userId,
                        actionItemId = actionItemId,
                        evidenceKind = evidenceKind,
                        evidenceId = evidenceId,
                    )
                } else {
                    null
                }

                if (result is BecalmResult.Success) {
                    evidenceDetailFlow.value = result.value.toPersonActionEvidenceDetailUi()
                    return@launch
                }
                if (result is BecalmResult.Failure) {
                    logger.w(TAG, "schedule action evidence remote lookup failed id=${hashId(actionItemId)}: ${result.error}")
                }

                val fallback = buildScheduleActionEvidenceFallback(userId = userId, action = action)
                if (fallback != null) {
                    evidenceDetailFlow.value = fallback
                } else {
                    refreshMessageFlow.value = UiMessage.resource(R.string.commitments_error_evidence_failed)
                }
            } finally {
                loadingEvidenceActionIdFlow.value = null
            }
        }
    }

    public fun onDismissScheduleActionEvidence() {
        evidenceDetailFlow.value = null
    }

    public fun onCompleteScheduleAction(actionItemId: String) {
        if (
            actionItemId.isBlank() ||
            loadingScheduleActionIdFlow.value == actionItemId ||
            loadingScheduleDismissActionIdFlow.value == actionItemId
        ) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userIdFlow.value ?: authRepository.currentSession()?.userId
            if (userId == null) {
                refreshMessageFlow.value = UiMessage.resource(R.string.today_error_sign_in_required)
                return@launch
            }
            loadingScheduleActionIdFlow.value = actionItemId
            refreshMessageFlow.value = null
            try {
                val action = state.value.scheduleActions.firstOrNull { it.id == actionItemId }
                val providerWrite = action?.providerWrite?.toRequest()
                val result = if (providerWrite != null) {
                    personActionRepository.completeActionWithProviderWrite(
                        userId = userId,
                        actionItemId = actionItemId,
                        providerWrite = providerWrite,
                    )
                } else {
                    personActionRepository.completeAction(userId = userId, actionItemId = actionItemId)
                }
                when (result) {
                    is BecalmResult.Success -> {
                        val jobId = result.value.providerWriteJobId?.takeIf { it.isNotBlank() }
                        if (jobId != null && providerWrite != null) {
                            val calendarAction = checkNotNull(action)
                            upsertCalendarWriteJob(
                                CalendarWriteJobUi(
                                    jobId = jobId,
                                    actionItemId = calendarAction.id,
                                    title = calendarAction.title,
                                    provider = providerWrite.provider,
                                    scheduleEventLinkId = providerWrite.scheduleEventLinkId,
                                    status = CalendarWriteJobStatusKind.QUEUED,
                                    checking = true,
                                ),
                            )
                            startCalendarWriteJobPolling(userId = userId, jobId = jobId)
                        }
                        refreshScheduleActions(userId)
                        refreshMessageFlow.value = UiMessage.resource(
                            if (providerWrite != null) {
                                R.string.schedule_action_add_to_calendar_success
                            } else {
                                R.string.schedule_action_complete_success
                            },
                        )
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "schedule action complete failed id=${hashId(actionItemId)}: ${result.error}")
                        refreshMessageFlow.value = UiMessage.resource(R.string.schedule_action_complete_failed)
                    }
                }
            } finally {
                loadingScheduleActionIdFlow.value = null
            }
        }
    }

    public fun onRetryCalendarWriteJob(jobId: String) {
        if (jobId.isBlank()) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userIdFlow.value ?: authRepository.currentSession()?.userId
            if (userId == null) {
                refreshMessageFlow.value = UiMessage.resource(R.string.today_error_sign_in_required)
                return@launch
            }
            val job = calendarWriteJobsFlow.value.firstOrNull { it.jobId == jobId } ?: return@launch
            upsertCalendarWriteJob(job.copy(checking = true))
            startCalendarWriteJobPolling(userId = userId, jobId = jobId)
        }
    }

    public fun onDismissScheduleAction(actionItemId: String) {
        if (
            actionItemId.isBlank() ||
            loadingScheduleActionIdFlow.value == actionItemId ||
            loadingScheduleDismissActionIdFlow.value == actionItemId
        ) return
        viewModelScope.launch(ioDispatcher) {
            val userId = userIdFlow.value ?: authRepository.currentSession()?.userId
            if (userId == null) {
                refreshMessageFlow.value = UiMessage.resource(R.string.today_error_sign_in_required)
                return@launch
            }
            loadingScheduleDismissActionIdFlow.value = actionItemId
            refreshMessageFlow.value = null
            try {
                when (
                    val result = personActionRepository.dismissAction(
                        userId = userId,
                        actionItemId = actionItemId,
                        reason = SCHEDULE_ACTION_DISMISS_REASON,
                    )
                ) {
                    is BecalmResult.Success -> {
                        refreshScheduleActions(userId)
                        refreshMessageFlow.value = UiMessage.resource(R.string.schedule_action_dismiss_success)
                    }
                    is BecalmResult.Failure -> {
                        logger.w(TAG, "schedule action dismiss failed id=${hashId(actionItemId)}: ${result.error}")
                        refreshMessageFlow.value = UiMessage.resource(R.string.schedule_action_dismiss_failed)
                    }
                }
            } finally {
                loadingScheduleDismissActionIdFlow.value = null
            }
        }
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

    private fun startCalendarWriteJobPolling(userId: String, jobId: String) {
        calendarWritePollingJobs.remove(jobId)?.cancel()
        calendarWritePollingJobs[jobId] = viewModelScope.launch(ioDispatcher) {
            try {
                pollCalendarWriteJob(userId = userId, jobId = jobId)
            } finally {
                calendarWritePollingJobs.remove(jobId)
            }
        }
    }

    private suspend fun pollCalendarWriteJob(userId: String, jobId: String) {
        repeat(CALENDAR_WRITE_JOB_MAX_POLLS) {
            when (val result = personActionRepository.fetchCalendarWriteJobStatus(userId = userId, jobId = jobId)) {
                is BecalmResult.Success -> {
                    val current = calendarWriteJobsFlow.value.firstOrNull { it.jobId == jobId }
                    val next = result.value.toCalendarWriteJobUi(previous = current)
                    val terminal = next.status.isCalendarWriteTerminal()
                    upsertCalendarWriteJob(next.copy(checking = !terminal))
                    if (terminal) {
                        if (next.status == CalendarWriteJobStatusKind.SUCCEEDED) {
                            refreshScheduleActions(userId)
                        }
                        return
                    }
                    delay((next.retryAfterSeconds ?: CALENDAR_WRITE_JOB_DEFAULT_POLL_SECONDS).coerceIn(1, 10).seconds)
                }
                is BecalmResult.Failure -> {
                    val current = calendarWriteJobsFlow.value.firstOrNull { it.jobId == jobId } ?: return
                    logger.w(TAG, "calendar write job poll failed id=${hashId(jobId)}: ${result.error}")
                    upsertCalendarWriteJob(
                        current.copy(
                            status = CalendarWriteJobStatusKind.CHECK_FAILED,
                            errorCode = "calendar_write_status_unavailable",
                            checking = false,
                            clientAction = "retry_later",
                        ),
                    )
                    return
                }
            }
        }
        val current = calendarWriteJobsFlow.value.firstOrNull { it.jobId == jobId } ?: return
        upsertCalendarWriteJob(current.copy(checking = false))
    }

    private fun upsertCalendarWriteJob(job: CalendarWriteJobUi) {
        calendarWriteJobsFlow.value = listOf(job) +
            calendarWriteJobsFlow.value.filterNot { it.jobId == job.jobId }
        persistCalendarWriteJobSnapshots(calendarWriteJobsFlow.value)
    }

    private fun restoreCalendarWriteJobSnapshots() {
        viewModelScope.launch(ioDispatcher) {
            try {
                val (restoredUserId, snapshots) = combine(
                    userIdFlow,
                    userPrefsStore.observeCalendarWriteJobSnapshots(),
                ) { userId, jobs ->
                    userId to jobs
                }.first { (userId, _) -> !userId.isNullOrBlank() }
                val userId = restoredUserId ?: return@launch
                val restoredJobs = snapshots
                    .mapNotNull { it.toCalendarWriteJobUi() }
                    .take(CALENDAR_WRITE_JOB_SNAPSHOT_LIMIT)
                if (restoredJobs.isEmpty() || calendarWriteJobsFlow.value.isNotEmpty()) return@launch
                calendarWriteJobsFlow.value = restoredJobs
                restoredJobs
                    .filter { !it.status.isCalendarWriteTerminal() }
                    .forEach { job ->
                        startCalendarWriteJobPolling(userId = userId, jobId = job.jobId)
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.w(TAG, "calendar write job snapshot restore failed: ${error.message}")
            }
        }
    }

    private fun persistCalendarWriteJobSnapshots(jobs: List<CalendarWriteJobUi>) {
        val snapshots = jobs
            .mapNotNull { it.toCalendarWriteJobPrefsSnapshot() }
            .take(CALENDAR_WRITE_JOB_SNAPSHOT_LIMIT)
        viewModelScope.launch(ioDispatcher) {
            try {
                userPrefsStore.setCalendarWriteJobSnapshots(snapshots)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.w(TAG, "calendar write job snapshot persist failed: ${error.message}")
            }
        }
    }

    private fun CalendarWriteJobPrefsSnapshot.toCalendarWriteJobUi(): CalendarWriteJobUi? {
        if (jobId.isBlank() || actionItemId.isBlank() || status.isBlank()) return null
        val kind = status.toCalendarWriteJobStatusKind()
        return CalendarWriteJobUi(
            jobId = jobId,
            actionItemId = actionItemId,
            title = title.takeIf { it.isNotBlank() } ?: actionItemId,
            provider = provider,
            scheduleEventLinkId = scheduleEventLinkId,
            status = kind,
            retryAfterSeconds = retryAfterSeconds,
            attempts = attempts,
            errorCode = errorCode,
            clientAction = clientAction,
            checking = !kind.isCalendarWriteTerminal(),
        )
    }

    private fun CalendarWriteJobUi.toCalendarWriteJobPrefsSnapshot(): CalendarWriteJobPrefsSnapshot? {
        if (!status.shouldPersistCalendarWriteSnapshot()) return null
        if (jobId.isBlank() || actionItemId.isBlank()) return null
        return CalendarWriteJobPrefsSnapshot(
            jobId = jobId,
            actionItemId = actionItemId,
            title = title,
            provider = provider,
            scheduleEventLinkId = scheduleEventLinkId,
            status = status.toCalendarWriteJobStatusString(),
            retryAfterSeconds = retryAfterSeconds,
            attempts = attempts,
            errorCode = errorCode,
            clientAction = clientAction,
        )
    }

    private fun CalendarWriteJobStatus.toCalendarWriteJobUi(
        previous: CalendarWriteJobUi?,
    ): CalendarWriteJobUi =
        CalendarWriteJobUi(
            jobId = jobId,
            actionItemId = actionItemId?.takeIf { it.isNotBlank() } ?: previous?.actionItemId.orEmpty(),
            title = previous?.title?.takeIf { it.isNotBlank() }
                ?: actionItemId?.takeIf { it.isNotBlank() }
                ?: jobId,
            provider = provider?.takeIf { it.isNotBlank() } ?: previous?.provider,
            scheduleEventLinkId = scheduleEventLinkId?.takeIf { it.isNotBlank() } ?: previous?.scheduleEventLinkId,
            status = status.toCalendarWriteJobStatusKind(),
            retryAfterSeconds = retryAfterSeconds,
            attempts = attempts,
            errorCode = errorCode,
            errorMessage = errorMessage,
            clientAction = clientAction,
        )

    private fun String.toCalendarWriteJobStatusKind(): CalendarWriteJobStatusKind =
        when (lowercase()) {
            "pending",
            "queued",
            -> CalendarWriteJobStatusKind.QUEUED
            "running" -> CalendarWriteJobStatusKind.RUNNING
            "retry" -> CalendarWriteJobStatusKind.RETRY
            "succeeded" -> CalendarWriteJobStatusKind.SUCCEEDED
            "failed" -> CalendarWriteJobStatusKind.FAILED
            "needs_reauth" -> CalendarWriteJobStatusKind.NEEDS_REAUTH
            "cancelled" -> CalendarWriteJobStatusKind.CANCELLED
            else -> CalendarWriteJobStatusKind.CHECK_FAILED
        }

    private fun CalendarWriteJobStatusKind.toCalendarWriteJobStatusString(): String =
        when (this) {
            CalendarWriteJobStatusKind.QUEUED -> "queued"
            CalendarWriteJobStatusKind.RUNNING -> "running"
            CalendarWriteJobStatusKind.RETRY -> "retry"
            CalendarWriteJobStatusKind.SUCCEEDED -> "succeeded"
            CalendarWriteJobStatusKind.FAILED -> "failed"
            CalendarWriteJobStatusKind.NEEDS_REAUTH -> "needs_reauth"
            CalendarWriteJobStatusKind.CANCELLED -> "cancelled"
            CalendarWriteJobStatusKind.CHECK_FAILED -> "check_failed"
        }

    private fun CalendarWriteJobStatusKind.isCalendarWriteTerminal(): Boolean =
        when (this) {
            CalendarWriteJobStatusKind.SUCCEEDED,
            CalendarWriteJobStatusKind.FAILED,
            CalendarWriteJobStatusKind.NEEDS_REAUTH,
            CalendarWriteJobStatusKind.CANCELLED,
            CalendarWriteJobStatusKind.CHECK_FAILED,
            -> true
            CalendarWriteJobStatusKind.QUEUED,
            CalendarWriteJobStatusKind.RUNNING,
            CalendarWriteJobStatusKind.RETRY,
            -> false
        }

    private fun CalendarWriteJobStatusKind.shouldPersistCalendarWriteSnapshot(): Boolean =
        when (this) {
            CalendarWriteJobStatusKind.SUCCEEDED,
            CalendarWriteJobStatusKind.CANCELLED,
            -> false
            CalendarWriteJobStatusKind.QUEUED,
            CalendarWriteJobStatusKind.RUNNING,
            CalendarWriteJobStatusKind.RETRY,
            CalendarWriteJobStatusKind.FAILED,
            CalendarWriteJobStatusKind.NEEDS_REAUTH,
            CalendarWriteJobStatusKind.CHECK_FAILED,
            -> true
        }

    init {
        logger.d(TAG, "init")
        restoreCalendarWriteJobSnapshots()
        viewModelScope.launch(ioDispatcher) {
            var lastRefreshedUserId: String? = null
            userIdFlow.collect { userId ->
                val currentUserId = userId?.takeIf { it.isNotBlank() }
                if (currentUserId != null && currentUserId != lastRefreshedUserId) {
                    lastRefreshedUserId = currentUserId
                    refreshScheduleActions(currentUserId)
                }
                if (currentUserId == null) {
                    lastRefreshedUserId = null
                }
            }
        }
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
                    if (!refreshScheduleActions(userId)) {
                        failed = true
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
            personActionRepository = personActionRepository,
            userCorrectionRepository = userCorrectionRepository,
            workScheduler = workScheduler,
            logger = logger,
        )

    private suspend fun refreshScheduleActions(userId: String): Boolean {
        return when (val result = personActionRepository.refresh(userId = userId, surface = "schedule")) {
            is BecalmResult.Success -> true
            is BecalmResult.Failure -> {
                logger.w(TAG, "schedule action refresh failed: ${result.error}")
                false
            }
        }
    }

    private suspend fun buildScheduleActionEvidenceFallback(
        userId: String,
        action: PersonActionItemUi?,
    ): PersonActionEvidenceDetailUi? {
        if (action == null) return null
        val rawEvent = resolveScheduleActionRawEvent(userId = userId, action = action)
        val original = rawEvent?.let { event ->
            runCatching {
                sourceOriginalResolver?.resolve(userId = userId, event = event)
            }
                .onFailure { logger.w(TAG, "schedule action local original fallback failed id=${hashId(action.id)}", it) }
                .getOrNull()
        }
        val localOriginalText = original?.toDisplayText()
        val metadataText = listOfNotNull(
            action.evidence?.quote?.takeIf { it.isNotBlank() },
            rawEvent?.eventSnippet?.takeIf { it.isNotBlank() },
            action.shortReason.takeIf { it.isNotBlank() },
            rawEvent?.eventTitle?.takeIf { it.isNotBlank() },
        )
            .distinct()
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
        val why = action.evidence?.quote?.takeIf { it.isNotBlank() }
            ?: action.shortReason.takeIf { it.isNotBlank() }
            ?: action.title
        val originalText = localOriginalText ?: metadataText ?: why
        if (originalText.isBlank() && why.isBlank()) return null
        return PersonActionEvidenceDetailUi(
            actionItemId = action.id,
            evidenceLabel = action.evidence?.label?.takeIf { it.isNotBlank() }
                ?: rawEvent?.eventTitle?.takeIf { it.isNotBlank() }
                ?: action.title,
            whyText = why,
            originalTitle = rawEvent?.eventTitle?.takeIf { it.isNotBlank() }
                ?: action.evidence?.label?.takeIf { it.isNotBlank() }
                ?: action.title,
            originalText = originalText,
            metadataText = metadataText,
            originalIsLocal = localOriginalText != null,
            originalTruncated = original?.archivedOriginal?.markdownTruncated == true,
            sourceType = rawEvent?.sourceType ?: action.sourceType,
        )
    }

    private suspend fun resolveScheduleActionRawEvent(
        userId: String,
        action: PersonActionItemUi,
    ): RawIngestionEventEntity? {
        val rawDao = rawIngestionEventDao ?: return null
        val idCandidates = listOfNotNull(
            action.sourceEventId,
            action.evidence?.id?.takeIf { action.evidence.kind == "source_event" },
            action.evidence?.sourceRef?.removePrefix("raw:"),
            action.sourceRef?.removePrefix("raw:"),
        ).distinctNonBlank()
        for (candidate in idCandidates) {
            rawDao.findById(candidate, userId)?.let { return it }
        }
        val sourceRefCandidates = listOfNotNull(
            action.sourceRef,
            action.evidence?.sourceRef,
            action.sourceEventId?.let { "raw:$it" },
        ).distinctNonBlank()
        if (sourceRefCandidates.isEmpty()) return null
        return rawDao.findBySourceRefsForUser(userId = userId, sourceRefs = sourceRefCandidates).firstOrNull()
    }

    private fun SourceOriginalContext.toDisplayText(): String? =
        listOfNotNull(
            emailBody?.bodyPlain?.takeIf { it.isNotBlank() },
            emailBody?.bodyHtml?.takeIf { it.isNotBlank() },
            archivedOriginal?.markdown?.takeIf { it.isNotBlank() },
        ).firstOrNull()

    private fun List<String>.distinctNonBlank(): List<String> =
        map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

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

    public fun onToggleScheduleReminder(commitmentId: String, enabled: Boolean) {
        viewModelScope.launch(ioDispatcher) {
            val dueAt = state.value.timeline
                .filterIsInstance<TimelineItem.Commitment>()
                .firstOrNull { it.id == commitmentId }
                ?.dueAt
            userPrefsStore.setCommitmentReminderDisabled(commitmentId, disabled = !enabled)
            if (enabled) {
                reminderScheduler.schedule(commitmentId, dueAt)
                customScheduleReminderIdsFlow.update { it - commitmentId }
                refreshMessageFlow.value = UiMessage.resource(R.string.commitment_action_reminder_on)
            } else {
                reminderScheduler.cancel(commitmentId)
                customScheduleReminderIdsFlow.update { it - commitmentId }
                refreshMessageFlow.value = UiMessage.resource(R.string.commitment_action_reminder_off)
            }
        }
    }

    public fun onSetScheduleReminderAt(commitmentId: String, triggerAt: Instant) {
        viewModelScope.launch(ioDispatcher) {
            userPrefsStore.setCommitmentReminderDisabled(commitmentId, disabled = false)
            reminderScheduler.scheduleAt(commitmentId = commitmentId, triggerAt = triggerAt)
            customScheduleReminderIdsFlow.update { it + commitmentId }
            refreshMessageFlow.value = UiMessage.resource(R.string.commitment_action_reminder_on)
        }
    }

    private companion object {
        private const val PULL_REFRESH_SOURCE = "today_pull_refresh"
        private const val CALENDAR_WRITE_JOB_MAX_POLLS = 8
        private const val CALENDAR_WRITE_JOB_DEFAULT_POLL_SECONDS = 2
        private const val CALENDAR_WRITE_JOB_SNAPSHOT_LIMIT = 3
    }

    private fun hashId(id: String): String = "%08x".format(id.hashCode())

}

private object NoopPersonActionRepository : PersonActionRepository {
    override fun observeActiveForSurface(
        userId: String,
        surface: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForPerson(
        userId: String,
        personId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForCommitment(
        userId: String,
        commitmentId: String,
        limit: Int,
    ): kotlinx.coroutines.flow.Flow<List<com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity>> =
        flowOf(emptyList())

    override fun observeActiveForCalendarEvent(
        userId: String,
        calendarEventId: String,
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

    override suspend fun completeAction(
        userId: String,
        actionItemId: String,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun dismissAction(
        userId: String,
        actionItemId: String,
        reason: String?,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun snoozeAction(
        userId: String,
        actionItemId: String,
        snoozedUntil: kotlinx.datetime.Instant,
        reason: String?,
        expectedUpdatedAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun submitActionFeedback(
        userId: String,
        actionItemId: String,
        feedbackType: String,
        reason: String?,
        correctedPersonId: String?,
        correctedDueAt: kotlinx.datetime.Instant?,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    override suspend fun syncPendingMutations(
        userId: String,
        limit: Int,
    ): BecalmResult<com.becalm.android.data.repository.PersonActionMutationSyncStats> =
        BecalmResult.Success(noopMutationStats())

    private fun noopMutationStats(): com.becalm.android.data.repository.PersonActionMutationSyncStats =
        com.becalm.android.data.repository.PersonActionMutationSyncStats(queued = 0, synced = 0, retryable = 0, failed = 0)
}
