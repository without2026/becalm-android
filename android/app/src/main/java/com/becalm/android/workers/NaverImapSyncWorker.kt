package com.becalm.android.workers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.data.local.dao.EmailBodyDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

// spec: ING-008 — [주기적 보조 경로] 네이버 IMAP sync
// spec: ING-012 — cursor: UIDVALIDITY + last_UID composite ("UIDVALIDITY:lastUID")
// spec: ING-013 — UIDVALIDITY mismatch → cursor reset + 30-day re-sync
// Invariant: IMAP app password stored in Android Keystore, NEVER uploaded to Railway
// Invariant: EmailBody NEVER uploaded to Railway or Supabase

@HiltWorker
class NaverImapSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val emailBodyDao: EmailBodyDao,
    private val dataStore: DataStore<Preferences>
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "sync-naver-imap"

        fun schedulePeriodicWork(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<NaverImapSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .addTag(WORK_NAME)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val prefs = dataStore.data.first()
        if (prefs[DataStoreKeys.NAVER_IMAP_CONNECTED] != true) return Result.success()

        // IMAP implementation scaffold — full implementation requires:
        // 1. Retrieve IMAP app password from Android Keystore (NOT DataStore)
        // 2. Connect to imap.naver.com:993 (SSL)
        // 3. Parse cursor: "UIDVALIDITY:lastUID" composite
        // 4. EXAMINE INBOX, check UIDVALIDITY
        // spec: ING-013 — UIDVALIDITY mismatch → cursor reset + 30-day re-sync
        // 5. UID FETCH (lastUID+1:*) for new messages
        // 6. Insert RawIngestionEvent(source_type='naver_imap') + EmailBody into Room
        // 7. Update DataStore cursor_naver_imap = "newUIDVALIDITY:newLastUID"

        return Result.success()
    }

    // spec: ING-013 — reset cursor on UIDVALIDITY mismatch
    private suspend fun resetCursorAndFullSync() {
        dataStore.edit { it.remove(DataStoreKeys.CURSOR_NAVER_IMAP) }
        // Perform limited full re-sync (30 days) — scaffold placeholder
    }
}
