package com.becalm.android.unit.worker

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.ScheduleEventLinkRepository
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.worker.CalendarRelationRefresh
import com.becalm.android.worker.SourceRelationRefreshCoordinator
import com.becalm.android.worker.SourceRelationRefreshPlan
import com.becalm.android.worker.SourceParticipantRefreshScope
import com.becalm.android.worker.WorkScheduler
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
        verify(exactly = 0) { workScheduler.enqueuePersonInteractionIndex() }
    }

    @Test
    fun `refresh pulls schedule event links and enqueues person index when calendar source truth changes`() = runTest {
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
        verify(exactly = 1) { workScheduler.enqueuePersonInteractionIndex() }
    }

    private fun coordinator(
        scheduleEventLinkRepository: ScheduleEventLinkRepository? = null,
    ): SourceRelationRefreshCoordinator =
        SourceRelationRefreshCoordinator(
            rawIngestionRepository = rawIngestionRepository,
            calendarEventRepository = calendarEventRepository,
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            scheduleEventLinkRepository = scheduleEventLinkRepository,
            workScheduler = workScheduler,
            logger = logger,
        )

    private fun stubRaw(upserted: Int) {
        coEvery { rawIngestionRepository.refreshSince(any(), any(), any()) } returns
            BecalmResult.Success(RawIngestionRepository.RefreshStats(upserted, upserted, false, null))
    }

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
    }
}
