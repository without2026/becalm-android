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

    // gmailClient is overridable for testing; production uses NoOpGmailClient until SP-4
    internal var gmailClient: GmailClient = NoOpGmailClient

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
        return syncGmail(lastHistoryId)
    }

    // Internal: injectable for testing (spec: ING-012 / ING-013)
    suspend fun syncGmail(lastHistoryId: String?): Result {
        return if (lastHistoryId == null) {
            // Cold start: no cursor → full 30-day sync
            performFullResync()
        } else {
            // spec: ING-012 — incremental via history.list(startHistoryId)
            val fetchResult = gmailClient.fetchHistory(lastHistoryId)
            when (fetchResult) {
                is GmailFetchResult.Messages -> {
                    persistMessages(fetchResult.messages)
                    if (fetchResult.newHistoryId != null) {
                        persistCursor(fetchResult.newHistoryId)
                    }
                    Result.success()
                }
                is GmailFetchResult.Gone -> {
                    // spec: ING-013 — 410 historyId expiry → reset cursor + 30-day re-sync
                    dataStore.edit { it.remove(DataStoreKeys.CURSOR_GMAIL) }
                    performFullResync()
                }
                is GmailFetchResult.Error -> Result.retry()
            }
        }
    }

    // spec: ING-013 — 30-day full re-sync after cursor expiry
    private suspend fun performFullResync(): Result {
        val thirtyDaysAgoSec = (System.currentTimeMillis() / 1000L) - (30 * 24 * 3600L)
        val fetchResult = gmailClient.fetchMessagesSince(thirtyDaysAgoSec)
        return when (fetchResult) {
            is GmailFetchResult.Messages -> {
                persistMessages(fetchResult.messages)
                if (fetchResult.newHistoryId != null) {
                    persistCursor(fetchResult.newHistoryId)
                }
                Result.success()
            }
            is GmailFetchResult.Gone -> Result.failure() // unexpected on full resync
            is GmailFetchResult.Error -> Result.retry()
        }
    }

    private suspend fun persistMessages(messages: List<GmailMessage>) {
        for (msg in messages) {
            val (event, body) = parseEmailToEvent(
                messageId = msg.messageId,
                subject = msg.subject,
                fromEmail = msg.fromEmail,
                snippetText = msg.snippet,
                bodyText = msg.bodyText,
                timestampMillis = msg.timestampMillis
            )
            rawIngestionEventDao.insert(event)   // IGNORE on duplicate client_event_id
            emailBodyDao.insert(body)
        }
    }

    private suspend fun persistCursor(newHistoryId: String) {
        dataStore.edit { it[DataStoreKeys.CURSOR_GMAIL] = newHistoryId }
    }

    // spec: ING-006 — parse email into RawIngestionEvent
    internal fun parseEmailToEvent(
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

// ---- Gmail API abstraction (injectable for testing) ----

data class GmailMessage(
    val messageId: String,
    val subject: String,
    val fromEmail: String,
    val snippet: String,
    val bodyText: String,
    val timestampMillis: Long
)

sealed class GmailFetchResult {
    data class Messages(val messages: List<GmailMessage>, val newHistoryId: String?) : GmailFetchResult()
    object Gone : GmailFetchResult()      // HTTP 410 — historyId expired
    data class Error(val code: Int) : GmailFetchResult()
}

interface GmailClient {
    // spec: ING-012 — incremental history.list
    suspend fun fetchHistory(startHistoryId: String): GmailFetchResult
    // spec: ING-013 — messages.list?q=after:{epochSec}
    suspend fun fetchMessagesSince(afterEpochSec: Long): GmailFetchResult
}

// Default no-op: replaced by real OAuth client in SP-4 implementation
private object NoOpGmailClient : GmailClient {
    override suspend fun fetchHistory(startHistoryId: String): GmailFetchResult =
        GmailFetchResult.Messages(emptyList(), null)

    override suspend fun fetchMessagesSince(afterEpochSec: Long): GmailFetchResult =
        GmailFetchResult.Messages(emptyList(), null)
}
