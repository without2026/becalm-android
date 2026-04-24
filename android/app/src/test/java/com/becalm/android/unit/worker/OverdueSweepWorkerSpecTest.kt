package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.worker.OverdueSweepWorker
import com.becalm.android.worker.ProcessingPauseGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverdueSweepWorkerSpecTest {

    @Test
    fun `CMT-011 graceCutoff is exactly now minus 24 hours`() {
        val now = Instant.parse("2026-04-23T15:00:00Z")

        assertEquals(
            Instant.parse("2026-04-22T15:00:00Z"),
            OverdueSweepWorker.Companion.graceCutoff(now),
        )
    }

    @Test
    fun `CMT-011 doWork queries overdue candidates with grace cutoff and batch size then marks returned ids`() = runTest {
        val authRepository: AuthRepository = mockk()
        val commitmentRepository: CommitmentRepository = mockk()
        val clock: Clock = mockk()
        val logger: Logger = mockk(relaxed = true)
        val now = Instant.parse("2026-04-23T15:00:00Z")
        val cutoff = Instant.parse("2026-04-22T15:00:00Z")
        val session = SupabaseSession("token", "refresh", "user-1", "user@example.com", now)
        val candidates = listOf(
            entity(id = "pending-1", actionState = "pending"),
            entity(id = "reminded-1", actionState = "reminded"),
            entity(id = "follow-1", actionState = "followed_up"),
        )

        coEvery { authRepository.currentSession() } returns session
        coEvery { clock.nowInstant() } returns now
        coEvery {
            commitmentRepository.findOverdueCandidates("user-1", cutoff, OverdueSweepWorker.BATCH_SIZE)
        } returns candidates
        coEvery {
            commitmentRepository.markOverdue(listOf("pending-1", "reminded-1", "follow-1"), now)
        } returns BecalmResult.Success(3)

        val worker = buildWorker(authRepository, commitmentRepository, clock, logger)
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(3, outputData.getInt(OverdueSweepWorker.KEY_CANDIDATE_COUNT, -1))
        assertEquals(3, outputData.getInt(OverdueSweepWorker.KEY_MARKED_COUNT, -1))
        coVerify(exactly = 1) {
            commitmentRepository.findOverdueCandidates("user-1", cutoff, OverdueSweepWorker.BATCH_SIZE)
        }
        coVerify(exactly = 1) {
            commitmentRepository.markOverdue(listOf("pending-1", "reminded-1", "follow-1"), now)
        }
    }

    @Test
    fun `CMT-011 doWork returns zero counts without repository selection when no session exists`() = runTest {
        val authRepository: AuthRepository = mockk()
        val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
        val clock: Clock = mockk(relaxed = true)
        val logger: Logger = mockk(relaxed = true)

        coEvery { authRepository.currentSession() } returns null

        val worker = buildWorker(authRepository, commitmentRepository, clock, logger)
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(0, outputData.getInt(OverdueSweepWorker.KEY_CANDIDATE_COUNT, -1))
        assertEquals(0, outputData.getInt(OverdueSweepWorker.KEY_MARKED_COUNT, -1))
        coVerify(exactly = 0) { commitmentRepository.findOverdueCandidates(any(), any(), any()) }
        coVerify(exactly = 0) { commitmentRepository.markOverdue(any(), any()) }
    }

    private fun buildWorker(
        authRepository: AuthRepository,
        commitmentRepository: CommitmentRepository,
        clock: Clock,
        logger: Logger,
    ): OverdueSweepWorker = OverdueSweepWorker(
        appContext = mockk<Context>(relaxed = true),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        authRepository = authRepository,
        commitmentRepository = commitmentRepository,
        clock = clock,
        processingPauseGate = mockPauseGate(),
        logger = logger,
    )

    private fun mockPauseGate(): ProcessingPauseGate = mockk<ProcessingPauseGate>().also { gate ->
        coEvery { gate.shouldSkip(any()) } returns false
    }

    private fun entity(
        id: String,
        actionState: String,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = "lee@corp.com",
        title = "Title",
        description = null,
        quote = "quote body",
        sourceEventTitle = "Call",
        sourceEventOccurredAt = Instant.parse("2026-04-20T00:00:00Z"),
        dueAt = Instant.parse("2026-04-20T00:00:00Z"),
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "pending",
        createdAt = Instant.parse("2026-04-20T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-20T00:00:00Z"),
        lastEditedBy = null,
        lastEditedAt = null,
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = null,
    )
}
