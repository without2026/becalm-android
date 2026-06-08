package com.becalm.android.unit.worker

import androidx.work.ListenableWorker
import com.becalm.android.core.util.Logger
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkerRunGuard
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerRunGuardSpecTest {

    private val pauseGate: ProcessingPauseGate = mockk()
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `returns failure when capped retry attempts are exhausted`() = runTest {
        coEvery { pauseGate.shouldSkip("worker") } returns false

        val result = WorkerRunGuard(
            tag = "worker",
            runAttemptCount = 5,
            maxRetries = 5,
            processingPauseGate = pauseGate,
            logger = logger,
        ).terminalResultOrNull()

        assertEquals(ListenableWorker.Result.failure(), result)
        verify(exactly = 1) { logger.e("worker", "Exceeded 5 attempts, failing permanently") }
    }

    @Test
    fun `does not cap retryable server-backed workers when max retries is null`() = runTest {
        coEvery { pauseGate.shouldSkip("worker") } returns false

        val result = WorkerRunGuard(
            tag = "worker",
            runAttemptCount = 500,
            maxRetries = null,
            processingPauseGate = pauseGate,
            logger = logger,
        ).terminalResultOrNull()

        assertNull(result)
        verify(exactly = 0) { logger.e(any(), any<String>()) }
    }
}
