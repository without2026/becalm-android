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
import com.becalm.android.data.local.dao.CalendarEventDao
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.CalendarEvent
import com.becalm.android.data.local.entities.RawIngestionEvent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

// spec: ING-009 — [주기적 보조 경로] Google Calendar sync (1시간 주기)
// spec: ING-010 — Outlook Calendar sync
// spec: ING-012 — cursor: Google = events.list syncToken; Outlook = events/delta deltaLink
// spec: ING-013 — 410 stale syncToken → cursor reset + 30-day re-sync

@HiltWorker
class CalendarSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val calendarEventDao: CalendarEventDao,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val dataStore: DataStore<Preferences>
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORK_NAME_GOOGLE = "sync-google-calendar"
        const val WORK_NAME_OUTLOOK = "sync-outlook-calendar"

        // spec: ING-009 — 1-hour periodic sync for Google Calendar
        fun scheduleGoogleCalendarWork(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setInputData(androidx.work.Data.Builder()
                    .putString("source", "google_calendar")
                    .build())
                .addTag(WORK_NAME_GOOGLE)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_GOOGLE,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun scheduleOutlookCalendarWork(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setInputData(androidx.work.Data.Builder()
                    .putString("source", "outlook_calendar")
                    .build())
                .addTag(WORK_NAME_OUTLOOK)
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_OUTLOOK,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val source = inputData.getString("source") ?: return Result.failure()
        val prefs = dataStore.data.first()

        return when (source) {
            "google_calendar" -> {
                if (prefs[DataStoreKeys.GOOGLE_CALENDAR_CONNECTED] != true) return Result.success()
                syncGoogleCalendar(prefs)
            }
            "outlook_calendar" -> {
                if (prefs[DataStoreKeys.OUTLOOK_CALENDAR_CONNECTED] != true) return Result.success()
                syncOutlookCalendar(prefs)
            }
            else -> Result.failure()
        }
    }

    // TODO(ING-013): scaffold only — full Google Calendar OAuth impl in follow-up sprint
    private suspend fun syncGoogleCalendar(prefs: Preferences): Result {
        // Full implementation requires:
        // 1. Retrieve OAuth token from Keystore
        // 2. Call events.list(syncToken=syncToken) or events.list(timeMin=30daysAgo) if no token
        // spec: ING-013 — 410 → reset cursor + 30-day re-sync
        // 3. Insert CalendarEvent + RawIngestionEvent into Room
        // 4. Update DataStore cursor_google_calendar = new syncToken
        throw NotImplementedError("CalendarSyncWorker.syncGoogleCalendar: implementation pending (TODO ING-013)")
    }

    // TODO(ING-013): scaffold only — full Outlook Calendar OAuth impl in follow-up sprint
    private suspend fun syncOutlookCalendar(prefs: Preferences): Result {
        // Full implementation requires:
        // 1. Retrieve OAuth token from Keystore
        // 2. GET /me/calendarView/delta?deltaToken=... or initial request
        // spec: ING-013 — 410 → reset cursor + 30-day re-sync
        // 3. Insert CalendarEvent + RawIngestionEvent into Room
        // 4. Update DataStore cursor_outlook_calendar = new deltaLink
        throw NotImplementedError("CalendarSyncWorker.syncOutlookCalendar: implementation pending (TODO ING-013)")
    }

    // spec: ING-009, ING-010 — create Room entities from calendar event data
    private fun createCalendarEntities(
        sourceType: String,
        externalId: String,
        title: String,
        startAtMillis: Long,
        endAtMillis: Long,
        location: String?,
        attendeesRaw: String?
    ): Pair<CalendarEvent, RawIngestionEvent> {
        val clientEventId = UUID.randomUUID().toString()
        val calEvent = CalendarEvent(
            id = clientEventId,
            sourceType = sourceType,
            sourceRef = externalId,
            title = title,
            startAt = startAtMillis,
            endAt = endAtMillis,
            attendeesRaw = attendeesRaw,
            syncStatus = CalendarEvent.SyncStatus.PENDING
        )
        val ingestionEvent = RawIngestionEvent(
            clientEventId = UUID.randomUUID().toString(),
            sourceType = sourceType,
            sourceRef = externalId,
            eventTitle = title,
            location = location,
            timestamp = startAtMillis,
            syncStatus = RawIngestionEvent.SyncStatus.PENDING
        )
        return Pair(calEvent, ingestionEvent)
    }
}
