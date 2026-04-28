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
import com.becalm.android.worker.WorkSchedulerRequests
import com.becalm.android.worker.extraction.CommitmentExtractionWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val policy = slot<ExistingWorkPolicy>()
        val request = slot<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueueImapNaverOneShotNow(7)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), capture(policy), capture(request))
        }
        assertEquals(ExistingWorkPolicy.REPLACE, policy.captured)
        assertEquals(7, request.captured.workSpec.input.getInt(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS, -1))
    }

    @Test
    fun `foreground catch-up one-shot keeps existing in-flight source work`() {
        val policy = slot<ExistingWorkPolicy>()

        WorkSchedulerImpl(appContext, logger).enqueueMediaStoreOneShotNow()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), capture(policy), any<OneTimeWorkRequest>())
        }
        assertEquals(ExistingWorkPolicy.KEEP, policy.captured)
    }

    @Test
    fun `EMAIL-001 commitment extraction keeps battery constraint without expedited request`() {
        val request = slot<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueueCommitmentExtraction("raw-1")

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), any(), capture(request))
        }
        val workSpec = request.captured.workSpec
        assertTrue(workSpec.constraints.requiresBatteryNotLow())
        assertFalse(workSpec.expedited)
        assertEquals("raw-1", workSpec.input.getString(CommitmentExtractionWorker.KEY_RAW_EVENT_ID))
        assertTrue(request.captured.tags.contains(WorkSchedulerRequests.TAG_COMMITMENT_EXTRACTION))
    }

    @Test
    fun `AUTH-009 cancelAll cancels dynamic extraction and voice work by tag`() {
        every { workManager.cancelUniqueWork(any()) } returns operation
        every { workManager.cancelAllWorkByTag(any()) } returns operation

        WorkSchedulerImpl(appContext, logger).cancelAll()

        verify(exactly = 1) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.TAG_VOICE_UPLOAD)
        }
        verify(exactly = 1) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.TAG_COMMITMENT_EXTRACTION)
        }
    }

    @Test
    fun `startup legacy cleanup does not cancel live commitment extraction work by tag`() {
        every { workManager.cancelUniqueWork(any()) } returns operation
        every { workManager.cancelAllWorkByTag(any()) } returns operation

        WorkSchedulerImpl(appContext, logger).cleanupLegacyWorkNames()

        verify(exactly = 2) {
            workManager.cancelUniqueWork(any())
        }
        verify(exactly = 0) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.TAG_COMMITMENT_EXTRACTION)
        }
    }
}
