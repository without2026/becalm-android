package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger
import com.becalm.android.worker.OverdueSweepWorker
import com.becalm.android.worker.UniqueWorkKeys
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

class OverdueSweepSchedulingSpecTest {

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `CMT-011 unique work key stays stable for overdue periodic sweep`() {
        assertEquals("commitment.overdue_sweep", UniqueWorkKeys.OVERDUE_SWEEP)
    }

    @Test
    fun `CMT-011 scheduleOverdueSweep uses KEEP policy and 6 hour periodic cadence`() {
        mockkStatic(WorkManager::class)
        val context: Context = mockk(relaxed = true)
        val logger: Logger = mockk(relaxed = true)
        val workManager: WorkManager = mockk()
        val requestSlot = slot<PeriodicWorkRequest>()

        every { WorkManager.getInstance(context) } returns workManager
        every {
            workManager.enqueueUniquePeriodicWork(
                UniqueWorkKeys.OVERDUE_SWEEP,
                ExistingPeriodicWorkPolicy.KEEP,
                capture(requestSlot),
            )
        } returns mockk<Operation>(relaxed = true)

        WorkSchedulerImpl(context, logger).scheduleOverdueSweep()

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(
                UniqueWorkKeys.OVERDUE_SWEEP,
                ExistingPeriodicWorkPolicy.KEEP,
                any(),
            )
        }
        val workSpec = requestSlot.captured.workSpec

        assertEquals(OverdueSweepWorker::class.java.name, workSpec.workerClassName)
        assertEquals(TimeUnit.HOURS.toMillis(6), workSpec.intervalDuration)
        assertEquals(NetworkType.NOT_REQUIRED, workSpec.constraints.requiredNetworkType)
        assertTrue(workSpec.constraints.requiresBatteryNotLow())
    }
}
