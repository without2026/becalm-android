package com.becalm.android.unit.ui.today

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.ui.today.OverallSyncState
import com.becalm.android.ui.today.TimelineItem
import com.becalm.android.ui.today.TodayEffect
import com.becalm.android.ui.today.TodayViewModel
import com.becalm.android.worker.ForegroundCatchUpScheduler
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
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
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sourceStatusRepository.observeAll() } returns flowOf(
            listOf(
                SourceStatus("gmail", SourceConnectionStatus.SYNCING, now, null),
                SourceStatus("outlook_mail", SourceConnectionStatus.ERROR, now, "token expired"),
            ),
        )
        every { userPrefsStore.observeProcessingPaused() } returns flowOf(false)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `TDY-001 timeline merges sorted rows and resolves counterparty display with enrichment`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(
            listOf(
                commitment(
                    id = "c1",
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    personRef = "lee@corp.com",
                ),
                commitment(
                    id = "s1",
                    itemType = CommitmentItemType.SCHEDULE,
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CHANGED,
                    occurredAt = Instant.parse("2026-04-18T01:30:00Z"),
                    personRef = "lee@corp.com",
                ),
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
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(
            mapOf("lee@corp.com" to enrichment("lee@corp.com", displayName = "이대리")),
        )

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(4, emission.timeline.size)
            assertTrue(emission.timeline[0] is TimelineItem.Meeting)
            assertTrue(emission.timeline[1] is TimelineItem.Commitment)
            assertTrue(emission.timeline[2] is TimelineItem.Commitment)
            assertTrue(emission.timeline[3] is TimelineItem.CalendarEvent)
            assertEquals(
                "이대리",
                (emission.timeline[1] as TimelineItem.Commitment).counterpartyDisplayName,
            )
            assertEquals(
                CommitmentItemType.SCHEDULE,
                (emission.timeline[2] as TimelineItem.Commitment).itemType,
            )
            assertEquals(1, emission.personFocus.size)
            assertEquals("이대리", emission.personFocus.single().displayName)
            assertEquals(2, emission.personFocus.single().commitmentCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-002 authenticated empty today state stays crash free and renders no items`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
    fun `TDY-004 today commitments stay room-backed and react to local invalidation within the KST day`() = runTest {
        val commitmentsFlow = MutableStateFlow<List<CommitmentEntity>>(emptyList())
        val dayEndEpochMs = slot<Long>()
        coEvery { authRepository.currentSession() } returns session()
        every {
            commitmentRepository.observePendingForToday(
                userId = "user-1",
                endOfTodayEpochMs = capture(dayEndEpochMs),
            )
        } returns commitmentsFlow
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertTrue(emission.timeline.isEmpty())
            assertEquals(
                Instant.parse("2026-04-18T14:59:59.999Z").toEpochMilliseconds(),
                dayEndEpochMs.captured,
            )

            commitmentsFlow.value = listOf(
                commitment(
                    id = "c-kst",
                    occurredAt = Instant.parse("2026-04-18T04:00:00Z"),
                    personRef = "lee@corp.com",
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
    fun `TDY-005 today calendar stays room-backed and queries the exact KST day window`() = runTest {
        val calendarFlow = MutableStateFlow<List<CalendarEventEntity>>(emptyList())
        val todayStart = slot<Instant>()
        val todayEnd = slot<Instant>()
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
                Instant.parse("2026-04-18T14:59:59.999Z").toEpochMilliseconds(),
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
    fun `TDY unauthenticated state surfaces explicit error and empty timeline`() = runTest {
        coEvery { authRepository.currentSession() } returns null
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
    fun `TDY counterparty display falls back to personRef when enrichment is missing`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(
            listOf(
                commitment(
                    id = "c2",
                    occurredAt = Instant.parse("2026-04-18T01:00:00Z"),
                    personRef = "raw@example.com",
                ),
            ),
        )
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

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
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = buildViewModel()

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) emission = awaitItem()

            assertEquals(true, emission.overallSyncing)
            assertEquals("SYNCING", emission.sourceStatus.getValue("gmail").statusLabel)
            assertEquals("token expired", emission.sourceStatus.getValue("outlook_mail").errorMessage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `TDY-008 overall state resolves synced at earliest timestamp when all sources connected`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
        every { calendarEventRepository.observeForUser(any(), any(), any()) } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)
        coEvery {
            commitmentRepository.refreshSince(
                userId = "user-1",
                since = null,
                personRef = null,
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
                personRef = null,
                direction = null,
                actionState = null,
            )
        }
        coVerify(exactly = 1) { calendarEventRepository.refreshSince(userId = "user-1", since = null) }
        assertEquals(false, viewModel.state.value.refreshing)
    }

    @Test
    fun `TDY-007 onOpenSettings emits settings navigation effect`() = runTest {
        coEvery { authRepository.currentSession() } returns session()
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
    fun `TDY-009 pull refresh always triggers catch-up and skips repository refresh when unauthenticated`() = runTest {
        coEvery { authRepository.currentSession() } returns null
        every { commitmentRepository.observePendingForToday(any(), any()) } returns flowOf(emptyList())
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
        assertEquals(false, viewModel.state.value.refreshing)
    }

    private fun buildViewModel(): TodayViewModel = TodayViewModel(
        commitmentRepository = commitmentRepository,
        calendarEventRepository = calendarEventRepository,
        sourceStatusRepository = sourceStatusRepository,
        personEnrichmentRepository = personEnrichmentRepository,
        authRepository = authRepository,
        userPrefsStore = userPrefsStore,
        foregroundCatchUpScheduler = foregroundCatchUpScheduler,
        clock = clock,
        logger = logger,
    )

    private fun session() = SupabaseSession(
        accessToken = "a",
        refreshToken = "r",
        userId = "user-1",
        email = "test@example.com",
        expiresAt = Instant.parse("2026-04-19T00:00:00Z"),
    )

    private fun enrichment(personRef: String, displayName: String): PersonEnrichmentEntity = PersonEnrichmentEntity(
        personRef = personRef,
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
        personRef: String?,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        scheduleStatus: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = scheduleStatus,
        counterpartyRaw = null,
        personRef = personRef,
        title = "title-$id",
        description = null,
        quote = "quote",
        sourceEventTitle = null,
        sourceEventOccurredAt = occurredAt,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = "voice",
        sourceRef = null,
        confidence = 1.0,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun calendarEvent(
        id: String,
        startAt: Instant,
        attendeesRaw: String?,
    ): CalendarEventEntity = CalendarEventEntity(
        id = id,
        userId = "user-1",
        sourceType = "google_calendar",
        sourceRef = id,
        title = "calendar-$id",
        startAt = startAt,
        endAt = startAt,
        attendeesRaw = attendeesRaw,
        syncStatus = "synced",
    )
}
