package com.becalm.android.worker

import android.content.Context
import android.os.Build
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [UploadWorker.flushCommitments] (round6-plan § 6B.1 BLOCKER-A).
 *
 * Covers the replacement of the old apologetic "mark synced locally" flow with the real
 * `/v1/commitments:batch` upload. Also proves the empty-pending short-circuit.
 *
 * Raw ingestion flow and backoff behaviour are out of scope here — already covered by the
 * existing [UploadBackoff] / [RawIngestionRepository] tests; mocking them out keeps these
 * tests focused on the commitment path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class UploadWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val logger = RecordingLogger()

    private val rawIngestionRepository = FakeRawIngestionRepository()
    private val commitmentRepository = FakeCommitmentRepository()

    private lateinit var worker: UploadWorker

    @Before
    fun setUp() {
        every { workerParams.runAttemptCount } returns 0
        every { workerParams.inputData.getInt(UploadWorker.INPUT_KEY_ATTEMPT, 0) } returns 0

        worker = UploadWorker(
            appContext = context,
            workerParams = workerParams,
            authRepository = authRepository,
            rawIngestionRepository = rawIngestionRepository,
            commitmentRepository = commitmentRepository,
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )

        // Default: session present.
        io.mockk.coEvery { authRepository.currentSession() } returns SupabaseSession(
            accessToken = "access",
            refreshToken = "refresh",
            userId = "user-1",
            email = "u@example.com",
            expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE / 2),
        )
    }

    // ── Zero pending commitments → no network call, success ──────────────────

    @Test
    fun `flushCommitments with zero pending does not call uploadBatch`() = runTest {
        // Both repos return empty pending. Worker should hit success without invoking uploadBatch.
        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(0, commitmentRepository.uploadBatchCalls.get())
    }

    // ── N pending commitments → single uploadBatch call ──────────────────────

    @Test
    fun `flushCommitments with 3 pending invokes uploadBatch once and marks synced`() = runTest {
        val entities = (1..3).map { makeEntity(id = "c-$it") }
        commitmentRepository.pendingRows += entities

        // Server acks all 3.
        commitmentRepository.uploadBatchResponder = { rows ->
            BecalmResult.Success(
                CommitmentRepository.BatchResponse(
                    acknowledged = rows.size,
                    failed = emptyList(),
                ),
            )
        }

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(1, commitmentRepository.uploadBatchCalls.get())
        assertEquals(listOf("c-1", "c-2", "c-3"), commitmentRepository.markedSynced)
        assertTrue(commitmentRepository.markedFailed.isEmpty())
    }

    // ── All retryable failures → Result.retry + rows stay pending + no success chip ─

    @Test
    fun `flushCommitments all retryable failures returns retry and leaves rows pending`() = runTest {
        // Section 6D.3 regression: previously this returned Result.success() + recorded sync
        // success, producing a green chip while rows accumulated. Must return Result.retry()
        // so WorkManager backoff fires and the UI stays honest.
        val entities = (1..2).map { makeEntity(id = "c-$it") }
        commitmentRepository.pendingRows += entities

        commitmentRepository.uploadBatchResponder = { rows ->
            BecalmResult.Success(
                CommitmentRepository.BatchResponse(
                    acknowledged = 0,
                    failed = rows.map {
                        CommitmentRepository.FailedEvent(
                            clientEventId = it.id,
                            reason = "internal_error",
                            message = "transient",
                            retryable = true,
                        )
                    },
                ),
            )
        }

        val result = worker.doWork()

        // Result.retry() — NOT success — because nothing was actually uploaded.
        assertEquals(Result.retry(), result)
        // Rows stay pending (worker never called markSynced/markFailed).
        assertTrue(commitmentRepository.markedSynced.isEmpty())
        assertTrue(commitmentRepository.markedFailed.isEmpty())
        assertEquals(entities.map { it.id }, commitmentRepository.pendingRows.map { it.id })
        // No success chip: sourceStatusRepository.recordSyncSuccess must NOT have been called.
        io.mockk.coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        // Sync-error recorded with the "batch all retryable, backing off" message.
        io.mockk.coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncError(
                UploadWorker.SOURCE_TYPE,
                "batch all retryable, backing off",
                any(),
            )
        }
    }

    // ── Mixed page 1 + all-retryable page 2 → still Result.retry (no green chip) ─

    @Test
    fun `flushCommitments mixed page then all-retryable page returns retry`() = runTest {
        // Section 6D.3 coverage: page 1 acks 5 / quarantines 2 / leaves 3 retryable.
        // Page 2 (those 3 leftovers) comes back all-retryable. Worker must return
        // Result.retry even though page 1 made real progress — otherwise the green
        // chip would mask a stuck tail.
        val entities = (1..10).map { makeEntity(id = "c-$it") }
        commitmentRepository.pendingRows += entities

        // Script two page responders via the FIFO queue. The fake's findPendingSync
        // returns a snapshot, so page 1 sees all 10; after markSynced + markFailed,
        // page 2 sees only the 3 retryables (c-6..c-8) that remain pending.
        commitmentRepository.uploadBatchResponderQueue.addLast { rows ->
            BecalmResult.Success(
                CommitmentRepository.BatchResponse(
                    acknowledged = 5,
                    failed = listOf(
                        CommitmentRepository.FailedEvent("c-6", "schema_invalid", "bad", retryable = false),
                        CommitmentRepository.FailedEvent("c-7", "schema_invalid", "bad", retryable = false),
                        CommitmentRepository.FailedEvent("c-8", "internal_error", "transient", retryable = true),
                        CommitmentRepository.FailedEvent("c-9", "internal_error", "transient", retryable = true),
                        CommitmentRepository.FailedEvent("c-10", "internal_error", "transient", retryable = true),
                    ),
                ),
            )
        }
        // Page 2: the 3 retryable leftovers all fail retryably again → RetryNeeded.
        commitmentRepository.uploadBatchResponderQueue.addLast { rows ->
            BecalmResult.Success(
                CommitmentRepository.BatchResponse(
                    acknowledged = 0,
                    failed = rows.map {
                        CommitmentRepository.FailedEvent(
                            clientEventId = it.id,
                            reason = "internal_error",
                            message = "transient",
                            retryable = true,
                        )
                    },
                ),
            )
        }

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        // Page 1: c-1..c-5 synced, c-6..c-7 quarantined. Page 2: none moved.
        assertEquals(listOf("c-1", "c-2", "c-3", "c-4", "c-5"), commitmentRepository.markedSynced)
        assertEquals(listOf("c-6", "c-7"), commitmentRepository.markedFailed)
        // c-8, c-9, c-10 remain pending for the next backoff attempt.
        assertEquals(listOf("c-8", "c-9", "c-10"), commitmentRepository.pendingRows.map { it.id })
        assertEquals(2, commitmentRepository.uploadBatchCalls.get())
        io.mockk.coVerify(exactly = 0) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        io.mockk.coVerify(exactly = 1) {
            sourceStatusRepository.recordSyncError(
                UploadWorker.SOURCE_TYPE,
                "batch all retryable, backing off",
                any(),
            )
        }
    }

    // ── Full ack across every page → Result.success (happy-path regression pin) ─

    @Test
    fun `flushCommitments all pages fully acked returns success and records sync success`() = runTest {
        // Regression pin: the fix for all-retryable must not break the happy path where
        // every pending row is acknowledged — that case must still reach recordSyncSuccess.
        val entities = (1..4).map { makeEntity(id = "c-$it") }
        commitmentRepository.pendingRows += entities

        commitmentRepository.uploadBatchResponder = { rows ->
            BecalmResult.Success(
                CommitmentRepository.BatchResponse(
                    acknowledged = rows.size,
                    failed = emptyList(),
                ),
            )
        }

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(entities.map { it.id }, commitmentRepository.markedSynced)
        assertTrue(commitmentRepository.markedFailed.isEmpty())
        assertTrue(commitmentRepository.pendingRows.isEmpty())
        io.mockk.coVerify(exactly = 1) { sourceStatusRepository.recordSyncSuccess(any(), any()) }
        io.mockk.coVerify(exactly = 0) {
            sourceStatusRepository.recordSyncError(any(), any(), any())
        }
    }

    // ── Partial success → ack synced + non-retryable quarantined ─────────────

    @Test
    fun `flushCommitments partial response marks acked synced and non-retryable failed`() = runTest {
        val entities = (1..3).map { makeEntity(id = "c-$it") }
        commitmentRepository.pendingRows += entities

        commitmentRepository.uploadBatchResponder = { _ ->
            BecalmResult.Success(
                CommitmentRepository.BatchResponse(
                    acknowledged = 2,
                    failed = listOf(
                        CommitmentRepository.FailedEvent(
                            clientEventId = "c-3",
                            reason = "schema_invalid",
                            message = "bad",
                            retryable = false,
                        ),
                    ),
                ),
            )
        }

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(listOf("c-1", "c-2"), commitmentRepository.markedSynced)
        assertEquals(listOf("c-3"), commitmentRepository.markedFailed)
    }

    // ── Transport failure (5xx) → Result.retry ───────────────────────────────

    @Test
    fun `flushCommitments on ServerError returns retry`() = runTest {
        commitmentRepository.pendingRows += makeEntity(id = "c-1")
        commitmentRepository.uploadBatchResponder = { _ ->
            BecalmResult.Failure(BecalmError.ServerError(503, "down"))
        }

        val result = worker.doWork()

        // Non-final attempt + ServerError → Result.retry.
        assertEquals(Result.retry(), result)
        assertTrue(commitmentRepository.markedSynced.isEmpty())
        assertTrue(commitmentRepository.markedFailed.isEmpty())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeEntity(id: String): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = null,
        title = "t",
        description = null,
        quote = "q",
        sourceEventTitle = null,
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(0),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.0,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "pending",
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    // ── In-test fakes ────────────────────────────────────────────────────────

    /**
     * Fake [RawIngestionRepository] that always returns empty pending so the raw-flush
     * loop short-circuits. Only the methods touched by [UploadWorker.flushRawIngestion]
     * are implemented.
     */
    private class FakeRawIngestionRepository : RawIngestionRepository {
        override fun observeTimelineForUser(userId: String, limit: Int): Flow<List<RawIngestionEventEntity>> =
            emptyFlow()

        override fun observeForPerson(userId: String, personRef: String, limit: Int): Flow<List<RawIngestionEventEntity>> =
            emptyFlow()

        override suspend fun insertLocal(event: RawIngestionEventEntity): BecalmResult<String> =
            BecalmResult.Success(event.id)

        override suspend fun insertLocalBatch(events: List<RawIngestionEventEntity>): BecalmResult<List<String>> =
            BecalmResult.Success(events.map { it.id })

        override suspend fun findByClientEventId(userId: String, clientEventId: String): RawIngestionEventEntity? = null

        override suspend fun findById(id: String, userId: String): RawIngestionEventEntity? = null

        override suspend fun findPendingSync(userId: String, limit: Int): List<RawIngestionEventEntity> = emptyList()

        override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> = BecalmResult.Success(Unit)

        override suspend fun markFailed(id: String, lastAttemptAt: Instant): BecalmResult<Unit> =
            BecalmResult.Success(Unit)

        override suspend fun uploadBatch(events: List<RawIngestionEventEntity>): BecalmResult<BatchUploadResponse> =
            BecalmResult.Success(BatchUploadResponse(acknowledged = 0, failed = emptyList()))

        override suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): BecalmResult<List<String>> =
            BecalmResult.Success(emptyList())

        override suspend fun parkAndCancelPendingVoice(userId: String): BecalmResult<List<String>> =
            BecalmResult.Success(emptyList())

        override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> = BecalmResult.Success(0)
    }

    /**
     * Fake [CommitmentRepository] that models real DB semantics: rows stay `pending` until
     * the worker explicitly calls [markSynced] or [markFailed] on them. This lets tests
     * reproduce the "stuck loop" that the real `findPendingSync` query would hit when a
     * batch returns all-retryable failures (same rows re-returned on the next iteration).
     *
     * [uploadBatchResponder] is a FIFO queue: tests push one responder per expected page so
     * a mixed-response scenario (page 1 partial / page 2 all-retryable) is expressible
     * without shared mutable flags.
     */
    private class FakeCommitmentRepository : CommitmentRepository {
        val pendingRows: MutableList<CommitmentEntity> = mutableListOf()
        val markedSynced: MutableList<String> = mutableListOf()
        val markedFailed: MutableList<String> = mutableListOf()
        val uploadBatchCalls = AtomicInteger(0)

        /** Default responder: acks zero rows (used only when no scripted queue). */
        var uploadBatchResponder: (List<CommitmentEntity>) -> BecalmResult<CommitmentRepository.BatchResponse> = { _ ->
            BecalmResult.Success(CommitmentRepository.BatchResponse(acknowledged = 0, failed = emptyList()))
        }

        /**
         * Optional FIFO queue of responders. When non-empty, each batch upload consumes one
         * entry. Tests use this to script page-by-page server behavior for multi-page cases.
         */
        val uploadBatchResponderQueue: ArrayDeque<(List<CommitmentEntity>) -> BecalmResult<CommitmentRepository.BatchResponse>> =
            ArrayDeque()

        override fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>> = emptyFlow()
        override fun observePendingForToday(userId: String, endOfTodayEpochMs: Long): Flow<List<CommitmentEntity>> = emptyFlow()
        override fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>> = emptyFlow()

        override suspend fun refreshSince(
            userId: String,
            since: Instant?,
            personRef: String?,
            direction: String?,
            actionState: String?,
        ): BecalmResult<CommitmentRepository.RefreshStats> =
            BecalmResult.Success(CommitmentRepository.RefreshStats(0, 0, false, null))

        override suspend fun transitionState(
            id: String,
            event: com.becalm.android.domain.commitment.CommitmentEvent,
        ): BecalmResult<CommitmentEntity> = BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))

        override suspend fun updateActionState(
            id: String,
            newState: String,
            updatedAt: Instant,
        ): BecalmResult<Unit> = BecalmResult.Success(Unit)

        override suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity> {
            // Read-only snapshot: real DB query returns the same rows again if they are
            // never transitioned out of `pending`. The worker decides what moves.
            return pendingRows.take(limit).toList()
        }

        override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> {
            markedSynced += ids
            pendingRows.removeAll { it.id in ids }
            return BecalmResult.Success(Unit)
        }

        override suspend fun markFailed(id: String): BecalmResult<Unit> {
            markedFailed += id
            pendingRows.removeAll { it.id == id }
            return BecalmResult.Success(Unit)
        }

        override suspend fun uploadBatch(pending: List<CommitmentEntity>): BecalmResult<CommitmentRepository.BatchResponse> {
            uploadBatchCalls.incrementAndGet()
            val responder = uploadBatchResponderQueue.removeFirstOrNull() ?: uploadBatchResponder
            return responder(pending)
        }

        override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> = BecalmResult.Success(0)
    }
}
