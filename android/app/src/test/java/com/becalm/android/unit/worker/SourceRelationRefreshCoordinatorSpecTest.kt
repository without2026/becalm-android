package com.becalm.android.unit.worker

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonActionRefreshStats
import com.becalm.android.data.repository.PersonActionRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.data.repository.UserCorrectionRepository
import com.becalm.android.worker.CalendarRelationRefresh
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.SourceParticipantRefreshScope
import com.becalm.android.worker.UploadWorker
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.sourceRelationRefreshPlanFor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRelationRefreshCoordinatorSpecTest {

    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val calendarEventRepository: CalendarEventRepository = mockk(relaxed = true)
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val sourceEventParticipantRepository: SourceEventParticipantRepository = mockk(relaxed = true)
    private val commitmentParticipantRepository: CommitmentParticipantRepository = mockk(relaxed = true)
    private val personActionRepository: PersonActionRepository = mockk(relaxed = true)
    private val userCorrectionRepository: UserCorrectionRepository = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `refresh pulls all selected mirrors then enqueues person index when data changed`() = runTest {
        stubRaw(upserted = 1)
        stubCalendar(upserted = 1)
        stubSourceParticipants(upserted = 1)
        stubCommitments(upserted = 1)
        stubCommitmentParticipants(upserted = 1)

        val result = coordinator().refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(
                sourceType = "gmail",
                rawSourceType = "gmail",
                calendarRefresh = CalendarRelationRefresh(),
                sourceParticipantRefreshScope = SourceParticipantRefreshScope.ALL,
            ),
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(5, (result as BecalmResult.Success).value.changedCount)
        coVerify(exactly = 1) { rawIngestionRepository.refreshSince("user-1", "gmail", null) }
        coVerify(exactly = 1) { calendarEventRepository.refreshSince("user-1", null, null, null) }
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince("user-1", null, null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince("user-1", null, null, null, null) }
        coVerify(exactly = 1) { commitmentParticipantRepository.refreshSince("user-1", null, null, null) }
        coVerify(exactly = 1) { personActionRepository.refresh(userId = "user-1", surface = null) }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh short-circuits on relation failure and does not enqueue person index`() = runTest {
        stubSourceParticipants(upserted = 0)
        coEvery { commitmentRepository.refreshSince("user-1", null, null, null, null) } returns
            BecalmResult.Failure(BecalmError.Io("boom"))

        val result = coordinator().refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(
                sourceType = "commitments_pull_refresh",
                sourceParticipantRefreshScope = SourceParticipantRefreshScope.ALL,
            ),
        )

        assertTrue(result is BecalmResult.Failure)
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince("user-1", null, null) }
        coVerify(exactly = 1) { commitmentRepository.refreshSince("user-1", null, null, null, null) }
        coVerify(exactly = 0) { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) }
        coVerify(exactly = 0) { personActionRepository.refresh(any(), any()) }
        verify(exactly = 0) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh pulls schedule event links and enqueues person index when calendar source truth changes`() = runTest {
        // spec: TDY-006
        val scheduleEventLinkRepository: ScheduleEventLinkRepository = mockk(relaxed = true)
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 0)
        stubCommitmentParticipants(upserted = 0)
        coEvery { scheduleEventLinkRepository.refreshSince("user-1", null, null) } returns
            BecalmResult.Success(ScheduleEventLinkRepository.RefreshStats(fetched = 1, upserted = 1, hasMore = false, nextCursor = null))

        val result = coordinator(scheduleEventLinkRepository = scheduleEventLinkRepository).refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(
                sourceType = "gmail",
                sourceParticipantRefreshScope = SourceParticipantRefreshScope.SOURCE,
            ),
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(1, (result as BecalmResult.Success).value.changedCount)
        coVerify(exactly = 1) { scheduleEventLinkRepository.refreshSince("user-1", null, null) }
        coVerify(exactly = 1) { personActionRepository.refresh(userId = "user-1", surface = null) }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh keeps source graph success when person action cache refresh fails`() = runTest {
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 1)
        stubCommitmentParticipants(upserted = 0)
        coEvery { personActionRepository.refresh(userId = "user-1", surface = null) } returns
            BecalmResult.Failure(BecalmError.Io("person action refresh failed"))

        val result = coordinator().refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(sourceType = "gmail"),
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(1, (result as BecalmResult.Success).value.changedCount)
        coVerify(exactly = 1) { personActionRepository.refresh(userId = "user-1", surface = null) }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh keeps source graph success when user correction refresh fails`() = runTest {
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 1)
        stubCommitmentParticipants(upserted = 0)
        coEvery { userCorrectionRepository.refreshSince("user-1", null) } returns
            BecalmResult.Failure(BecalmError.NotFound("user_corrections"))
        coEvery { userCorrectionRepository.applyActiveCorrections("user-1") } returns BecalmResult.Success(0)

        val result = coordinator(userCorrectionRepository = userCorrectionRepository).refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(sourceType = "gmail"),
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(1, (result as BecalmResult.Success).value.changedCount)
        coVerify(exactly = 1) { userCorrectionRepository.refreshSince("user-1", null) }
        coVerify(exactly = 1) { userCorrectionRepository.applyActiveCorrections("user-1") }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh keeps source graph success when active user correction reapply fails`() = runTest {
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 1)
        stubCommitmentParticipants(upserted = 0)
        coEvery { userCorrectionRepository.refreshSince("user-1", null) } returns
            BecalmResult.Success(UserCorrectionRepository.RefreshStats(fetched = 0, upserted = 0, hasMore = false, nextCursor = null))
        coEvery { userCorrectionRepository.applyActiveCorrections("user-1") } returns
            BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("apply failed")))

        val result = coordinator(userCorrectionRepository = userCorrectionRepository).refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(sourceType = "gmail"),
        )

        assertTrue(result is BecalmResult.Success)
        assertEquals(1, (result as BecalmResult.Success).value.changedCount)
        coVerify(exactly = 1) { userCorrectionRepository.refreshSince("user-1", null) }
        coVerify(exactly = 1) { userCorrectionRepository.applyActiveCorrections("user-1") }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh enqueues follow-up mirror refresh when any repository still has more pages`() = runTest {
        stubRaw(upserted = 0, hasMore = true)
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 0)
        stubCommitmentParticipants(upserted = 0)

        val result = coordinator().refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(
                sourceType = "gmail",
                rawSourceType = "gmail",
            ),
        )

        assertTrue(result is BecalmResult.Success)
        assertTrue((result as BecalmResult.Success).value.hasMore)
        verify(exactly = 1) { workScheduler.enqueueSourceRelationRefresh("gmail", 0L) }
    }

    @Test
    fun `backend sync follow-up uses broad mirror refresh instead of unsupported source scoped pull`() = runTest {
        stubSourceParticipants(upserted = 1)
        stubCommitments(upserted = 0)
        stubCommitmentParticipants(upserted = 0)

        val plan = sourceRelationRefreshPlanFor(UploadWorker.SOURCE_TYPE, resetBeforeRefresh = false)
        val result = coordinator().refresh(userId = "user-1", plan = plan)

        assertTrue(result is BecalmResult.Success)
        assertEquals(SourceParticipantRefreshScope.ALL, plan.sourceParticipantRefreshScope)
        assertEquals(null, plan.rawSourceType)
        assertEquals(null, plan.calendarRefresh)
        coVerify(exactly = 1) { sourceEventParticipantRepository.refreshSince("user-1", null, null) }
        coVerify(exactly = 0) { sourceEventParticipantRepository.refreshSince("user-1", UploadWorker.SOURCE_TYPE, null) }
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh resets mirror cursors once before first server-backed mirror pull`() = runTest {
        stubRaw(upserted = 1)
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 0)
        stubCommitmentParticipants(upserted = 0)

        val result = coordinator().refresh(
            userId = "user-1",
            plan = SourceRelationRefreshPlan(
                sourceType = "gmail",
                rawSourceType = "gmail",
                resetMirrorCursorBeforeRefresh = true,
            ),
        )

        assertTrue(result is BecalmResult.Success)
        coVerify(exactly = 1) { syncCursorStore.clearCursor("raw_ingestion_events:gmail") }
        coVerify(exactly = 1) { syncCursorStore.clearCursor("source_event_participants:gmail") }
        coVerify(exactly = 1) { rawIngestionRepository.refreshSince("user-1", "gmail", null) }
    }

    @Test
    fun `follow-up refresh chain keeps resuming until six pages are exhausted`() = runTest {
        coEvery { rawIngestionRepository.refreshSince("user-1", "gmail", null) } returnsMany listOf(
            rawStats(hasMore = true),
            rawStats(hasMore = true),
            rawStats(hasMore = true),
            rawStats(hasMore = true),
            rawStats(hasMore = true),
            rawStats(hasMore = false),
        )
        stubSourceParticipants(upserted = 0)
        stubCommitments(upserted = 0)
        stubCommitmentParticipants(upserted = 0)
        val subject = coordinator()
        val plan = SourceRelationRefreshPlan(
            sourceType = "gmail",
            rawSourceType = "gmail",
        )

        repeat(6) {
            val result = subject.refresh(userId = "user-1", plan = plan)
            assertTrue(result is BecalmResult.Success)
        }

        coVerify(exactly = 6) { rawIngestionRepository.refreshSince("user-1", "gmail", null) }
        verify(exactly = 5) { workScheduler.enqueueSourceRelationRefresh("gmail", 0L) }
    }

    private fun coordinator(
        scheduleEventLinkRepository: ScheduleEventLinkRepository? = null,
        userCorrectionRepository: UserCorrectionRepository? = null,
    ): SourceRelationRefreshCoordinator =
        SourceRelationRefreshCoordinator(
            rawIngestionRepository = rawIngestionRepository,
            calendarEventRepository = calendarEventRepository,
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            scheduleEventLinkRepository = scheduleEventLinkRepository,
            personActionRepository = personActionRepository,
            userCorrectionRepository = userCorrectionRepository,
            syncCursorStore = syncCursorStore,
            workScheduler = workScheduler,
            logger = logger,
        )

    private fun stubRaw(upserted: Int, hasMore: Boolean = false) {
        coEvery { rawIngestionRepository.refreshSince(any(), any(), any()) } returns
            rawStats(upserted = upserted, hasMore = hasMore)
    }

    private fun rawStats(upserted: Int = 0, hasMore: Boolean): BecalmResult.Success<RawIngestionRepository.RefreshStats> =
        BecalmResult.Success(RawIngestionRepository.RefreshStats(upserted, upserted, hasMore, null))

    private fun stubCalendar(upserted: Int) {
        coEvery { calendarEventRepository.refreshSince(any(), any(), any(), any()) } returns
            BecalmResult.Success(CalendarEventRepository.RefreshStats(upserted, upserted, false, null))
    }

    private fun stubSourceParticipants(upserted: Int) {
        coEvery { sourceEventParticipantRepository.refreshSince(any(), any(), any()) } returns
            BecalmResult.Success(SourceEventParticipantRepository.RefreshStats(upserted, upserted, false, null))
    }

    private fun stubCommitments(upserted: Int) {
        coEvery { commitmentRepository.refreshSince(any(), any(), any(), any(), any()) } returns
            BecalmResult.Success(CommitmentRepository.RefreshStats(upserted, upserted, false, null))
    }

    private fun stubCommitmentParticipants(upserted: Int) {
        coEvery { commitmentParticipantRepository.refreshSince(any(), any(), any(), any()) } returns
            BecalmResult.Success(CommitmentParticipantRepository.RefreshStats(upserted, upserted, false, null))
        coEvery { personActionRepository.refresh(any(), any()) } returns
            BecalmResult.Success(PersonActionRefreshStats(fetched = 0, deleted = 0, serverWatermark = null, recomputeState = null))
    }
}
