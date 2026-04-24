package com.becalm.android.integration.local.ui.today

import app.cash.turbine.test
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepositoryImpl
import com.becalm.android.data.repository.CommitmentRepositoryImpl
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.today.TimelineItem
import com.becalm.android.ui.today.TodayScreenStateSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TodayScreenStateSourceLocalIntegrationTest {

    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val cursorStore = mockk<com.becalm.android.data.local.datastore.SyncCursorStore>(relaxed = true)
    private val userPrefsStore = mockk<com.becalm.android.data.local.datastore.UserPrefsStore>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()
    private val sourceStatuses = MutableStateFlow(
        listOf(
            SourceStatus(
                sourceType = SourceType.VOICE,
                status = SourceConnectionStatus.CONNECTED,
                lastSyncedAt = Instant.parse("2026-04-23T00:30:00Z"),
                errorMessage = null,
            ),
        ),
    )
    private val sourceStatusRepository = mockk<SourceStatusRepository> {
        every { observeAll() } returns sourceStatuses
    }
    private val userId = "user-1"
    private val clock = FakeClock(Instant.parse("2026-04-23T03:00:00Z"))

    private val commitmentRepository = CommitmentRepositoryImpl(
        dao = db.commitmentDao(),
        api = api,
        cursorStore = cursorStore,
        userPrefsStore = userPrefsStore,
        database = db,
        logger = logger,
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val calendarRepository = CalendarEventRepositoryImpl(
        dao = db.calendarEventDao(),
        api = api,
        cursorStore = cursorStore,
        logger = logger,
    )
    private val enrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )

    @Before
    fun setUp() {
        coEvery { authRepository.currentSession() } returns LocalIntegrationSupport.authenticatedSession(userId = userId)
        every { userPrefsStore.observeProcessingPaused() } returns flowOf(false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `TDY-001 TDY-004 and TDY-005 room to repository to state-source chain emits only today's rows with enrichment fallback`() = runTest {
        val stateSource = TodayScreenStateSource(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = enrichmentRepository,
            authRepository = authRepository,
            userPrefsStore = userPrefsStore,
            clock = clock,
            logger = logger,
        )
        val refreshing = MutableStateFlow(false)
        val userIdFlow = stateSource.userIdFlow(this.backgroundScope)

        stateSource.observeUiState(userIdFlow, refreshing).test {
            val initial = awaitItem()
            assertTrue(initial.timeline.isEmpty())

            db.personEnrichmentDao().upsert(
                PersonEnrichmentEntity(
                    personRef = "+821012345678",
                    displayName = "김영희",
                    nickname = "영희",
                    lastSyncedAt = Instant.parse("2026-04-23T02:00:00Z"),
                ),
            )
            db.commitmentDao().insert(
                commitment(
                    id = "commitment-today",
                    userId = userId,
                    personRef = "+821012345678",
                    counterpartyRaw = "01012345678",
                    title = "영희에게 송금",
                    dueAt = Instant.parse("2026-04-23T08:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-23T01:00:00Z"),
                ),
            )
            db.calendarEventDao().insertAll(
                listOf(
                    calendarEvent(
                        id = "calendar-today",
                        userId = userId,
                        title = "Daily standup",
                        startAt = Instant.parse("2026-04-23T02:00:00Z"),
                        endAt = Instant.parse("2026-04-23T03:00:00Z"),
                        attendeesRaw = "team@example.com",
                    ),
                    calendarEvent(
                        id = "calendar-tomorrow",
                        userId = userId,
                        title = "Tomorrow planning",
                        startAt = Instant.parse("2026-04-24T02:00:00Z"),
                        endAt = Instant.parse("2026-04-24T03:00:00Z"),
                        attendeesRaw = "team@example.com",
                    ),
                ),
            )

            var updated = awaitItem()
            while (updated.timeline.size < 2) {
                updated = awaitItem()
            }

            val commitment = updated.timeline.filterIsInstance<TimelineItem.Commitment>().single()
            val meeting = updated.timeline.filterIsInstance<TimelineItem.Meeting>().single()
            assertEquals("김영희", commitment.counterpartyDisplayName)
            assertEquals("영희에게 송금", commitment.title)
            assertEquals("Daily standup", meeting.title)
            assertFalse(updated.timeline.any { it.title == "Tomorrow planning" })
            assertFalse(updated.overallSyncing)

            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun commitment(
        id: String,
        userId: String,
        personRef: String?,
        counterpartyRaw: String?,
        title: String,
        dueAt: Instant?,
        sourceEventOccurredAt: Instant,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = userId,
        direction = "give",
        counterpartyRaw = counterpartyRaw,
        personRef = personRef,
        title = title,
        description = null,
        quote = "quote-$id",
        sourceEventTitle = "source-$id",
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = dueAt,
        dueHint = null,
        sourceType = SourceType.GMAIL,
        sourceRef = "source-ref-$id",
        createdAt = sourceEventOccurredAt,
        updatedAt = sourceEventOccurredAt,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
    )

    private fun calendarEvent(
        id: String,
        userId: String,
        title: String,
        startAt: Instant,
        endAt: Instant,
        attendeesRaw: String?,
    ): CalendarEventEntity = CalendarEventEntity(
        id = id,
        userId = userId,
        sourceType = SourceType.GOOGLE_CALENDAR,
        sourceRef = id,
        title = title,
        startAt = startAt,
        endAt = endAt,
        attendeesRaw = attendeesRaw,
        syncStatus = "synced",
    )
}
