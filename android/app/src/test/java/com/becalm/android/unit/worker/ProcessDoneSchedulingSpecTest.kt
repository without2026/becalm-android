package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger
import com.becalm.android.worker.ProcessDoneWorker
import com.becalm.android.worker.UniqueWorkKeys
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.WorkSchedulerImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessDoneSchedulingSpecTest {

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `completion processing one-shot uses stable key replace policy and debounce`() {
        mockkStatic(WorkManager::class)
        val context: Context = mockk(relaxed = true)
        val logger: Logger = mockk(relaxed = true)
        val workManager: WorkManager = mockk()
        val requestSlot = slot<OneTimeWorkRequest>()

        every { WorkManager.getInstance(context) } returns workManager
        every {
            workManager.enqueueUniqueWork(
                UniqueWorkKeys.PROCESS_DONE,
                ExistingWorkPolicy.REPLACE,
                capture(requestSlot),
            )
        } returns mockk<Operation>(relaxed = true)

        WorkSchedulerImpl(context, logger).enqueueProcessDone()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                UniqueWorkKeys.PROCESS_DONE,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
        val workSpec = requestSlot.captured.workSpec

        assertEquals(ProcessDoneWorker::class.java.name, workSpec.workerClassName)
        assertEquals(WorkScheduler.PROCESS_DONE_DEBOUNCE_SECONDS * 1_000L, workSpec.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, workSpec.constraints.requiredNetworkType)
        assertTrue(workSpec.constraints.requiresBatteryNotLow())
    }

    @Test
    fun `completion processing periodic sweep uses KEEP policy and 6 hour cadence`() {
        mockkStatic(WorkManager::class)
        val context: Context = mockk(relaxed = true)
        val logger: Logger = mockk(relaxed = true)
        val workManager: WorkManager = mockk()
        val requestSlot = slot<PeriodicWorkRequest>()

        every { WorkManager.getInstance(context) } returns workManager
        every {
            workManager.enqueueUniquePeriodicWork(
                UniqueWorkKeys.PROCESS_DONE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                capture(requestSlot),
            )
        } returns mockk<Operation>(relaxed = true)

        WorkSchedulerImpl(context, logger).scheduleProcessDoneSweep()

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                UniqueWorkKeys.PROCESS_DONE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                any<PeriodicWorkRequest>(),
            )
        }
        val workSpec = requestSlot.captured.workSpec

        assertEquals(ProcessDoneWorker::class.java.name, workSpec.workerClassName)
        assertEquals(TimeUnit.HOURS.toMillis(6), workSpec.intervalDuration)
        assertEquals(NetworkType.NOT_REQUIRED, workSpec.constraints.requiredNetworkType)
        assertTrue(workSpec.constraints.requiresBatteryNotLow())
    }
}
