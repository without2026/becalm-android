package com.becalm.android.ui.today

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.KST
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus

internal data class TodaySnapshot(
    val userId: String?,
    val commitments: List<TodayCommitmentRow>,
    val calendarEvents: List<CalendarEventEntity>,
    val sourceStatuses: List<SourceStatus>,
    val processingPaused: Boolean,
)

internal class TodayScreenStateSource @Inject constructor(
    private val commitmentRepository: CommitmentRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val authRepository: AuthRepository,
    private val userPrefsStore: UserPrefsStore,
    private val clock: Clock,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
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
    ): Flow<TodayUiState> {
        val commitmentFlow = userIdFlow.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest flowOf(emptyList())
            commitmentRepository.observeTimelineForToday(userId, endOfTodayEpochMs())
        }

        val calendarFlow = userIdFlow.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest flowOf(emptyList())
            val (dayStart, dayEnd) = todayRange()
            calendarEventRepository.observeForUser(userId, dayStart, dayEnd)
        }

        val baseSnapshotFlow = combine(
            userIdFlow,
            commitmentFlow,
            calendarFlow,
            sourceStatusRepository.observeAll(),
        ) { userId, commitments, calendarEvents, sourceStatuses ->
            TodaySnapshot(
                userId = userId,
                commitments = commitments,
                calendarEvents = calendarEvents,
                sourceStatuses = sourceStatuses,
                processingPaused = false,
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
                        error = e.message ?: "timeline load failed",
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

    /**
     * KST-anchored today range `[start, end)` expressed as [Instant] bounds.
     */
    fun todayRange(): Pair<Instant, Instant> {
        val today = clock.today(KST)
        val start = today.atStartOfDayIn(KST)
        val end = Instant.fromEpochMilliseconds(
            today.plus(DatePeriod(days = 1)).atStartOfDayIn(KST).toEpochMilliseconds() - 1L,
        )
        return start to end
    }

    private companion object {
        private const val TAG = "TodayViewModel"
    }
}
