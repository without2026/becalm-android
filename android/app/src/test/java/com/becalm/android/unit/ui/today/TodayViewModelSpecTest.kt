package com.becalm.android.unit.ui.today

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.CalendarWriteJobPrefsSnapshot
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentAgendaIntent
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarWriteJobStatus
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.ProcessingSourceState
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.PersonActionMutationSyncStats
import com.becalm.android.data.repository.PersonActionProviderWriteRequest
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.actions.PersonActionFeedStatusKind
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.today.ScheduleRangeFilter
import com.becalm.android.ui.today.CalendarWriteJobStatusKind
import com.becalm.android.ui.today.TimelineItem
import com.becalm.android.ui.today.TodayCommitmentRowTreatment
import com.becalm.android.ui.today.TodayEffect
import com.becalm.android.ui.today.TodaySnapshot
import com.becalm.android.ui.today.TodaySyncProjector
import com.becalm.android.ui.today.TodayTimelineProjector
import com.becalm.android.ui.today.TodayViewModel
import com.becalm.android.worker.ForegroundCatchUpScheduler
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val now = Instant.parse("2026-04-18T09:00:00Z")
    private val clock = FakeClock(nowInstant = now)
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val sourceEventParticipantRepository: SourceEventParticipantRepository = mockk(relaxed = true)
    private val commitmentParticipantRepository: CommitmentParticipantRepository = mockk(relaxed = true)
    private val scheduleEventLinkRepository: ScheduleEventLinkRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val processingStatusRepository: ProcessingStatusRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val createdViewModels = mutableListOf<TodayViewModel>()
    private val actionSyncState = MutableStateFlow<PersonActionSyncStateEntity?>(null)

    @Before
    fun setUp() {
        actionSyncState.value = null
        Dispatchers.setMain(testDispatcher)
        every { sourceStatusRepository.observeAll() } returns flowOf(
            listOf(
                SourceStatus("gmail", SourceConnectionStatus.SYNCING, now, null),
                SourceStatus("outlook_mail", SourceConnectionStatus.ERROR, now, "token expired"),
            ),
        )
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { scheduleEventLinkRepository.observeForTodayRange(any(), any(), any(), any(), any()) } returns flowOf(emptyList())
        every { userPrefsStore.observeProcessingPaused() } returns flowOf(false)
        every { userPrefsStore.observeCalendarWriteJobSnapshots() } returns flowOf(emptyList())
        every { processingStatusRepository.observeAll() } returns flowOf(emptyList())
        every { personActionRepository.observeActiveForSurface(any(), any(), any()) } returns flowOf(emptyList())
        every { personActionRepository.observeSyncState(any(), any(), any()) } returns actionSyncState
        coEvery { personActionRepository.refresh(any(), any()) } returns
            BecalmResult.Success(
                PersonActionRefreshStats(
                    fetched = 0,
                    deleted = 0,
                    serverWatermark = null,
                    recomputeState = null,
                ),
            )
        coEvery { sourceEventParticipantRepository.refreshSince(any(), any(), any()) } returns
            BecalmResult.Success(
                SourceEventParticipantRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
        coEvery { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) } returns
            BecalmResult.Success(
                CommitmentParticipantRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )
    }

    @After
    fun tearDown() {
        createdViewModels.forEach(::clearViewModel)
        createdViewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `TDY-001 schedule timeline merges sorted schedule rows and calendar events`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "c1",
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                ),
                commitment(
                    id = "s1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CHANGED,
                    occurredAt = Instant.parse("2026-04-18T01:30:00Z"),
                    counterpartyRef = "lee@corp.com",
                ),
                enrichment = mapOf("lee@corp.com" to "이대리"),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(
            listOf(
                calendarEvent(
                    id = "m1",
                    startAt = Instant.parse("2026-04-18T00:30:00Z"),
                    attendeesRaw = "a@example.com",
                ),
                calendarEvent(
                    id = "e1",
                    startAt = Instant.parse("2026-04-18T02:00:00Z"),
                    attendeesRaw = null,
                ),
            ),
        )
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(3, emission.timeline.size)
            assertTrue(emission.timeline[0] is TimelineItem.Meeting)
            assertTrue(emission.timeline[1] is TimelineItem.Commitment)
            assertTrue(emission.timeline[2] is TimelineItem.CalendarEvent)
            assertEquals(
                "이대리",
                (emission.timeline[1] as TimelineItem.Commitment).counterpartyDisplayName,
            )
            assertEquals(
                CommitmentItemType.SCHEDULE,
                (emission.timeline[1] as TimelineItem.Commitment).itemType,
            )
            assertEquals(
                TodayCommitmentRowTreatment.SCHEDULE,
                (emission.timeline[1] as TimelineItem.Commitment).rowTreatment,
            )
            assertTrue(emission.personFocus.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedule without person stays on timeline but not in person focus`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "schedule-no-person",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:30:00Z"),
                    counterpartyRef = null,
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(1, emission.timeline.size)
            assertTrue(emission.timeline.single() is TimelineItem.Commitment)
            assertEquals(CommitmentItemType.SCHEDULE, (emission.timeline.single() as TimelineItem.Commitment).itemType)
            assertEquals(null, (emission.timeline.single() as TimelineItem.Commitment).counterpartyDisplayName)
            assertTrue(emission.personFocus.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun agenda_timeline_includes_schedule_candidates_and_schedule_coordination_actions() = runTest {
        val timeline = TodayTimelineProjector.buildTimeline(
            commitments = todayRows(
                commitment(
                    id = "tentative-schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.TENTATIVE,
                    occurredAt = Instant.parse("2026-04-18T01:30:00Z"),
                    counterpartyRef = null,
                ),
                commitment(
                    id = "meeting-coordinate",
                    itemType = CommitmentItemType.ACTION,
                    direction = "take",
                    agendaIntent = CommitmentAgendaIntent.SCHEDULE_COORDINATION,
                    scheduleStatus = null,
                    occurredAt = Instant.parse("2026-04-18T02:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                    dueAt = null,
                ),
            ),
            calendarEvents = emptyList(),
        )

        assertEquals(listOf("tentative-schedule"), timeline.map { (it as TimelineItem.Commitment).id })
        assertEquals(TodayCommitmentRowTreatment.SCHEDULE, (timeline[0] as TimelineItem.Commitment).rowTreatment)
    }

    @Test
    fun `calendar event is hidden from today timeline when mirrored as schedule commitment`() {
        val timeline = TodayTimelineProjector.buildTimeline(
            commitments = todayRows(
                commitment(
                    id = "calendar-schedule-1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "event-1",
                ),
            ),
            calendarEvents = listOf(
                calendarEvent(
                    id = "event-1",
                    startAt = Instant.parse("2026-04-18T01:00:00Z"),
                    attendeesRaw = "lee@corp.com",
                ),
            ),
        )

        assertEquals(1, timeline.size)
        assertTrue(timeline.single() is TimelineItem.Commitment)
    }

    @Test
    fun `calendar all day event stays calendar typed and untimed while source schedule remains proposal typed`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "mail-schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T03:00:00Z"),
                    counterpartyRef = null,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "mail-1",
                    dueAt = Instant.parse("2026-04-18T03:00:00Z"),
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(
            listOf(
                calendarEvent(
                    id = "all-day-calendar",
                    startAt = Instant.parse("2026-04-17T15:00:00Z"),
                    attendeesRaw = null,
                    isAllDay = true,
                    location = "Seoul HQ",
                    availability = "free",
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading || emission.timeline.size < 2) emission = awaitItem()

            val sourceSchedule = emission.timeline.filterIsInstance<TimelineItem.Commitment>().single()
            val calendar = emission.timeline.filterIsInstance<TimelineItem.CalendarEvent>().single()
            assertEquals(SourceType.GMAIL, sourceSchedule.sourceType)
            assertFalse(calendar.isTimed)
            assertTrue(calendar.isAllDay)
            assertEquals("Seoul HQ", calendar.location)
            assertEquals("free", calendar.availability)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `conflicting schedule link exposes calendar first review action`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "source-schedule-1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CHANGED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                    sourceType = SourceType.GMAIL,
                    sourceRef = "mail-1",
                    dueAt = Instant.parse("2026-04-18T02:00:00Z"),
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(
            listOf(
                calendarEvent(
                    id = "calendar-1",
                    startAt = Instant.parse("2026-04-18T01:00:00Z"),
                    attendeesRaw = "lee@corp.com",
                ),
            ),
        )
        every { scheduleEventLinkRepository.observeForTodayRange(any(), any(), any(), any(), any()) } returns flowOf(
            listOf(
                scheduleLink(
                    id = "link-1",
                    commitmentId = "source-schedule-1",
                    relationType = "conflicts",
                    status = "needs_review",
                    proposedStartAt = Instant.parse("2026-04-18T02:00:00Z"),
                    proposedTitle = "메일에서 온 변경 일정",
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            val review = emission.scheduleConflictReviewItems.single()
            assertEquals("link-1", review.linkId)
            assertEquals("calendar-calendar-1", review.calendarTitle)
            assertEquals("메일에서 온 변경 일정", review.sourceTitle)
            assertEquals(SourceType.GMAIL, review.sourceType)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `same schedule resolution absorbs source schedule into calendar schedule`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "source-schedule-1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = null,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "mail-1",
                    dueAt = Instant.parse("2026-04-18T02:00:00Z"),
                ),
                commitment(
                    id = "calendar-schedule-1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = null,
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "calendar-1",
                    dueAt = Instant.parse("2026-04-18T01:00:00Z"),
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(
            listOf(calendarEvent(id = "calendar-1", startAt = Instant.parse("2026-04-18T01:00:00Z"), attendeesRaw = null)),
        )
        every { scheduleEventLinkRepository.observeForTodayRange(any(), any(), any(), any(), any()) } returns flowOf(
            listOf(
                scheduleLink(
                    id = "link-1",
                    relationType = "conflicts",
                    status = "approved",
                    resolutionChoice = ScheduleEventLinkResolutionChoice.SAME_SCHEDULE,
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            val commitmentIds = emission.timeline.filterIsInstance<TimelineItem.Commitment>().map { it.id }
            assertEquals(listOf("calendar-schedule-1"), commitmentIds)
            assertTrue(emission.scheduleConflictReviewItems.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedule adjustment resolution keeps source and calendar schedules`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "source-schedule-1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = null,
                    sourceType = SourceType.GMAIL,
                    sourceRef = "mail-1",
                    dueAt = Instant.parse("2026-04-18T02:00:00Z"),
                ),
                commitment(
                    id = "calendar-schedule-1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = null,
                    sourceType = SourceType.GOOGLE_CALENDAR,
                    sourceRef = "calendar-1",
                    dueAt = Instant.parse("2026-04-18T01:00:00Z"),
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(
            listOf(calendarEvent(id = "calendar-1", startAt = Instant.parse("2026-04-18T01:00:00Z"), attendeesRaw = null)),
        )
        every { scheduleEventLinkRepository.observeForTodayRange(any(), any(), any(), any(), any()) } returns flowOf(
            listOf(
                scheduleLink(
                    id = "link-1",
                    relationType = "conflicts",
                    status = "approved",
                    resolutionChoice = ScheduleEventLinkResolutionChoice.SCHEDULE_ADJUSTMENT_NEEDED,
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            val commitmentIds = emission.timeline.filterIsInstance<TimelineItem.Commitment>().map { it.id }.toSet()
            assertEquals(setOf("calendar-schedule-1", "source-schedule-1"), commitmentIds)
            assertTrue(emission.scheduleConflictReviewItems.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedule conflict resolution calls repository with selected choice`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery {
            scheduleEventLinkRepository.resolve("user-1", "link-1", ScheduleEventLinkResolutionChoice.SAME_SCHEDULE)
        } returns BecalmResult.Success(
            scheduleLink(
                id = "link-1",
                relationType = "conflicts",
                status = "approved",
                resolutionChoice = ScheduleEventLinkResolutionChoice.SAME_SCHEDULE,
            ),
        )
        val viewModel = buildViewModel()

        viewModel.onResolveScheduleConflict("link-1", ScheduleEventLinkResolutionChoice.SAME_SCHEDULE)
        advanceUntilIdle()

        coVerify { scheduleEventLinkRepository.resolve("user-1", "link-1", ScheduleEventLinkResolutionChoice.SAME_SCHEDULE) }
    }

    @Test
    fun `schedule timeline places schedules without exact due time after timed items`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "untimed",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T00:30:00Z"),
                    counterpartyRef = "lee@corp.com",
                    dueAt = null,
                ),
                commitment(
                    id = "timed",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T00:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                    dueAt = Instant.parse("2026-04-18T02:00:00Z"),
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(
            listOf(
                calendarEvent(
                    id = "meeting",
                    startAt = Instant.parse("2026-04-18T01:00:00Z"),
                    attendeesRaw = "lee@corp.com",
                ),
            ),
        )
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertTrue(emission.timeline[0] is TimelineItem.Meeting)
            assertEquals("timed", (emission.timeline[1] as TimelineItem.Commitment).id)
            val untimed = emission.timeline[2] as TimelineItem.Commitment
            assertEquals("untimed", untimed.id)
            assertEquals(false, untimed.isTimed)
            assertEquals(null, untimed.timelineAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-002 authenticated empty today state stays crash free and renders no items`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertTrue(emission.timeline.isEmpty())
            assertEquals(null, emission.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-004 schedule commitments stay room-backed and query the upcoming range`() = runTest {
        val commitmentsFlow = MutableStateFlow<List<TodayCommitmentRow>>(emptyList())
        val dayStartEpochMs = slot<Long>()
        val dayEndEpochMs = slot<Long>()
        coEvery { authRepository.currentSession() } returns session()
        every {
            commitmentRepository.observeTimelineForToday(
                userId = "user-1",
                endOfTodayEpochMs = capture(dayEndEpochMs),
                startOfTodayEpochMs = capture(dayStartEpochMs),
            )
        } returns commitmentsFlow
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertTrue(emission.timeline.isEmpty())
            assertEquals(
                Instant.parse("2026-04-17T15:00:00Z").toEpochMilliseconds(),
                dayStartEpochMs.captured,
            )
            assertEquals(
                Instant.parse("2026-04-24T15:00:00Z").toEpochMilliseconds() - 1L,
                dayEndEpochMs.captured,
            )

            commitmentsFlow.value = todayRows(
                commitment(
                    id = "c-kst",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T04:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                ),
            )

            do {
                emission = awaitItem()
            } while (emission.loading || emission.timeline.isEmpty())

            assertEquals(1, emission.timeline.size)
            assertTrue(emission.timeline.single() is TimelineItem.Commitment)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { commitmentRepository.refreshSince(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `TDY-005 calendar stays room-backed and queries the upcoming schedule window`() = runTest {
        val calendarFlow = MutableStateFlow<List<CalendarEventEntity>>(emptyList())
        val todayStart = slot<Instant>()
        val todayEnd = slot<Instant>()
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(
                userId = "user-1",
                fromInstant = capture(todayStart),
                toInstant = capture(todayEnd),
            )
        } returns calendarFlow
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertTrue(emission.timeline.isEmpty())
            assertEquals(Instant.parse("2026-04-17T15:00:00Z"), todayStart.captured)
            assertEquals(
                Instant.parse("2026-04-24T15:00:00Z").toEpochMilliseconds(),
                todayEnd.captured.toEpochMilliseconds(),
            )

            calendarFlow.value = listOf(
                calendarEvent(
                    id = "calendar-kst",
                    startAt = Instant.parse("2026-04-18T05:00:00Z"),
                    attendeesRaw = "lee@corp.com",
                ),
            )

            do {
                emission = awaitItem()
            } while (emission.loading || emission.timeline.isEmpty())

            assertEquals(1, emission.timeline.size)
            assertTrue(emission.timeline.single() is TimelineItem.Meeting)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { calendarEventRepository.refreshSince(any(), any()) }
    }

    @Test
    fun `schedule range defaults to next seven days and can switch to today`() = runTest {
        val startBounds = mutableListOf<Long>()
        val endBounds = mutableListOf<Long>()
        coEvery { authRepository.currentSession() } returns session()
        every {
            commitmentRepository.observeTimelineForToday(
                userId = "user-1",
                endOfTodayEpochMs = any(),
                startOfTodayEpochMs = any(),
            )
        } answers {
            endBounds += secondArg<Long>()
            startBounds += thirdArg<Long>()
            flowOf(emptyList())
        }
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()
            assertEquals(ScheduleRangeFilter.NEXT_7_DAYS, emission.scheduleRangeFilter)
            assertEquals(Instant.parse("2026-04-17T15:00:00Z").toEpochMilliseconds(), startBounds.last())
            assertEquals(
                Instant.parse("2026-04-24T15:00:00Z").toEpochMilliseconds() - 1L,
                endBounds.last(),
            )

            viewModel.onScheduleRangeChange(ScheduleRangeFilter.TODAY)

            do {
                emission = awaitItem()
            } while (emission.scheduleRangeFilter != ScheduleRangeFilter.TODAY)
            assertEquals(Instant.parse("2026-04-17T15:00:00Z").toEpochMilliseconds(), startBounds.last())
            assertEquals(
                Instant.parse("2026-04-18T15:00:00Z").toEpochMilliseconds() - 1L,
                endBounds.last(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non person lifecycle actions are hidden from today but schedules remain visible`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "slack-action",
                    occurredAt = Instant.parse("2026-04-18T04:00:00Z"),
                    counterpartyRef = "slack",
                    sourceEventTitle = "Slack에서 이메일 주소를 확인하세요.",
                    dueAt = null,
                ),
                commitment(
                    id = "asan-schedule",
                    occurredAt = Instant.parse("2026-04-18T05:00:00Z"),
                    counterpartyRef = "startup@asan-nanum.org",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    sourceEventTitle = "[아산 두어스] 2026 아산 두어스 지원서 제출이 완료되었습니다.",
                    dueAt = Instant.parse("2026-04-18T05:00:00Z"),
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading || emission.timeline.isEmpty()) emission = awaitItem()

            assertEquals(listOf("asan-schedule"), emission.timeline.map { (it as TimelineItem.Commitment).id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun schedule_timeline_hides_rows_that_violate_schedule_shape() {
        val timeline = TodayTimelineProjector.buildTimeline(
            commitments = todayRows(
                commitment(
                    id = "valid-schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = null,
                ),
                commitment(
                    id = "action-shaped-schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = "give",
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T02:00:00Z"),
                    counterpartyRef = "lee@corp.com",
                ),
                commitment(
                    id = "unknown-status-schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = null,
                    occurredAt = Instant.parse("2026-04-18T03:00:00Z"),
                    counterpartyRef = null,
                ),
            ),
            calendarEvents = emptyList(),
        )

        assertEquals(listOf("valid-schedule"), timeline.map { (it as TimelineItem.Commitment).id })
    }

    @Test
    fun `TDY unauthenticated state surfaces explicit error and empty timeline`() = runTest {
        coEvery { authRepository.currentSession() } returns null
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertNotNull(emission.error)
            assertTrue(emission.timeline.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY counterparty display falls back to counterpartyRef when enrichment is missing`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observeTimelineForToday(any(), any(), any()) } returns flowOf(
            todayRows(
                commitment(
                    id = "c2",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    counterpartyRef = "raw@example.com",
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            val row = emission.timeline.single() as TimelineItem.Commitment
            assertEquals("raw@example.com", row.counterpartyDisplayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-008 source status map and overall syncing are derived from repository state`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(true, emission.overallSyncing)
            assertEquals(SourceSyncStatus.Syncing, emission.sourceStatus.getValue("gmail").status)
            assertEquals("token expired", emission.sourceStatus.getValue("outlook_mail").errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-008 overall state resolves synced at earliest timestamp when all sources connected`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        every { sourceStatusRepository.observeAll() } returns flowOf(
            listOf(
                SourceStatus("voice", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(9_000), null),
                SourceStatus("gmail", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(3_000), null),
                SourceStatus("outlook_mail", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(5_000), null),
                SourceStatus("naver_imap", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(6_000), null),
                SourceStatus("daum_imap", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(7_000), null),
                SourceStatus("google_calendar", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(4_000), null),
                SourceStatus("outlook_calendar", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(8_000), null),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertTrue(emission.overall is OverallSyncState.Synced)
            assertEquals(
                Instant.fromEpochMilliseconds(3_000),
                (emission.overall as OverallSyncState.Synced).at,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-008 source status keeps voice naver and daum distinct in seven-source aggregate`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        every { sourceStatusRepository.observeAll() } returns flowOf(
            listOf(
                SourceStatus("voice", SourceConnectionStatus.SYNCING, null, null),
                SourceStatus("gmail", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(3_000), null),
                SourceStatus("outlook_mail", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(4_000), null),
                SourceStatus("naver_imap", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(5_000), null),
                SourceStatus("daum_imap", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(6_000), null),
                SourceStatus("google_calendar", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(7_000), null),
                SourceStatus("outlook_calendar", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(8_000), null),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(7, emission.sourceStatus.size)
            assertTrue(emission.sourceStatus.containsKey("voice"))
            assertTrue(emission.sourceStatus.containsKey("naver_imap"))
            assertTrue(emission.sourceStatus.containsKey("daum_imap"))
            assertEquals(OverallSyncState.Syncing(count = 1, total = 7), emission.overall)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-006 authenticated pull refresh fans out to room-backed refreshes and catch-up`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)
        coEvery {
            commitmentRepository.refreshSince(
                userId = "user-1",
                since = null,
                counterpartyRef = null,
                direction = null,
                actionState = null,
            )
        } returns BecalmResult.Success(
            CommitmentRepository.RefreshStats(
                fetched = 0,
                upserted = 0,
                hasMore = false,
                nextCursor = null,
            ),
        )
        coEvery { calendarEventRepository.refreshSince(userId = "user-1", since = null) } returns
            BecalmResult.Success(
                CalendarEventRepository.RefreshStats(
                    fetched = 0,
                    upserted = 0,
                    hasMore = false,
                    nextCursor = null,
                ),
            )

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onPullRefresh()
        advanceUntilIdle()

        verify(exactly = 1) { foregroundCatchUpScheduler.triggerCatchUp() }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) {
            commitmentRepository.refreshSince(
                userId = "user-1",
                since = null,
                counterpartyRef = null,
                direction = null,
                actionState = null,
            )
        }
        coVerify(exactly = 1) { calendarEventRepository.refreshSince(userId = "user-1", since = null) }
        coVerify(exactly = 1) {
            sourceEventParticipantRepository.refreshSince(userId = "user-1", sourceType = null, since = null)
        }
        coVerify(exactly = 1) {
            commitmentParticipantRepository.refreshSince(
                userId = "user-1",
                since = null,
                personId = null,
                commitmentId = null,
            )
        }
        assertEquals(false, viewModel.state.value.refreshing)
    }

    @Test
    fun `TDY-007 onOpenSettings emits settings navigation effect`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.effects.test {
            viewModel.onOpenSettings()

            assertEquals(TodayEffect.NavigateToSettings, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-009 error status wins over syncing in overall state`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        every { sourceStatusRepository.observeAll() } returns flowOf(
            listOf(
                SourceStatus("gmail", SourceConnectionStatus.ERROR, null, "auth expired"),
                SourceStatus("outlook_mail", SourceConnectionStatus.SYNCING, null, null),
                SourceStatus("naver_imap", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000), null),
                SourceStatus("daum_imap", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000), null),
                SourceStatus("google_calendar", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000), null),
                SourceStatus("outlook_calendar", SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000), null),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(OverallSyncState.PartialFailure, emission.overall)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `processing status summarizes active and action-needed source work`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        every { processingStatusRepository.observeAll() } returns flowOf(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.GEMINI,
                    itemCount = 3,
                    updatedAt = now,
                ),
                ProcessingSourceState(
                    sourceType = SourceType.OUTLOOK_MAIL,
                    phase = ProcessingPhase.ERROR,
                    itemCount = 1,
                    updatedAt = Instant.parse("2026-04-18T08:30:00Z"),
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(1, emission.processingStatus.activeCount)
            assertEquals(1, emission.processingStatus.actionCount)
            assertEquals(3, emission.processingStatus.activeItemCount)
            assertEquals(ProcessingPhase.GEMINI, emission.processingStatus.latestPhase)
            assertTrue(emission.processingStatus.visible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `processing status hides stale non-action work from today surface`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        every { processingStatusRepository.observeAll() } returns flowOf(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.GMAIL,
                    phase = ProcessingPhase.GEMINI,
                    itemCount = 3,
                    updatedAt = Instant.parse("2026-04-18T08:20:00Z"),
                ),
                ProcessingSourceState(
                    sourceType = SourceType.MEETING,
                    phase = ProcessingPhase.SYNCED,
                    itemCount = 1,
                    updatedAt = Instant.parse("2026-04-18T08:45:00Z"),
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(0, emission.processingStatus.activeCount)
            assertEquals(0, emission.processingStatus.actionCount)
            assertEquals(0, emission.processingStatus.activeItemCount)
            assertEquals(null, emission.processingStatus.latestPhase)
            assertFalse(emission.processingStatus.visible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `processing status does not show recent terminal work on today surface`() {
        val uiState = TodaySyncProjector.buildUiState(
            snapshot = TodaySnapshot(
                userId = "user-1",
                commitments = emptyList(),
                calendarEvents = emptyList(),
                scheduleActions = emptyList(),
                scheduleActionSyncState = null,
                scheduleLinks = emptyList(),
                sourceStatuses = emptyList(),
                processingStates = listOf(
                ProcessingSourceState(
                    sourceType = SourceType.MEETING,
                    phase = ProcessingPhase.SYNCED,
                    itemCount = 1,
                    updatedAt = Instant.parse("2026-04-18T08:55:00Z"),
                ),
                ),
                processingPaused = false,
                rangeFilter = ScheduleRangeFilter.ALL,
                today = kotlinx.datetime.LocalDate(2026, 4, 18),
                now = now,
            ),
            refreshing = false,
        )

        assertEquals(0, uiState.processingStatus.activeCount)
        assertEquals(0, uiState.processingStatus.actionCount)
        assertEquals(ProcessingPhase.SYNCED, uiState.processingStatus.latestPhase)
        assertFalse(uiState.processingStatus.visible)
    }

    @Test
    fun `processing status keeps stale error visible because user action is needed`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        every { processingStatusRepository.observeAll() } returns flowOf(
            listOf(
                ProcessingSourceState(
                    sourceType = SourceType.OUTLOOK_MAIL,
                    phase = ProcessingPhase.ERROR,
                    itemCount = 0,
                    updatedAt = Instant.parse("2026-04-18T07:00:00Z"),
                ),
            ),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(0, emission.processingStatus.activeCount)
            assertEquals(1, emission.processingStatus.actionCount)
            assertEquals(ProcessingPhase.ERROR, emission.processingStatus.latestPhase)
            assertTrue(emission.processingStatus.visible)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-009 pull refresh always triggers catch-up and skips repository refresh when unauthenticated`() = runTest {
        coEvery { authRepository.currentSession() } returns null
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)

        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onPullRefresh()
        advanceUntilIdle()

        verify(exactly = 1) { foregroundCatchUpScheduler.triggerCatchUp() }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 0) { commitmentRepository.refreshSince(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { calendarEventRepository.refreshSince(any(), any()) }
        coVerify(exactly = 0) { sourceEventParticipantRepository.refreshSince(any(), any(), any()) }
        coVerify(exactly = 0) { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) }
        assertEquals(false, viewModel.state.value.refreshing)
    }

    @Test
    fun `schedule action cache refreshes from backend on first authenticated entry`() = runTest {
        coEvery { authRepository.currentSession() } returns session()

        val viewModel = buildViewModel()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.refresh(userId = "user-1", surface = "schedule")
        }
        assertFalse(viewModel.state.value.refreshing)
    }

    @Test
    fun `P1-GAP-004 schedule action feed quota delay state remains visible`() = runTest {
        actionSyncState.value = actionSyncStateEntity(
            surfaceKey = "schedule",
            recomputeState = "pending",
            capacityState = "quota_degraded",
            backlogLagSeconds = 240,
        )
        coEvery { authRepository.currentSession() } returns session()

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.scheduleActionFeedStatus == null) {
                emission = awaitItem()
            }

            val status = emission.scheduleActionFeedStatus
            assertEquals(PersonActionFeedStatusKind.QUOTA_DELAY, status?.kind)
            assertEquals(240, status?.backlogLagSeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `schedule action completion patches backend action and refreshes schedule feed`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery {
            personActionRepository.completeAction(userId = "user-1", actionItemId = "pa-schedule-1", expectedUpdatedAt = null)
        } returns BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onCompleteScheduleAction("pa-schedule-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.completeAction(userId = "user-1", actionItemId = "pa-schedule-1", expectedUpdatedAt = null)
        }
        coVerify(exactly = 2) {
            personActionRepository.refresh(userId = "user-1", surface = "schedule")
        }
    }

    @Test
    fun `schedule action completion sends provider write when metadata is ready`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { personActionRepository.observeActiveForSurface(any(), any(), any()) } returns flowOf(
            listOf(scheduleActionEntity(providerWriteReady = true)),
        )
        val providerWriteSlot = slot<PersonActionProviderWriteRequest>()
        coEvery {
            personActionRepository.completeActionWithProviderWrite(
                userId = "user-1",
                actionItemId = "pa-schedule-1",
                providerWrite = capture(providerWriteSlot),
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading || emission.scheduleActions.isEmpty()) {
                emission = awaitItem()
            }

            viewModel.onCompleteScheduleAction("pa-schedule-1")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("google_calendar", providerWriteSlot.captured.provider)
        assertEquals("conn-calendar-write", providerWriteSlot.captured.sourceConnectionId)
        assertEquals("schedule-link-1", providerWriteSlot.captured.scheduleEventLinkId)
        coVerify(exactly = 0) {
            personActionRepository.completeAction(userId = "user-1", actionItemId = "pa-schedule-1", expectedUpdatedAt = null)
        }
    }

    @Test
    fun `P1-GAP-005 add to calendar keeps visible job status and polls terminal state`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { personActionRepository.observeActiveForSurface(any(), any(), any()) } returns flowOf(
            listOf(scheduleActionEntity(providerWriteReady = true)),
        )
        coEvery {
            personActionRepository.completeActionWithProviderWrite(
                userId = "user-1",
                actionItemId = "pa-schedule-1",
                providerWrite = any(),
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(
            PersonActionMutationSyncStats(
                queued = 1,
                synced = 1,
                retryable = 0,
                failed = 0,
                providerWriteJobId = "job-1",
            ),
        )
        coEvery {
            personActionRepository.fetchCalendarWriteJobStatus(userId = "user-1", jobId = "job-1")
        } returns BecalmResult.Success(
            CalendarWriteJobStatus(
                jobId = "job-1",
                status = "succeeded",
                accepted = true,
                provider = "google_calendar",
                actionItemId = "pa-schedule-1",
                scheduleEventLinkId = "schedule-link-1",
                sourceConnectionId = "conn-calendar-write",
                attempts = 1,
            ),
        )
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading || emission.scheduleActions.isEmpty()) {
                emission = awaitItem()
            }

            viewModel.onCompleteScheduleAction("pa-schedule-1")
            advanceUntilIdle()

            while (emission.calendarWriteJobs.firstOrNull()?.status != CalendarWriteJobStatusKind.SUCCEEDED) {
                emission = awaitItem()
            }
            val job = emission.calendarWriteJobs.single()
            assertEquals("job-1", job.jobId)
            assertEquals("Jane Kim calendar candidate", job.title)
            assertEquals(false, job.checking)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            personActionRepository.fetchCalendarWriteJobStatus(userId = "user-1", jobId = "job-1")
        }
    }

    @Test
    fun `P1-GAP-005 calendar write poll failure keeps visible retryable job status`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { personActionRepository.observeActiveForSurface(any(), any(), any()) } returns flowOf(
            listOf(scheduleActionEntity(providerWriteReady = true)),
        )
        coEvery {
            personActionRepository.completeActionWithProviderWrite(
                userId = "user-1",
                actionItemId = "pa-schedule-1",
                providerWrite = any(),
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(
            PersonActionMutationSyncStats(
                queued = 1,
                synced = 1,
                retryable = 0,
                failed = 0,
                providerWriteJobId = "job-poll-failed",
            ),
        )
        coEvery {
            personActionRepository.fetchCalendarWriteJobStatus(
                userId = "user-1",
                jobId = "job-poll-failed",
            )
        } returns BecalmResult.Failure(BecalmError.Network(503, "calendar_write_status_unavailable"))
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading || emission.scheduleActions.isEmpty()) {
                emission = awaitItem()
            }

            viewModel.onCompleteScheduleAction("pa-schedule-1")
            advanceUntilIdle()

            while (emission.calendarWriteJobs.firstOrNull()?.status != CalendarWriteJobStatusKind.CHECK_FAILED) {
                emission = awaitItem()
            }
            val job = emission.calendarWriteJobs.single()
            assertEquals("job-poll-failed", job.jobId)
            assertEquals("Jane Kim calendar candidate", job.title)
            assertEquals(CalendarWriteJobStatusKind.CHECK_FAILED, job.status)
            assertEquals(false, job.checking)
            assertEquals("retry_later", job.clientAction)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(atLeast = 1) {
            userPrefsStore.setCalendarWriteJobSnapshots(
                match { jobs ->
                    jobs.any {
                        it.jobId == "job-poll-failed" &&
                            it.actionItemId == "pa-schedule-1" &&
                            it.status == "check_failed" &&
                            it.clientAction == "retry_later"
                    }
                },
            )
        }
    }

    @Test
    fun `P1-GAP-005 restores calendar write retry state from user prefs after process restart`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { userPrefsStore.observeCalendarWriteJobSnapshots() } returns flowOf(
            listOf(
                CalendarWriteJobPrefsSnapshot(
                    jobId = "job-restored",
                    actionItemId = "pa-schedule-1",
                    title = "Jane Kim calendar candidate",
                    provider = "google_calendar",
                    scheduleEventLinkId = "schedule-link-1",
                    status = "check_failed",
                    retryAfterSeconds = null,
                    attempts = 1,
                    errorCode = "calendar_write_status_unavailable",
                    clientAction = "retry_later",
                ),
            ),
        )
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.calendarWriteJobs.isEmpty()) {
                emission = awaitItem()
            }
            val job = emission.calendarWriteJobs.single()
            assertEquals("job-restored", job.jobId)
            assertEquals("pa-schedule-1", job.actionItemId)
            assertEquals("Jane Kim calendar candidate", job.title)
            assertEquals("google_calendar", job.provider)
            assertEquals("schedule-link-1", job.scheduleEventLinkId)
            assertEquals(CalendarWriteJobStatusKind.CHECK_FAILED, job.status)
            assertEquals(false, job.checking)
            assertEquals("calendar_write_status_unavailable", job.errorCode)
            assertEquals("retry_later", job.clientAction)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            personActionRepository.fetchCalendarWriteJobStatus(userId = "user-1", jobId = "job-restored")
        }
    }

    @Test
    fun `P1-GAP-005 restores calendar write reauth state from user prefs after process restart`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { userPrefsStore.observeCalendarWriteJobSnapshots() } returns flowOf(
            listOf(
                CalendarWriteJobPrefsSnapshot(
                    jobId = "job-restored-reauth",
                    actionItemId = "pa-schedule-1",
                    title = "Jane Kim calendar candidate",
                    provider = "google_calendar",
                    scheduleEventLinkId = "schedule-link-1",
                    status = "needs_reauth",
                    retryAfterSeconds = null,
                    attempts = 2,
                    errorCode = "provider_token_expired",
                    clientAction = "reconnect_source",
                ),
            ),
        )
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.calendarWriteJobs.isEmpty()) {
                emission = awaitItem()
            }
            val job = emission.calendarWriteJobs.single()
            assertEquals("job-restored-reauth", job.jobId)
            assertEquals("pa-schedule-1", job.actionItemId)
            assertEquals("Jane Kim calendar candidate", job.title)
            assertEquals("google_calendar", job.provider)
            assertEquals("schedule-link-1", job.scheduleEventLinkId)
            assertEquals(CalendarWriteJobStatusKind.NEEDS_REAUTH, job.status)
            assertEquals(false, job.checking)
            assertEquals("provider_token_expired", job.errorCode)
            assertEquals("reconnect_source", job.clientAction)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) {
            personActionRepository.fetchCalendarWriteJobStatus(userId = "user-1", jobId = "job-restored-reauth")
        }
    }

    @Test
    fun `P1-GAP-005 restored queued calendar write job resumes polling after process restart`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { userPrefsStore.observeCalendarWriteJobSnapshots() } returns flowOf(
            listOf(
                CalendarWriteJobPrefsSnapshot(
                    jobId = "job-restored-active",
                    actionItemId = "pa-schedule-1",
                    title = "Jane Kim calendar candidate",
                    provider = "google_calendar",
                    scheduleEventLinkId = "schedule-link-1",
                    status = "queued",
                    retryAfterSeconds = 1,
                    attempts = 0,
                    errorCode = null,
                    clientAction = null,
                ),
            ),
        )
        coEvery {
            personActionRepository.fetchCalendarWriteJobStatus(
                userId = "user-1",
                jobId = "job-restored-active",
            )
        } returns BecalmResult.Success(
            CalendarWriteJobStatus(
                jobId = "job-restored-active",
                status = "succeeded",
                accepted = true,
                provider = "google_calendar",
                actionItemId = "pa-schedule-1",
                scheduleEventLinkId = "schedule-link-1",
                sourceConnectionId = "conn-calendar-write",
                attempts = 1,
            ),
        )
        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.calendarWriteJobs.firstOrNull()?.status != CalendarWriteJobStatusKind.SUCCEEDED) {
                emission = awaitItem()
            }
            val job = emission.calendarWriteJobs.single()
            assertEquals("job-restored-active", job.jobId)
            assertEquals("pa-schedule-1", job.actionItemId)
            assertEquals("Jane Kim calendar candidate", job.title)
            assertEquals(CalendarWriteJobStatusKind.SUCCEEDED, job.status)
            assertEquals(false, job.checking)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            personActionRepository.fetchCalendarWriteJobStatus(userId = "user-1", jobId = "job-restored-active")
        }
        coVerify(atLeast = 1) {
            userPrefsStore.setCalendarWriteJobSnapshots(match { it.isEmpty() })
        }
    }

    @Test
    fun `schedule action dismissal patches backend action and refreshes schedule feed`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        coEvery {
            personActionRepository.dismissAction(
                userId = "user-1",
                actionItemId = "pa-schedule-1",
                reason = "not_actionable",
                expectedUpdatedAt = null,
            )
        } returns BecalmResult.Success(PersonActionMutationSyncStats(queued = 1, synced = 1, retryable = 0, failed = 0))
        val viewModel = buildViewModel()
        advanceUntilIdle()

        viewModel.onDismissScheduleAction("pa-schedule-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            personActionRepository.dismissAction(
                userId = "user-1",
                actionItemId = "pa-schedule-1",
                reason = "not_actionable",
                expectedUpdatedAt = null,
            )
        }
        coVerify(exactly = 2) {
            personActionRepository.refresh(userId = "user-1", surface = "schedule")
        }
    }

    @Test
    fun `pull refresh failure surfaces a retryable message and stops the spinner`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        coEvery { sourceStatusRepository.refreshFromServer() } returns
            BecalmResult.Failure(BecalmError.Network(503, "unavailable"))

        val viewModel = buildViewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.onPullRefresh()
            runCurrent()

            var emission = awaitItem()
            while (emission.message == null) {
                emission = awaitItem()
            }
            assertEquals(false, emission.refreshing)
            assertEquals(com.becalm.android.R.string.today_refresh_failed, emission.message?.resId)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
    }

    private fun buildViewModel(
        personActionRepository: PersonActionRepository = this.personActionRepository,
    ): TodayViewModel = TodayViewModel(
        commitmentRepository = commitmentRepository,
        calendarEventRepository = calendarEventRepository,
        sourceEventParticipantRepository = sourceEventParticipantRepository,
        commitmentParticipantRepository = commitmentParticipantRepository,
        scheduleEventLinkRepository = scheduleEventLinkRepository,
        personActionRepository = personActionRepository,
        workScheduler = workScheduler,
        sourceStatusRepository = sourceStatusRepository,
        processingStatusRepository = processingStatusRepository,
        authRepository = authRepository,
        userPrefsStore = userPrefsStore,
        foregroundCatchUpScheduler = foregroundCatchUpScheduler,
        clock = clock,
        logger = logger,
    ).also(createdViewModels::add)

    private fun clearViewModel(viewModel: TodayViewModel) {
        runCatching {
            androidx.lifecycle.ViewModel::class.java
                .getDeclaredMethod("clear")
                .apply { isAccessible = true }
                .invoke(viewModel)
        }
    }

    private fun actionSyncStateEntity(
        surfaceKey: String,
        recomputeState: String?,
        capacityState: String?,
        backlogLagSeconds: Int? = null,
    ): PersonActionSyncStateEntity =
        PersonActionSyncStateEntity(
            userId = "user-1",
            surfaceKey = surfaceKey,
            status = "active",
            serverWatermark = now,
            recomputeState = recomputeState,
            capacityState = capacityState,
            capacityBacklogLagSeconds = backlogLagSeconds,
            lastSyncedAt = now,
            updatedAt = now,
        )

    private fun session() = SupabaseSession(
        accessToken = "a",
        refreshToken = "r",
        userId = "user-1",
        email = "test@example.com",
        expiresAt = Instant.parse("2026-04-19T00:00:00Z"),
    )

    private fun enrichment(counterpartyRef: String, displayName: String): PersonEnrichmentEntity = PersonEnrichmentEntity(
        personRef = counterpartyRef,
        displayName = displayName,
        nickname = null,
        company = null,
        title = null,
        sourceContactId = null,
        lastSyncedAt = now,
    )

    private fun commitment(
        id: String,
        occurredAt: Instant,
        counterpartyRef: String?,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        scheduleStatus: String? = null,
        sourceType: String = "voice",
        sourceRef: String? = null,
        sourceEventTitle: String? = null,
        dueAt: Instant? = occurredAt,
        dueIsApproximate: Boolean = false,
        agendaIntent: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        agendaIntent = agendaIntent,
        counterpartyRaw = null,
        counterpartyRef = counterpartyRef,
        title = "title-$id",
        description = null,
        quote = "quote",
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = occurredAt,
        dueAt = dueAt,
        dueHint = null,
        dueIsApproximate = dueIsApproximate,
        actionState = "pending",
        sourceType = sourceType,
        sourceRef = sourceRef,
        confidence = 1.0,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun todayRows(
        vararg commitments: CommitmentEntity,
        enrichment: Map<String, String> = emptyMap(),
    ): List<TodayCommitmentRow> =
        commitments.map { commitment ->
            TodayCommitmentRow(
                id = commitment.id,
                itemType = commitment.itemType,
                title = commitment.title,
                direction = commitment.direction,
                scheduleStatus = commitment.scheduleStatus,
                agendaIntent = commitment.agendaIntent,
                counterpartyDisplayName = commitment.counterpartyRef?.let { ref ->
                    enrichment[ref] ?: ref
                } ?: commitment.counterpartyRaw?.take(30),
                sourceType = commitment.sourceType,
                sourceRef = commitment.sourceRef,
                sourceTitle = commitment.sourceEventTitle,
                quote = commitment.quote,
                dueAt = commitment.dueAt,
                dueIsApproximate = commitment.dueIsApproximate,
                dueHint = commitment.dueHint,
                sortKey = commitment.sourceEventOccurredAt,
            )
        }

    private fun calendarEvent(
        id: String,
        startAt: Instant,
        attendeesRaw: String?,
        isAllDay: Boolean = false,
        location: String? = null,
        availability: String? = null,
    ): CalendarEventEntity = CalendarEventEntity(
        id = id,
        userId = "user-1",
        sourceType = "google_calendar",
        sourceRef = id,
        title = "calendar-$id",
        startAt = startAt,
        endAt = startAt,
        isAllDay = isAllDay,
        attendeesRaw = attendeesRaw,
        availability = availability,
        location = location,
        syncStatus = "synced",
    )

    private fun scheduleActionEntity(providerWriteReady: Boolean = false): PersonActionItemCacheEntity =
        PersonActionItemCacheEntity(
            id = "pa-schedule-1",
            userId = "user-1",
            personId = "person-1",
            personDisplayName = "Jane Kim",
            personSortKey = "jane kim",
            surfacesCsv = "schedule,person",
            actionKind = "add_to_calendar",
            status = "active",
            title = "Jane Kim calendar candidate",
            primaryVerb = "후보 확인",
            shortReason = "메일에는 있는데 캘린더에는 없습니다.",
            commitmentId = "commitment-1",
            calendarEventId = null,
            sourceEventId = "source-event-1",
            sourceType = "gmail",
            sourceRef = "gmail-msg-1",
            dueAt = null,
            dueHint = null,
            dueIsApproximate = false,
            staleAfter = null,
            urgencyScore = 91.0,
            importanceScore = 82.0,
            confidence = 0.88,
            reasonCodesCsv = "calendar_missing",
            inputWatermark = Instant.parse("2026-06-03T02:00:00Z"),
            serverWatermark = Instant.parse("2026-06-03T03:00:00Z"),
            computedAt = Instant.parse("2026-06-03T02:00:01Z"),
            updatedAt = Instant.parse("2026-06-03T02:00:02Z"),
            snoozedUntil = null,
            completedAt = null,
            dismissedAt = null,
            primaryEvidenceKind = "schedule_link",
            primaryEvidenceId = "schedule-link-1",
            primaryEvidenceSourceRef = "gmail-msg-1",
            primaryEvidenceOccurredAt = null,
            primaryEvidenceLabel = "메일 일정 후보",
            primaryEvidenceQuote = null,
            providerWriteKind = if (providerWriteReady) "add_to_calendar" else null,
            providerWriteState = if (providerWriteReady) "ready" else null,
            providerWriteProvider = if (providerWriteReady) "google_calendar" else null,
            providerWriteSourceConnectionId = if (providerWriteReady) "conn-calendar-write" else null,
            providerWriteScheduleEventLinkId = if (providerWriteReady) "schedule-link-1" else null,
        )

    private fun scheduleLink(
        id: String,
        commitmentId: String = "source-schedule-1",
        relationType: String = "conflicts",
        status: String = "needs_review",
        proposedStartAt: Instant? = Instant.parse("2026-04-18T02:00:00Z"),
        proposedTitle: String? = "source-title",
        resolutionChoice: String? = null,
    ): ScheduleEventLinkEntity = ScheduleEventLinkEntity(
        id = id,
        userId = "user-1",
        calendarEventId = "calendar-1",
        calendarSourceType = "google_calendar",
        calendarSourceRef = "calendar-ref-1",
        sourceType = SourceType.GMAIL,
        sourceRef = "mail-1",
        rawEventId = "raw-1",
        commitmentId = commitmentId,
        relationType = relationType,
        status = status,
        confidence = 0.8,
        proposedStartAt = proposedStartAt,
        proposedEndAt = null,
        proposedTitle = proposedTitle,
        evidence = "메일 본문 근거",
        resolutionChoice = resolutionChoice,
        createdAt = Instant.parse("2026-04-18T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-18T00:00:00Z"),
    )
}
