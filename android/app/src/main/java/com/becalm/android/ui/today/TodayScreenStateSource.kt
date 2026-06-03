package com.becalm.android.ui.today

import com.becalm.android.R
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.KST
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.components.UiMessage
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

internal data class TodaySnapshot(
    val userId: String?,
    val commitments: List<TodayCommitmentRow>,
    val calendarEvents: List<CalendarEventEntity>,
    val scheduleActions: List<PersonActionItemCacheEntity>,
    val scheduleLinks: List<ScheduleEventLinkEntity>,
    val sourceStatuses: List<SourceStatus>,
    val processingStates: List<ProcessingSourceState>,
    val processingPaused: Boolean,
    val rangeFilter: ScheduleRangeFilter,
    val today: LocalDate,
    val now: Instant,
)

private data class TodaySourceProcessingSnapshot(
    val sourceStatuses: List<SourceStatus>,
    val processingStates: List<ProcessingSourceState>,
)

private data class TodayRowsSnapshot(
    val scope: ScheduleQueryScope,
    val commitments: List<TodayCommitmentRow>,
    val calendarEvents: List<CalendarEventEntity>,
    val scheduleActions: List<PersonActionItemCacheEntity>,
    val scheduleLinks: List<ScheduleEventLinkEntity>,
)

internal class TodayScreenStateSource @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository? = null,
    private val personActionRepository: PersonActionRepository? = null,
    private val sourceStatusRepository: SourceStatusRepository,
    private val processingStatusRepository: ProcessingStatusRepository? = null,
    private val authRepository: AuthRepository,
    private val userPrefsStore: UserPrefsStore,
    private val clock: Clock,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @IoDispatcher private val todayPollDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    fun userIdFlow(scope: kotlinx.coroutines.CoroutineScope): StateFlow<String?> = flow {
        val userId = authRepository.currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "currentSession() returned null — unauthenticated state")
        }
        emit(userId)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = null,
    )

    fun observeUiState(
        userIdFlow: StateFlow<String?>,
        refreshingFlow: Flow<Boolean>,
        scheduleRangeFilterFlow: Flow<ScheduleRangeFilter> = flowOf(ScheduleRangeFilter.ALL),
    ): Flow<TodayUiState> {
        val userDayFlow = combine(userIdFlow, todayFlow(), scheduleRangeFilterFlow) { userId, today, filter ->
            ScheduleQueryScope(userId = userId, today = today, rangeFilter = filter)
        }
            .distinctUntilChanged()

        val commitmentFlow = userDayFlow.flatMapLatest { scope ->
            val userId = scope.userId ?: return@flatMapLatest flowOf(emptyList())
            val (rangeStart, rangeEnd) = scheduleRange(scope.today, scope.rangeFilter)
            commitmentRepository.observeTimelineForToday(
                userId = userId,
                endOfTodayEpochMs = rangeEnd.toEpochMilliseconds() - 1L,
                startOfTodayEpochMs = rangeStart.toEpochMilliseconds(),
            )
        }

        val calendarFlow = userDayFlow.flatMapLatest { scope ->
            val userId = scope.userId ?: return@flatMapLatest flowOf(emptyList())
            val (rangeStart, rangeEnd) = scheduleRange(scope.today, scope.rangeFilter)
            calendarEventRepository.observeForUser(userId, rangeStart, rangeEnd)
        }

        val scheduleActionFlow = userDayFlow.flatMapLatest { scope ->
            val userId = scope.userId
            val repository = personActionRepository
            if (userId == null || repository == null) {
                flowOf(emptyList())
            } else {
                repository.observeActiveForSurface(
                    userId = userId,
                    surface = "schedule",
                    limit = SCHEDULE_ACTION_LIMIT,
                )
            }
        }

        val scheduleLinkFlow = userDayFlow.flatMapLatest { scope ->
            val userId = scope.userId
            if (userId == null || scheduleEventLinkRepository == null) return@flatMapLatest flowOf(emptyList())
            combine(commitmentFlow, calendarFlow) { commitments, calendarEvents ->
                commitments to calendarEvents
            }.flatMapLatest { (commitments, calendarEvents) ->
                val (rangeStart, rangeEnd) = scheduleRange(scope.today, scope.rangeFilter)
                scheduleEventLinkRepository.observeForTodayRange(
                    userId = userId,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    calendarEventIds = calendarEvents.map { it.id },
                    commitmentIds = commitments.map { it.id },
                )
            }
        }

        val sourceProcessingFlow = combine(
            sourceStatusRepository.observeAll(),
            processingStatusRepository?.observeAll() ?: flowOf(emptyList<ProcessingSourceState>()),
        ) { sourceStatuses, processingStates ->
            TodaySourceProcessingSnapshot(
                sourceStatuses = sourceStatuses,
                processingStates = processingStates,
            )
        }

        val rowsSnapshotFlow = combine(
            userDayFlow,
            commitmentFlow,
            calendarFlow,
            scheduleActionFlow,
            scheduleLinkFlow,
        ) { scope, commitments, calendarEvents, scheduleActions, scheduleLinks ->
            TodayRowsSnapshot(
                scope = scope,
                commitments = commitments,
                calendarEvents = calendarEvents,
                scheduleActions = scheduleActions,
                scheduleLinks = scheduleLinks,
            )
        }

        val baseSnapshotFlow = combine(rowsSnapshotFlow, sourceProcessingFlow) { rows, sourceProcessing ->
            TodaySnapshot(
                userId = rows.scope.userId,
                commitments = rows.commitments,
                calendarEvents = rows.calendarEvents,
                scheduleActions = rows.scheduleActions,
                scheduleLinks = rows.scheduleLinks,
                sourceStatuses = sourceProcessing.sourceStatuses,
                processingStates = sourceProcessing.processingStates,
                processingPaused = false,
                rangeFilter = rows.scope.rangeFilter,
                today = rows.scope.today,
                now = clock.nowInstant(),
            )
        }

        val snapshotFlow = combine(baseSnapshotFlow, userPrefsStore.observeProcessingPaused()) { snapshot, processingPaused ->
            snapshot.copy(processingPaused = processingPaused)
        }

        return combine(snapshotFlow, refreshingFlow) { snapshot, refreshing ->
            TodaySyncProjector.buildUiState(snapshot, refreshing)
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
            .catch { e ->
                logger.w(TAG, "timeline flow failed: ${e.message}")
                emit(
                    TodayUiState(
                        loading = false,
                        error = UiMessage.resource(R.string.today_error_load_failed),
                    ),
                )
            }
    }

    /**
     * Inclusive end-of-today as UTC epoch milliseconds (KST business calendar).
     */
    fun endOfTodayEpochMs(): Long {
        val tomorrowStart = clock.today(KST).plus(DatePeriod(days = 1)).atStartOfDayIn(KST)
        return tomorrowStart.toEpochMilliseconds() - 1L
    }

    fun startOfTodayEpochMs(): Long = clock.today(KST).atStartOfDayIn(KST).toEpochMilliseconds()

    /**
     * KST-anchored today range `[start, end)` expressed as [Instant] bounds.
     */
    fun todayRange(): Pair<Instant, Instant> {
        val today = clock.today(KST)
        return todayRange(today)
    }

    private fun todayRange(today: LocalDate): Pair<Instant, Instant> {
        val start = today.atStartOfDayIn(KST)
        val end = today.plus(DatePeriod(days = 1)).atStartOfDayIn(KST)
        return start to end
    }

    private fun scheduleRange(today: LocalDate, filter: ScheduleRangeFilter): Pair<Instant, Instant> {
        val todayStart = today.atStartOfDayIn(KST)
        val tomorrowStart = today.plus(DatePeriod(days = 1)).atStartOfDayIn(KST)
        val thisWeekEnd = today.plus(DatePeriod(days = daysUntilNextWeek(today))).atStartOfDayIn(KST)
        val nextSevenDaysEnd = today.plus(DatePeriod(days = 7)).atStartOfDayIn(KST)
        return when (filter) {
            ScheduleRangeFilter.TODAY -> todayStart to tomorrowStart
            ScheduleRangeFilter.THIS_WEEK -> todayStart to thisWeekEnd
            ScheduleRangeFilter.NEXT_7_DAYS -> todayStart to nextSevenDaysEnd
            ScheduleRangeFilter.ALL -> EPOCH_START to DISTANT_FUTURE
        }
    }

    private fun daysUntilNextWeek(today: LocalDate): Int = when (today.dayOfWeek) {
        kotlinx.datetime.DayOfWeek.MONDAY -> 7
        kotlinx.datetime.DayOfWeek.TUESDAY -> 6
        kotlinx.datetime.DayOfWeek.WEDNESDAY -> 5
        kotlinx.datetime.DayOfWeek.THURSDAY -> 4
        kotlinx.datetime.DayOfWeek.FRIDAY -> 3
        kotlinx.datetime.DayOfWeek.SATURDAY -> 2
        kotlinx.datetime.DayOfWeek.SUNDAY -> 1
    }

    private fun todayFlow(): Flow<LocalDate> = flow {
        while (currentCoroutineContext().isActive) {
            emit(clock.today(KST))
            withContext(todayPollDispatcher) {
                delay(TODAY_POLL_INTERVAL_MS)
            }
        }
    }.distinctUntilChanged()

    private companion object {
        private const val TAG = "TodayViewModel"
        private const val TODAY_POLL_INTERVAL_MS = 60_000L
        private const val SCHEDULE_ACTION_LIMIT = 50
        private val EPOCH_START: Instant = Instant.fromEpochMilliseconds(0)
        private val DISTANT_FUTURE: Instant = Instant.fromEpochMilliseconds(Long.MAX_VALUE)
    }
}

private data class ScheduleQueryScope(
    val userId: String?,
    val today: LocalDate,
    val rangeFilter: ScheduleRangeFilter,
)
