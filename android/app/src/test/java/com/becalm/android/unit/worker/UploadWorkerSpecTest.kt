package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.FailedEventDto
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.UploadWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class UploadWorkerSpecTest {

    private val appContext: Context = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val processingPauseGate: ProcessingPauseGate = mockk(relaxed = true)
    private val directExecutor = java.util.concurrent.Executor { runnable -> runnable.run() }
    private val taskExecutor: TaskExecutor = object : TaskExecutor {
        override fun getMainThreadExecutor() = directExecutor
        override fun getSerialTaskExecutor() = object : androidx.work.impl.utils.taskexecutor.SerialExecutor {
            override fun execute(command: Runnable) = command.run()
            override fun hasPendingTasks(): Boolean = false
        }
    }
    private val progressUpdater: ProgressUpdater = ProgressUpdater { _, _, _ ->
        SettableFuture.create<Void>().apply { set(null) }
    }
    private val foregroundUpdater: ForegroundUpdater = ForegroundUpdater { _, _, _ ->
        SettableFuture.create<Void>().apply { set(null) }
    }

    init {
        every { appContext.applicationContext } returns appContext
    }

    @Test
    fun `SYNC-004 no session fails closed without touching upload repositories`() = runTest {
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        val state = FakeState(session = null)
        val result = state.buildWorker().doWork()

        assertEquals(ListenableWorker.Result.failure().javaClass, result.javaClass)
        assertEquals(0, state.rawFindPendingCalls)
        assertEquals(0, state.rawUploadCalls)
        assertEquals(0, state.commitmentFindPendingCalls)
        assertEquals(0, state.commitmentUploadCalls)
        assertEquals(0, state.recordSyncStartCalls)
        assertEquals(0, state.recordSyncSuccessCalls)
        assertEquals(0, state.recordSyncErrorCalls)
    }

    @Test
    fun `SYNC-001 and SYNC-004 worker uploads pending raw and commitments and records sync success`() = runTest {
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        val state = FakeState(
            session = fakeSession,
            pendingRaw = listOf(rawEvent(id = "raw-1")),
            pendingCommitments = listOf(commitment(id = "commit-1")),
            rawUploadResult = BecalmResult.Success(
                BatchUploadResponse(acknowledged = 1, failed = emptyList<FailedEventDto>()),
            ),
            commitmentUploadResult = BecalmResult.Success(
                CommitmentRepository.BatchResponse(acknowledged = 1, failed = emptyList()),
            ),
        )

        val result = state.buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertTrue(state.rawFindPendingCalls >= 1)
        assertEquals(1, state.rawUploadCalls)
        assertEquals(1, state.rawMarkSyncedCalls)
        assertTrue(state.commitmentFindPendingCalls >= 1)
        assertEquals(1, state.commitmentUploadCalls)
        assertEquals(1, state.commitmentMarkSyncedCalls)
        assertEquals(1, state.recordSyncSuccessCalls)
        assertEquals(0, state.recordSyncErrorCalls)
    }

    @Test
    fun `SYNC-004 retryable upload failure records sync error and returns retry`() = runTest {
        coEvery { processingPauseGate.shouldSkip(any()) } returns false
        val state = FakeState(
            session = fakeSession,
            pendingRaw = listOf(rawEvent(id = "raw-1")),
            rawUploadResult = BecalmResult.Failure(BecalmError.Network(503, "service unavailable")),
        )

        val result = state.buildWorker().doWork()

        assertEquals(ListenableWorker.Result.retry().javaClass, result.javaClass)
        assertEquals(1, state.rawFindPendingCalls)
        assertEquals(1, state.rawUploadCalls)
        assertEquals(0, state.commitmentUploadCalls)
        assertEquals(0, state.recordSyncSuccessCalls)
        assertEquals(1, state.recordSyncErrorCalls)
    }

    @Test
    fun `PIPA-004 paused processing short-circuits upload worker`() = runTest {
        coEvery { processingPauseGate.shouldSkip(any()) } returns true
        val state = FakeState(session = fakeSession)

        val result = state.buildWorker().doWork()

        assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
        assertEquals(0, state.rawFindPendingCalls)
        assertEquals(0, state.commitmentFindPendingCalls)
    }

    private inner class FakeState(
        val session: SupabaseSession?,
        val pendingRaw: List<RawIngestionEventEntity> = emptyList(),
        val pendingCommitments: List<CommitmentEntity> = emptyList(),
        val rawUploadResult: BecalmResult<BatchUploadResponse> = BecalmResult.Success(
            BatchUploadResponse(acknowledged = 0, failed = emptyList()),
        ),
        val commitmentUploadResult: BecalmResult<CommitmentRepository.BatchResponse> = BecalmResult.Success(
            CommitmentRepository.BatchResponse(acknowledged = 0, failed = emptyList()),
        ),
    ) {
        var rawFindPendingCalls = 0
        var rawUploadCalls = 0
        var rawMarkSyncedCalls = 0
        var commitmentFindPendingCalls = 0
        var commitmentUploadCalls = 0
        var commitmentMarkSyncedCalls = 0
        var recordSyncStartCalls = 0
        var recordSyncSuccessCalls = 0
        var recordSyncErrorCalls = 0
        private var rawRemaining: List<RawIngestionEventEntity> = pendingRaw
        private var commitmentRemaining: List<CommitmentEntity> = pendingCommitments

        fun buildWorker(
            inputData: Data = Data.EMPTY,
            runAttemptCount: Int = 0,
        ): UploadWorker = UploadWorker(
            appContext,
            workerParams(inputData, runAttemptCount),
            authRepository(),
            rawRepository(),
            commitmentRepository(),
            sourceStatusRepository(),
            processingPauseGate,
            logger,
        )

        private fun authRepository(): AuthRepository = proxy(AuthRepository::class.java) { name, _ ->
            when (name) {
                "currentSession" -> session
                else -> unsupported(name)
            }
        }

        private fun rawRepository(): RawIngestionRepository = proxy(RawIngestionRepository::class.java) { name, args ->
            when (name) {
                "findPendingSync" -> {
                    rawFindPendingCalls += 1
                    rawRemaining
                }
                "uploadBatch" -> {
                    rawUploadCalls += 1
                    rawUploadResult
                }
                "markSynced" -> {
                    rawMarkSyncedCalls += 1
                    rawRemaining = emptyList()
                    BecalmResult.Success(Unit)
                }
                "markFailed" -> {
                    rawRemaining = emptyList()
                    BecalmResult.Success(Unit)
                }
                else -> unsupported(name, args)
            }
        }

        private fun commitmentRepository(): CommitmentRepository = proxy(CommitmentRepository::class.java) { name, args ->
            when (name) {
                "findPendingSync" -> {
                    commitmentFindPendingCalls += 1
                    commitmentRemaining
                }
                "uploadBatch" -> {
                    commitmentUploadCalls += 1
                    commitmentUploadResult
                }
                "markSynced" -> {
                    commitmentMarkSyncedCalls += 1
                    commitmentRemaining = emptyList()
                    BecalmResult.Success(Unit)
                }
                "markFailed" -> {
                    commitmentRemaining = emptyList()
                    BecalmResult.Success(Unit)
                }
                else -> unsupported(name, args)
            }
        }

        private fun sourceStatusRepository(): SourceStatusRepository = proxy(SourceStatusRepository::class.java) { name, _ ->
            when (name) {
                "recordSyncStart" -> {
                    recordSyncStartCalls += 1
                    BecalmResult.Success(Unit)
                }
                "recordSyncSuccess" -> {
                    recordSyncSuccessCalls += 1
                    BecalmResult.Success(Unit)
                }
                "recordSyncError" -> {
                    recordSyncErrorCalls += 1
                    BecalmResult.Success(Unit)
                }
                else -> unsupported(name)
            }
        }

        private fun workerParams(inputData: Data, runAttemptCount: Int): WorkerParameters =
            mockk<WorkerParameters>().also { params ->
                every { params.id } returns UUID.randomUUID()
                every { params.inputData } returns inputData
                every { params.tags } returns emptySet()
                every { params.triggeredContentUris } returns emptyList()
                every { params.triggeredContentAuthorities } returns emptyList()
                every { params.network } returns null
                every { params.runAttemptCount } returns runAttemptCount
                every { params.backgroundExecutor } returns directExecutor
                every { params.taskExecutor } returns taskExecutor
                every { params.workerFactory } returns WorkerFactory.getDefaultWorkerFactory()
                every { params.progressUpdater } returns progressUpdater
                every { params.foregroundUpdater } returns foregroundUpdater
            }
    }

    private fun rawEvent(id: String): RawIngestionEventEntity = RawIngestionEventEntity(
        id = id,
        userId = "user-1",
        clientEventId = "client-$id",
        sourceType = "voice",
        sourceRef = "content://raw/$id",
        eventTitle = "raw-$id",
        timestamp = Instant.parse("2026-04-23T00:00:00Z"),
        syncStatus = "pending",
    )

    private fun commitment(id: String): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = null,
        title = "title-$id",
        description = null,
        quote = "quote-$id",
        sourceEventTitle = "source-$id",
        sourceEventOccurredAt = Instant.parse("2026-04-23T00:00:00Z"),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "pending",
        createdAt = Instant.parse("2026-04-23T00:00:00Z"),
        updatedAt = Instant.parse("2026-04-23T00:00:00Z"),
    )

    private fun <T> proxy(type: Class<T>, handler: (String, Array<out Any?>?) -> Any?): T {
        val instance = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
        ) { _, method, args ->
            when (method.name) {
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "Fake${type.simpleName}"
                else -> handler(method.name, args)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return instance as T
    }

    private fun unsupported(name: String, args: Array<out Any?>? = null): Nothing {
        throw UnsupportedOperationException("Unexpected call: $name(${args?.toList() ?: emptyList<Any?>()})")
    }

    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = "user-1",
        email = "user@example.com",
        expiresAt = Instant.parse("2026-05-01T00:00:00Z"),
    )
}
