package com.becalm.android.ui.today

import app.cash.turbine.test
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.FakeClock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.worker.ForegroundCatchUpScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** Deterministic clock — 2026-04-18T09:00:00Z; matches kotlinx.datetime semantics. */
    private val fixedInstant: Instant = Instant.parse("2026-04-18T09:00:00Z")
    private val fakeClock = FakeClock(nowInstant = fixedInstant)

    private val commitmentRepository: CommitmentRepository = mockk()
    private val calendarEventRepository: CalendarEventRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk()
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk()
    private val authRepository: AuthRepository = mockk()
    private val foregroundCatchUpScheduler: ForegroundCatchUpScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private val fakeSession = SupabaseSession(
        accessToken = "access-token",
        refreshToken = "refresh-token",
        userId = "user-123",
        email = "test@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    private val fakeSourceStatuses = SourceType.ALL.map { sourceType ->
        SourceStatus(
            sourceType = sourceType,
            status = SourceConnectionStatus.CONNECTED,
            lastSyncedAt = fixedInstant,
            errorMessage = null,
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sourceStatusRepository.observeAll() } returns flowOf(fakeSourceStatuses)
        // Default: empty enrichment map. Tests that need a populated map override.
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── TDY-VM-01: empty state when no commitments or calendar events ──────────

    @Test
    fun `state emits empty timeline when repositories return no data`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            // Advance until loading=false
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            assertTrue("Timeline should be empty", emission.timeline.isEmpty())
            assertNull("No error expected", emission.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-02: timeline is sorted by time ────────────────────────────────

    @Test
    fun `state emits timeline sorted by time ascending`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession

        val earlier = Instant.fromEpochMilliseconds(1_000_000L)
        val later = Instant.fromEpochMilliseconds(2_000_000L)

        val commitment = buildCommitment(id = "c1", occurredAt = later)
        val calEvent = buildCalendarEvent(id = "e1", startAt = earlier)

        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(listOf(commitment))
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(listOf(calEvent))

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            assertEquals("Timeline should have 2 items", 2, emission.timeline.size)
            // Calendar event (earlier) should come first
            assertTrue(
                "First item must be CalendarEvent",
                emission.timeline[0] is TimelineItem.CalendarEvent,
            )
            assertTrue(
                "Second item must be Commitment",
                emission.timeline[1] is TimelineItem.Commitment,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-03: unauthenticated state ────────────────────────────────────

    @Test
    fun `state emits error when currentSession returns null`() = runTest {
        coEvery { authRepository.currentSession() } returns null
        // These should not be called, but stub defensively so the ViewModel doesn't crash.
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            assertNotNull("Error should be set when unauthenticated", emission.error)
            assertEquals("not authenticated", emission.error)
            assertTrue("Timeline should be empty on auth error", emission.timeline.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-04: onPullRefresh fans out to 3 repositories + catch-up ──────

    @Test
    fun `onPullRefresh triggers refresh on sourceStatus commitments calendar and catch-up`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        coEvery { sourceStatusRepository.refreshFromServer() } returns BecalmResult.Success(Unit)
        coEvery {
            commitmentRepository.refreshSince(any(), any(), any(), any(), any())
        } returns BecalmResult.Success(
            CommitmentRepository.RefreshStats(fetched = 0, upserted = 0, hasMore = false, nextCursor = null),
        )
        coEvery {
            calendarEventRepository.refreshSince(any(), any())
        } returns BecalmResult.Success(
            CalendarEventRepository.RefreshStats(fetched = 0, upserted = 0, hasMore = false, nextCursor = null),
        )

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.onPullRefresh()
        advanceUntilIdle()

        // TDY-009: pull gesture is the single trigger for ING-011 parallel catch-up.
        verify(exactly = 1) { foregroundCatchUpScheduler.triggerCatchUp() }
        coVerify(exactly = 1) { sourceStatusRepository.refreshFromServer() }
        coVerify(exactly = 1) {
            commitmentRepository.refreshSince(
                userId = "user-123",
                since = null,
                personRef = null,
                direction = null,
                actionState = null,
            )
        }
        coVerify(exactly = 1) {
            calendarEventRepository.refreshSince(userId = "user-123", since = null)
        }
    }

    // ── TDY-VM-06: overall = Syncing when any of the 6 sources is SYNCING ───

    @Test
    fun `overall state is Syncing when any source is SYNCING`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        // 1 SYNCING, 5 CONNECTED (voice excluded from the chip strip).
        val statuses = buildList {
            add(
                SourceStatus(
                    sourceType = SourceType.GMAIL,
                    status = SourceConnectionStatus.SYNCING,
                    lastSyncedAt = null,
                    errorMessage = null,
                ),
            )
            listOf(
                SourceType.OUTLOOK_MAIL,
                SourceType.NAVER_IMAP,
                SourceType.DAUM_IMAP,
                SourceType.GOOGLE_CALENDAR,
                SourceType.OUTLOOK_CALENDAR,
            ).forEach { src ->
                add(
                    SourceStatus(
                        sourceType = src,
                        status = SourceConnectionStatus.CONNECTED,
                        lastSyncedAt = Instant.fromEpochMilliseconds(1_000L),
                        errorMessage = null,
                    ),
                )
            }
            // Voice — must be filtered out of the banner state.
            add(
                SourceStatus(
                    sourceType = SourceType.VOICE,
                    status = SourceConnectionStatus.CONNECTED,
                    lastSyncedAt = Instant.fromEpochMilliseconds(1_000L),
                    errorMessage = null,
                ),
            )
        }
        every { sourceStatusRepository.observeAll() } returns flowOf(statuses)

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            val overall = emission.overall
            assertTrue("expected Syncing but was $overall", overall is OverallSyncState.Syncing)
            val syncing = overall as OverallSyncState.Syncing
            assertEquals("count should be 1", 1, syncing.count)
            assertEquals("total should be 6 (voice excluded)", 6, syncing.total)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-07: overall = Synced(min lastSyncedAt) when all 6 CONNECTED ──

    @Test
    fun `overall state is Synced with minimum lastSyncedAt when all sources CONNECTED`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        val earliest = Instant.fromEpochMilliseconds(1_000_000L)
        val later = Instant.fromEpochMilliseconds(2_000_000L)
        val statuses = listOf(
            SourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, earliest, null),
            SourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, later, null),
            SourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.CONNECTED, later, null),
            SourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED, later, null),
            SourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED, later, null),
            SourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.CONNECTED, later, null),
        )
        every { sourceStatusRepository.observeAll() } returns flowOf(statuses)

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            val overall = emission.overall
            assertTrue("expected Synced but was $overall", overall is OverallSyncState.Synced)
            assertEquals(
                "Synced.at must be the earliest lastSyncedAt across the 6 chip sources",
                earliest,
                (overall as OverallSyncState.Synced).at,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-08: overall = PartialFailure when any source is ERROR ────────

    @Test
    fun `overall state is PartialFailure when any source is ERROR`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(emptyList())
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())

        val statuses = listOf(
            SourceStatus(SourceType.GMAIL, SourceConnectionStatus.ERROR, null, "auth expired"),
            SourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000L), null),
            SourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.SYNCING, null, null),
            SourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000L), null),
            SourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000L), null),
            SourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.CONNECTED, Instant.fromEpochMilliseconds(1_000L), null),
        )
        every { sourceStatusRepository.observeAll() } returns flowOf(statuses)

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            // Error wins priority over SYNCING even though a source is actively syncing.
            assertTrue(
                "expected PartialFailure but was ${emission.overall}",
                emission.overall === OverallSyncState.PartialFailure,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-09: enrichment displayName surfaces when map has match ────────

    /**
     * TDY-001 / HIGH-A — commitment personRef="test@example.com" with enrichment
     * displayName="홍길동" must surface "홍길동" as the counterparty display label,
     * NOT the raw personRef / counterpartyRaw.
     */
    @Test
    fun `commitment counterparty display uses enrichment displayName when match present`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession

        val personRef = "test@example.com"
        val commitment = buildCommitment(
            id = "c-enrich-1",
            occurredAt = fixedInstant,
            personRef = personRef,
            counterpartyRaw = "test@example.com",
        )
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(listOf(commitment))
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(
            mapOf(personRef to buildEnrichment(personRef = personRef, displayName = "홍길동")),
        )

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            val row = emission.timeline.firstOrNull { it is TimelineItem.Commitment }
                as? TimelineItem.Commitment
            assertNotNull("commitment row must be present", row)
            assertEquals(
                "must render enriched displayName, not raw personRef",
                "홍길동",
                row!!.counterpartyDisplayName,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-10: nickname fallback when displayName is null ─────────────────

    @Test
    fun `commitment counterparty display falls back to nickname when displayName null`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession

        val personRef = "+821012345678"
        val commitment = buildCommitment(
            id = "c-enrich-2",
            occurredAt = fixedInstant,
            personRef = personRef,
            counterpartyRaw = "010-1234-5678",
        )
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(listOf(commitment))
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(
            mapOf(
                personRef to buildEnrichment(
                    personRef = personRef,
                    displayName = null,
                    nickname = "보스",
                ),
            ),
        )

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            val row = emission.timeline.firstOrNull { it is TimelineItem.Commitment }
                as? TimelineItem.Commitment
            assertNotNull("commitment row must be present", row)
            assertEquals("must render nickname when displayName null", "보스", row!!.counterpartyDisplayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-11: personRef fallback when no enrichment match ───────────────

    @Test
    fun `commitment counterparty display falls back to personRef when enrichment missing`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession

        val personRef = "unmatched@example.com"
        val commitment = buildCommitment(
            id = "c-enrich-3",
            occurredAt = fixedInstant,
            personRef = personRef,
            counterpartyRaw = null,
        )
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(listOf(commitment))
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())
        // Empty enrichment map — no match for personRef.
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            val row = emission.timeline.firstOrNull { it is TimelineItem.Commitment }
                as? TimelineItem.Commitment
            assertNotNull("commitment row must be present", row)
            assertEquals(
                "must fall back to personRef when no enrichment match exists",
                personRef,
                row!!.counterpartyDisplayName,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── TDY-VM-12: counterpartyRaw fallback when personRef is null ───────────

    @Test
    fun `commitment counterparty display falls back to counterpartyRaw when personRef null`() = runTest {
        coEvery { authRepository.currentSession() } returns fakeSession

        val rawText = "bob@x.com"
        val commitment = buildCommitment(
            id = "c-enrich-4",
            occurredAt = fixedInstant,
            personRef = null,
            counterpartyRaw = rawText,
        )
        every {
            commitmentRepository.observePendingForToday(any(), any())
        } returns flowOf(listOf(commitment))
        every {
            calendarEventRepository.observeForUser(any(), any(), any())
        } returns flowOf(emptyList())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())

        val viewModel = TodayViewModel(
            commitmentRepository = commitmentRepository,
            calendarEventRepository = calendarEventRepository,
            sourceStatusRepository = sourceStatusRepository,
            personEnrichmentRepository = personEnrichmentRepository,
            authRepository = authRepository,
            foregroundCatchUpScheduler = foregroundCatchUpScheduler,
            clock = fakeClock,
            logger = logger,
        )

        viewModel.state.test {
            var emission = awaitItem()
            while (emission.loading) {
                emission = awaitItem()
            }
            val row = emission.timeline.firstOrNull { it is TimelineItem.Commitment }
                as? TimelineItem.Commitment
            assertNotNull("commitment row must be present", row)
            assertTrue(
                "counterpartyRaw fallback must be present when personRef null",
                (row!!.counterpartyDisplayName ?: "").isNotEmpty(),
            )
            assertEquals(rawText, row.counterpartyDisplayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildCommitment(
        id: String,
        occurredAt: Instant,
        personRef: String? = null,
        counterpartyRaw: String? = null,
    ) = CommitmentEntity(
        id = id,
        userId = "user-123",
        direction = "give",
        counterpartyRaw = counterpartyRaw,
        personRef = personRef,
        title = "Do something",
        description = null,
        quote = "I'll do something",
        sourceEventTitle = null,
        sourceEventOccurredAt = occurredAt,
        dueDate = LocalDate(2026, 4, 16),
        actionState = "pending",
        sourceType = SourceType.VOICE,
        sourceRef = null,
        confidence = 0.9,
        commitmentState = CommitmentState.CONFIRMED,
        syncStatus = "synced",
        createdAt = occurredAt,
        updatedAt = occurredAt,
    )

    private fun buildEnrichment(
        personRef: String,
        displayName: String? = null,
        nickname: String? = null,
    ) = PersonEnrichmentEntity(
        personRef = personRef,
        displayName = displayName,
        nickname = nickname,
        company = null,
        title = null,
        sourceContactId = null,
        lastSyncedAt = fixedInstant,
    )

    private fun buildCalendarEvent(
        id: String,
        startAt: Instant,
        attendeesRaw: String? = null,
    ) = CalendarEventEntity(
        id = id,
        userId = "user-123",
        sourceType = SourceType.GOOGLE_CALENDAR,
        sourceRef = id,
        title = "Team Meeting",
        startAt = startAt,
        endAt = Instant.fromEpochMilliseconds(startAt.toEpochMilliseconds() + 3_600_000L),
        attendeesRaw = attendeesRaw,
        syncStatus = "synced",
    )
}
