package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger
import com.becalm.android.worker.WorkSchedulerImpl
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppRuntimeWorkSchedulingSpecTest {

    private val appContext: Context = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val workManager: WorkManager = mockk()
    private val operation: Operation = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { appContext.applicationContext } returns appContext
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(appContext) } returns workManager
        every {
            workManager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>())
        } returns operation
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `SYNC periodic redundancy uses connected network and keep semantics`() {
        val workName = slot<String>()
        val policy = slot<ExistingPeriodicWorkPolicy>()
        val request = slot<PeriodicWorkRequest>()

        WorkSchedulerImpl(appContext, logger).scheduleUploadRedundancy()

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(capture(workName), capture(policy), capture(request))
        }
        assertEquals("sync-all-upload-periodic", workName.captured)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, policy.captured)
        assertEquals(androidx.work.NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(0, request.captured.workSpec.input.getInt("attempt", -1))
    }

    @Test
    fun `ENR periodic sweep uses daily keep semantics and no network requirement`() {
        val workName = slot<String>()
        val policy = slot<ExistingPeriodicWorkPolicy>()
        val request = slot<PeriodicWorkRequest>()

        WorkSchedulerImpl(appContext, logger).scheduleEnrichmentSweep()

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(capture(workName), capture(policy), capture(request))
        }
        assertEquals("enrichment.periodic", workName.captured)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, policy.captured)
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, request.captured.workSpec.constraints.requiredNetworkType)
    }
}
