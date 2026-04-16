package com.becalm.android.workers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.work.ListenableWorker.Result
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.data.local.dao.PersonEnrichmentDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.ui.settings.SourceConnectionState
import com.becalm.android.ui.settings.SourceManagementViewModel
import androidx.work.WorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule

// spec: ENR-002 — CONTACTS permission denied → graceful skip, persons_enrichment stays empty
// spec: ENR-005 — EnrichmentWorker WORK_NAME + 1-day periodic scheduling constant
// spec: ENR-008 — contacts pseudo-source connected/disconnected state in SourceManagementViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class EnrichmentWorkerTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    // spec: ENR-002 — when READ_CONTACTS is denied, doWork() returns success without inserting any enrichment
    @Test
    fun `doWork returns success immediately when contacts permission is denied`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val personEnrichmentDao = mockk<PersonEnrichmentDao>(relaxed = true)
        val rawIngestionEventDao = mockk<RawIngestionEventDao>(relaxed = true)

        // Simulate permission DENIED
        every {
            context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
        } returns android.content.pm.PackageManager.PERMISSION_DENIED

        val worker = EnrichmentWorker(context, workerParams, personEnrichmentDao, rawIngestionEventDao)
        val result = worker.doWork()

        // spec: ENR-002 — app proceeds normally, persons_enrichment stays empty (no upsert called)
        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { personEnrichmentDao.upsertAll(any()) }
    }

    // spec: ENR-002 — when permission is denied, no person_refs are queried
    @Test
    fun `doWork does not query person_refs when contacts permission denied`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val personEnrichmentDao = mockk<PersonEnrichmentDao>(relaxed = true)
        val rawIngestionEventDao = mockk<RawIngestionEventDao>(relaxed = true)

        every {
            context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS)
        } returns android.content.pm.PackageManager.PERMISSION_DENIED

        val worker = EnrichmentWorker(context, workerParams, personEnrichmentDao, rawIngestionEventDao)
        worker.doWork()

        // spec: ENR-002 — graceful skip: no DAO calls at all when permission denied
        coVerify(exactly = 0) { rawIngestionEventDao.getDistinctPersonRefs() }
    }

    // spec: ENR-005 — EnrichmentWorker WORK_NAME constant is defined for periodic scheduling
    @Test
    fun `EnrichmentWorker WORK_NAME is enrichment-periodic`() {
        // spec: ENR-005 — 1-day PeriodicWorkRequest uses this tag
        assertEquals("enrichment-periodic", EnrichmentWorker.WORK_NAME)
    }

    // spec: ENR-005 — schedulePeriodicWork does not throw when WorkManager is available
    @Test
    fun `schedulePeriodicWork completes without throwing`() {
        val workManager = mockk<WorkManager>(relaxed = true)
        // spec: ENR-005 — should enqueue KEEP policy, 1-day periodic
        EnrichmentWorker.schedulePeriodicWork(workManager)
        // Verify enqueueUniquePeriodicWork was called with correct work name
        io.mockk.verify { workManager.enqueueUniquePeriodicWork(
            "enrichment-periodic",
            any(),
            any()
        ) }
    }

    // spec: ENR-008 — contacts row in SourceManagementViewModel shows CONNECTED when permission granted
    @Test
    fun `contacts source shows CONNECTED when CONTACTS_PERMISSION_GRANTED is true`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>()
        val workManager = mockk<WorkManager>(relaxed = true)

        val prefs = preferencesOf(DataStoreKeys.CONTACTS_PERMISSION_GRANTED to true)
        coEvery { dataStore.data } returns flowOf(prefs)

        val vm = SourceManagementViewModel(context, dataStore, workManager)
        advanceUntilIdle()

        val contactsRow = vm.uiState.value.sources.find { it.sourceId == "contacts" }
        // spec: ENR-008 — contacts row exists with CONNECTED state when permission granted
        assertEquals(SourceConnectionState.CONNECTED, contactsRow?.state)
    }

    // spec: ENR-008 — contacts row shows DISCONNECTED when permission not granted
    @Test
    fun `contacts source shows DISCONNECTED when contacts permission not granted`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>()
        val workManager = mockk<WorkManager>(relaxed = true)

        val prefs = preferencesOf() // no CONTACTS_PERMISSION_GRANTED key
        coEvery { dataStore.data } returns flowOf(prefs)

        val vm = SourceManagementViewModel(context, dataStore, workManager)
        advanceUntilIdle()

        val contactsRow = vm.uiState.value.sources.find { it.sourceId == "contacts" }
        // spec: ENR-008 — disconnected when READ_CONTACTS not granted
        assertEquals(SourceConnectionState.DISCONNECTED, contactsRow?.state)
    }
}
