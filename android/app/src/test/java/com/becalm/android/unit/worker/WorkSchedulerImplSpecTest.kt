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
import com.becalm.android.worker.ColdSyncWorkInputs
import com.becalm.android.worker.UniqueWorkKeys
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.WorkSchedulerImpl
import com.becalm.android.worker.WorkSchedulerRequests
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
        every { workManager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>()) } returns operation
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

        WorkSchedulerImpl(appContext, logger).enqueueUpload(attempt = 0)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(capture(workName), capture(policy), capture(request))
        }
        assertEquals("sync-all-upload", workName.captured)
        assertEquals(ExistingWorkPolicy.REPLACE, policy.captured)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(0, request.captured.workSpec.input.getInt("attempt", -1))
        assertEquals(WorkSchedulerRequests.UPLOAD_DEBOUNCE_SECONDS * 1_000L, request.captured.workSpec.initialDelay)
    }

    @Test
    fun `retry upload attempts are not debounced`() {
        val request = slot<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueueUpload(attempt = 2)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(any(), any(), capture(request))
        }
        assertEquals(2, request.captured.workSpec.input.getInt("attempt", -1))
        assertEquals(0L, request.captured.workSpec.initialDelay)
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
    fun `backend mail sync is enrolled as 15 minute connected periodic work`() {
        val workName = slot<String>()
        val policy = slot<ExistingPeriodicWorkPolicy>()
        val request = slot<PeriodicWorkRequest>()

        WorkSchedulerImpl(appContext, logger).scheduleBackendMailSync()

        verify(exactly = 1) {
            workManager.enqueueUniquePeriodicWork(capture(workName), capture(policy), capture(request))
        }
        assertEquals("ingest.backend_mail", workName.captured)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, policy.captured)
        assertEquals(NetworkType.CONNECTED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals(15 * 60 * 1000L, request.captured.workSpec.intervalDuration)
    }

    @Test
    fun `person index work is debounced by default and can be forced immediate`() {
        val workName = mutableListOf<String>()
        val request = mutableListOf<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueuePersonInteractionIndex()
        WorkSchedulerImpl(appContext, logger).enqueuePersonInteractionIndex(initialDelaySeconds = 0L)

        verify(exactly = 2) {
            workManager.enqueueUniqueWork(capture(workName), any(), capture(request))
        }
        assertEquals(listOf(UniqueWorkKeys.PERSON_INDEX, UniqueWorkKeys.PERSON_INDEX), workName)
        assertEquals(WorkScheduler.PERSON_INDEX_DEBOUNCE_SECONDS * 1_000L, request[0].workSpec.initialDelay)
        assertEquals(0L, request[1].workSpec.initialDelay)
    }

    @Test
    fun `profile memory work is person scoped and can run offline`() {
        val workName = slot<String>()
        val policy = slot<ExistingWorkPolicy>()
        val request = slot<OneTimeWorkRequest>()

        WorkSchedulerImpl(appContext, logger).enqueueProfileMemory(" person-1 ", initialDelaySeconds = 0L)

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(capture(workName), capture(policy), capture(request))
        }
        assertEquals(UniqueWorkKeys.profileMemory("person-1"), workName.captured)
        assertEquals(ExistingWorkPolicy.REPLACE, policy.captured)
        assertEquals(NetworkType.NOT_REQUIRED, request.captured.workSpec.constraints.requiredNetworkType)
        assertEquals("person-1", request.captured.workSpec.input.getString("person_id"))
        assertEquals(0L, request.captured.workSpec.initialDelay)
    }

    @Test
    fun `AUTH-009 cancelAll cancels voice work and stale local extraction work by tag`() {
        every { workManager.cancelUniqueWork(any()) } returns operation
        every { workManager.cancelAllWorkByTag(any()) } returns operation

        WorkSchedulerImpl(appContext, logger).cancelAll()

        verify(exactly = 1) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.TAG_VOICE_UPLOAD)
        }
        verify(exactly = 1) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.TAG_PROFILE_MEMORY)
        }
        verify(exactly = 1) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.LEGACY_TAG_COMMITMENT_EXTRACTION)
        }
    }

    @Test
    fun `startup legacy cleanup cancels stale local commitment extraction work by tag`() {
        every { workManager.cancelUniqueWork(any()) } returns operation
        every { workManager.cancelAllWorkByTag(any()) } returns operation

        WorkSchedulerImpl(appContext, logger).cleanupLegacyWorkNames()

        verify(exactly = 2) {
            workManager.cancelUniqueWork(any())
        }
        verify(exactly = 1) {
            workManager.cancelAllWorkByTag(WorkSchedulerRequests.LEGACY_TAG_COMMITMENT_EXTRACTION)
        }
    }
}
