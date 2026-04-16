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
import com.becalm.android.data.local.entities.EmailBody
import com.becalm.android.data.local.entities.RawIngestionEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

// spec: ING-006 — [주기적 보조 경로] Gmail OAuth WorkManager periodic sync
// spec: ING-012 — cursor: Gmail history.list startHistoryId
// spec: ING-013 — 410 historyId expiry → cursor reset + limited full re-sync (30 days)
// Invariant: Gmail OAuth tokens stored in Android Keystore, NEVER uploaded to Railway
// Invariant: EmailBody NEVER uploaded to Railway or Supabase

@HiltWorker
class GmailSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val emailBodyDao: EmailBodyDao,
    private val dataStore: DataStore<Preferences>
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME = "sync-gmail"

        // spec: ING-006 — 15 minute periodic sync
        fun schedulePeriodicWork(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<GmailSyncWorker>(15, TimeUnit.MINUTES)
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
        // spec: ING-006 — skip if Gmail not connected
        if (prefs[DataStoreKeys.GMAIL_CONNECTED] != true) return Result.success()

        val lastHistoryId = prefs[DataStoreKeys.CURSOR_GMAIL]

        // Gmail API integration scaffold — full implementation requires:
        // 1. Retrieve OAuth token from Android Keystore (not DataStore)
        // 2. Call Gmail API history.list(startHistoryId=lastHistoryId) or messages.list for cold start
        // 3. For each new message: fetch RFC 2822 content, parse headers for person_ref (From:)
        // 4. Insert RawIngestionEvent + EmailBody into Room
        // 5. Update DataStore cursor_gmail to new historyId

        // spec: ING-013 — 410 expiry handling (skeleton)
        // If Gmail returns 410: reset cursor to null, perform 30-day full re-sync
        // For scaffold: return success; SP-4 implementation adds actual API calls

        return Result.success()
    }

    // spec: ING-006 — parse email into RawIngestionEvent
    private fun parseEmailToEvent(
        messageId: String,
        subject: String,
        fromEmail: String,
        snippetText: String,
        bodyText: String,
        timestampMillis: Long
    ): Pair<RawIngestionEvent, EmailBody> {
        val clientEventId = UUID.randomUUID().toString()
        val event = RawIngestionEvent(
            clientEventId = clientEventId,
            sourceType = RawIngestionEvent.SourceType.GMAIL,
            sourceRef = messageId,
            personRef = fromEmail.lowercase(), // spec: data-model — email lowercase as person_ref
            eventTitle = subject,
            eventSnippet = snippetText.take(200), // spec: ING-006 — first 200 chars
            timestamp = timestampMillis,
            syncStatus = RawIngestionEvent.SyncStatus.PENDING
        )
        val body = EmailBody(
            rawIngestionId = clientEventId,
            bodyText = bodyText
        )
        return Pair(event, body)
    }
}
