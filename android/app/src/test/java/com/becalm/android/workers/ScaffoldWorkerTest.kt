package com.becalm.android.workers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.dao.EmailBodyDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

// spec: ING-013 — NaverImap and Calendar workers are scaffold-only until follow-up sprint
// Tests assert NotImplementedError at runtime so CI fails loudly if the scaffold is accidentally shipped.

class ScaffoldWorkerTest {

    // spec: ING-008 — NaverImapSyncWorker throws NotImplementedError when connected
    @Test
    fun `naverImapSyncWorker_throwsNotImplementedError_whenConnected`() = runTest {
        val context = mockk<android.content.Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val dao = mockk<RawIngestionEventDao>(relaxed = true)
        val emailBodyDao = mockk<EmailBodyDao>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        // Simulate NAVER_IMAP_CONNECTED = true so the guard does not exit early
        val prefs = mockk<Preferences>(relaxed = true)
        every { prefs[DataStoreKeys.NAVER_IMAP_CONNECTED] } returns true
        coEvery { dataStore.data } returns flowOf(prefs)

        val worker = NaverImapSyncWorker(context, workerParams, dao, emailBodyDao, dataStore)

        assertThrows(NotImplementedError::class.java) {
            kotlinx.coroutines.runBlocking { worker.doWork() }
        }
    }

    // spec: ING-009 — CalendarSyncWorker(google) throws NotImplementedError when connected
    @Test
    fun `calendarSyncWorker_google_throwsNotImplementedError_whenConnected`() = runTest {
        val context = mockk<android.content.Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        every { workerParams.inputData } returns androidx.work.Data.Builder()
            .putString("source", "google_calendar").build()

        val calendarEventDao = mockk<CalendarEventDao>(relaxed = true)
        val rawDao = mockk<RawIngestionEventDao>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>(relaxed = true)
        every { prefs[DataStoreKeys.GOOGLE_CALENDAR_CONNECTED] } returns true
        coEvery { dataStore.data } returns flowOf(prefs)

        val worker = CalendarSyncWorker(context, workerParams, calendarEventDao, rawDao, dataStore)

        assertThrows(NotImplementedError::class.java) {
            kotlinx.coroutines.runBlocking { worker.doWork() }
        }
    }

    // spec: ING-010 — CalendarSyncWorker(outlook) throws NotImplementedError when connected
    @Test
    fun `calendarSyncWorker_outlook_throwsNotImplementedError_whenConnected`() = runTest {
        val context = mockk<android.content.Context>(relaxed = true)
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        every { workerParams.inputData } returns androidx.work.Data.Builder()
            .putString("source", "outlook_calendar").build()

        val calendarEventDao = mockk<CalendarEventDao>(relaxed = true)
        val rawDao = mockk<RawIngestionEventDao>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val prefs = mockk<Preferences>(relaxed = true)
        every { prefs[DataStoreKeys.OUTLOOK_CALENDAR_CONNECTED] } returns true
        coEvery { dataStore.data } returns flowOf(prefs)

        val worker = CalendarSyncWorker(context, workerParams, calendarEventDao, rawDao, dataStore)

        assertThrows(NotImplementedError::class.java) {
            kotlinx.coroutines.runBlocking { worker.doWork() }
        }
    }
}
