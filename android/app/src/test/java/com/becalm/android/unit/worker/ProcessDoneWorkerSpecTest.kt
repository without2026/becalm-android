package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.CommitmentProgressEventDao
import com.becalm.android.data.local.db.dao.CompletionMatchCandidateRow
import com.becalm.android.data.local.db.entity.CommitmentProgressEventEntity
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.worker.ProcessDoneWorker
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessDoneWorkerSpecTest {

    @Test
    fun `high-confidence same-person same-thread signal completes action locally and enqueues upload`() = runTest {
        val authRepository: AuthRepository = mockk()
        val progressEventDao: CommitmentProgressEventDao = mockk()
        val commitmentDao: CommitmentDao = mockk()
        val workScheduler: WorkScheduler = mockk(relaxed = true)
        val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
        val clock: Clock = mockk()
        val logger: Logger = mockk(relaxed = true)
        val now = Instant.parse("2026-05-19T05:00:00Z")
        val signal = progress(id = "progress-1", confidence = 0.91, personId = "person-1")
        val candidate = CompletionMatchCandidateRow(
            id = "commit-1",
            title = "제안서 송부",
            quote = "제안서 보내주세요.",
            sourceEventOccurredAt = Instant.parse("2026-05-18T00:00:00Z"),
            conversationRef = "gmail:thread-1",
        )

        coEvery { authRepository.currentSession() } returns SupabaseSession(
            "token",
            "refresh",
            "user-1",
            "user@example.com",
            now,
        )
        coEvery { clock.nowInstant() } returns now
        coEvery { progressEventDao.findPendingCompletionSignals("user-1", ProcessDoneWorker.BATCH_SIZE) } returnsMany listOf(
            listOf(signal),
            emptyList(),
        )
        coEvery {
            commitmentDao.findCompletionMatchCandidates(
                userId = "user-1",
                personId = "person-1",
                sourceEventId = "raw-2",
                conversationRef = "gmail:thread-1",
                limit = any(),
            )
        } returns listOf(candidate)
        coEvery { commitmentDao.completeActionIfEligible("user-1", "commit-1", now) } returns 1
        coEvery { progressEventDao.markAutoApplied("progress-1", "commit-1", any(), now) } returns 1

        val result = buildWorker(
            authRepository = authRepository,
            progressEventDao = progressEventDao,
            commitmentDao = commitmentDao,
            workScheduler = workScheduler,
            reminderScheduler = reminderScheduler,
            clock = clock,
            logger = logger,
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, outputData.getInt(ProcessDoneWorker.KEY_CANDIDATE_COUNT, -1))
        assertEquals(1, outputData.getInt(ProcessDoneWorker.KEY_MARKED_COUNT, -1))
        assertEquals(0, outputData.getInt(ProcessDoneWorker.KEY_REVIEW_COUNT, -1))
        coVerify(exactly = 1) { commitmentDao.completeActionIfEligible("user-1", "commit-1", now) }
        coVerify(exactly = 1) { progressEventDao.markAutoApplied("progress-1", "commit-1", any(), now) }
        verify(exactly = 1) { reminderScheduler.cancel("commit-1") }
        verify(exactly = 1) { workScheduler.enqueueUpload() }
    }

    @Test
    fun `low-confidence signal is retained for review without touching commitments`() = runTest {
        val authRepository: AuthRepository = mockk()
        val progressEventDao: CommitmentProgressEventDao = mockk()
        val commitmentDao: CommitmentDao = mockk(relaxed = true)
        val workScheduler: WorkScheduler = mockk(relaxed = true)
        val reminderScheduler: ReminderScheduler = mockk(relaxed = true)
        val clock: Clock = mockk()
        val logger: Logger = mockk(relaxed = true)
        val now = Instant.parse("2026-05-19T05:00:00Z")
        val signal = progress(id = "progress-low", confidence = 0.4, personId = "person-1")

        coEvery { authRepository.currentSession() } returns SupabaseSession(
            "token",
            "refresh",
            "user-1",
            "user@example.com",
            now,
        )
        coEvery { clock.nowInstant() } returns now
        coEvery { progressEventDao.findPendingCompletionSignals("user-1", ProcessDoneWorker.BATCH_SIZE) } returnsMany listOf(
            listOf(signal),
            emptyList(),
        )
        coEvery { progressEventDao.markNeedsReview("progress-low", "low_confidence", now) } returns 1

        val result = buildWorker(
            authRepository = authRepository,
            progressEventDao = progressEventDao,
            commitmentDao = commitmentDao,
            workScheduler = workScheduler,
            reminderScheduler = reminderScheduler,
            clock = clock,
            logger = logger,
        ).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val outputData = (result as ListenableWorker.Result.Success).outputData
        assertEquals(1, outputData.getInt(ProcessDoneWorker.KEY_CANDIDATE_COUNT, -1))
        assertEquals(0, outputData.getInt(ProcessDoneWorker.KEY_MARKED_COUNT, -1))
        assertEquals(1, outputData.getInt(ProcessDoneWorker.KEY_REVIEW_COUNT, -1))
        coVerify(exactly = 0) { commitmentDao.completeActionIfEligible(any(), any(), any()) }
        verify(exactly = 0) { reminderScheduler.cancel(any()) }
        verify(exactly = 0) { workScheduler.enqueueUpload() }
    }

    private fun buildWorker(
        authRepository: AuthRepository,
        progressEventDao: CommitmentProgressEventDao,
        commitmentDao: CommitmentDao,
        workScheduler: WorkScheduler,
        reminderScheduler: ReminderScheduler,
        clock: Clock,
        logger: Logger,
    ): ProcessDoneWorker = ProcessDoneWorker(
        appContext = mockk<Context>(relaxed = true),
        workerParams = mockk<WorkerParameters>(relaxed = true),
        authRepository = authRepository,
        progressEventDao = progressEventDao,
        commitmentDao = commitmentDao,
        workScheduler = workScheduler,
        reminderScheduler = reminderScheduler,
        clock = clock,
        processingPauseGate = mockPauseGate(),
        logger = logger,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun mockPauseGate(): ProcessingPauseGate = mockk<ProcessingPauseGate>().also { gate ->
        coEvery { gate.shouldSkip(any()) } returns false
    }

    private fun progress(
        id: String,
        confidence: Double,
        personId: String?,
    ): CommitmentProgressEventEntity = CommitmentProgressEventEntity(
        id = id,
        userId = "user-1",
        sourceEventId = "raw-2",
        sourceType = "gmail",
        sourceRef = "gmail-message-2",
        conversationRef = "gmail:thread-1",
        personId = personId,
        eventType = "completed",
        status = "needs_review",
        confidence = confidence,
        evidenceQuote = "제안서 송부 완료했습니다.",
        reason = null,
        appliedAt = null,
        createdAt = Instant.parse("2026-05-19T04:59:00Z"),
        updatedAt = Instant.parse("2026-05-19T04:59:00Z"),
    )
}
