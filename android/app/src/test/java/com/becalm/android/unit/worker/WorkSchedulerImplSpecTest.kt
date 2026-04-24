package com.becalm.android.unit.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.becalm.android.core.util.Logger
import com.becalm.android.worker.ColdSyncWorkInputs
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

class WorkSchedulerImplSpecTest {

    private val appContext: Context = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val workManager: WorkManager = mockk()
    private val operation: Operation = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { appContext.applicationContext } returns appContext
        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(appContext) } returns workManager
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns operation
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
    }

    @Test
    fun `SYNC-004 and SYNC-006 enqueueUpload requires connected network and replaces sync-all-upload work`() {
        val workName = slot<String>()
        val policy = slot<ExistingWorkPolicy>()
        val request = slot<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueueUpload(attempt = 7)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(capture(workName), capture(policy), capture(request))
        }
        assertEquals("sync-all-upload", workName.captured)
        assertEquals(ExistingWorkPolicy.REPLACE, policy.captured)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(7, request.captured.workSpec.input.getInt("attempt", -1))
    }

    @Test
    fun `COLD-001 stage1 one-shot scheduler passes bounded lookback days into worker input`() {
        val request = slot<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueueGmailOneShotNow(7)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), any(), capture(request))
        }
        assertEquals(7, request.captured.workSpec.input.getInt(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS, -1))
    }
}
