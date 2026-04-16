package com.becalm.android.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.becalm.android.data.local.DataStoreKeys
import com.becalm.android.data.local.dao.RawIngestionEventDao
import com.becalm.android.data.local.entities.RawIngestionEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// spec: ING-001 — ContentObserver for Samsung Voice Recorder (real-time, process-alive only)
// spec: ING-011 — ON_START foreground catch-up (PRIMARY 100%-arrival path)
// spec: ING-012 — per-source cursor persistence in DataStore
// Phase 5 debate conclusion: Hybrid Option A — ContentObserver + foreground catch-up
// ContentObserver adds real-time detection when process is alive;
// ING-011 cursor catch-up guarantees 100%-arrival on next foreground.
// content_observer_fire_rate metric should be instrumented in production to validate Option A.

@Singleton
class VoiceIngestionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val dataStore: DataStore<Preferences>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var contentObserver: ContentObserver? = null

    // Metric: track ContentObserver fires for production monitoring (Phase 5 kill criterion)
    private var contentObserverFireCount = 0L

    // spec: ING-001 — register ContentObserver for Samsung Voice Recorder MediaStore URI
    // Called from lifecycle-aware component (LifecycleObserver or similar)
    fun registerContentObserver(safUri: Uri?) {
        val observerUri = safUri ?: MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        contentObserver?.let { unregisterContentObserver() }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // spec: ING-001 — ContentObserver fires when new .m4a file saved
                // Process is alive (foreground or background) at this point
                contentObserverFireCount++
                scope.launch {
                    handleNewVoiceFile()
                }
            }
        }
        context.contentResolver.registerContentObserver(observerUri, true, observer)
        contentObserver = observer
    }

    fun unregisterContentObserver() {
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    // spec: ING-011 — PRIMARY path: ON_START catch-up using MediaStore timestamp cursor
    // Called on every foreground entry (ON_START lifecycle event)
    suspend fun performForegroundCatchUp(): List<RawIngestionEvent> {
        val prefs = dataStore.data.first()
        val lastCursor = prefs[DataStoreKeys.CURSOR_VOICE] ?: 0L
        val safUri = prefs[DataStoreKeys.VOICE_SAF_URI]?.let { Uri.parse(it) }

        val newEvents = queryMediaStoreSince(lastCursor, safUri)
        if (newEvents.isEmpty()) return emptyList()

        rawIngestionEventDao.insertAll(newEvents)

        // spec: ING-012 — update cursor to latest timestamp
        val latestTimestamp = newEvents.maxOf { it.timestamp }
        dataStore.edit { it[DataStoreKeys.CURSOR_VOICE] = latestTimestamp }

        return newEvents
    }

    // spec: ING-001 — also used by ContentObserver (process-alive path)
    private suspend fun handleNewVoiceFile() {
        val prefs = dataStore.data.first()
        val lastCursor = prefs[DataStoreKeys.CURSOR_VOICE] ?: 0L
        val safUri = prefs[DataStoreKeys.VOICE_SAF_URI]?.let { Uri.parse(it) }

        val newEvents = queryMediaStoreSince(lastCursor, safUri)
        if (newEvents.isEmpty()) return

        rawIngestionEventDao.insertAll(newEvents)

        val latestTimestamp = newEvents.maxOf { it.timestamp }
        dataStore.edit { it[DataStoreKeys.CURSOR_VOICE] = latestTimestamp }
    }

    // spec: ING-012 — query MediaStore since last cursor (epoch millis DATE_MODIFIED)
    private fun queryMediaStoreSince(sinceTimestampMillis: Long, safUri: Uri?): List<RawIngestionEvent> {
        val sinceSeconds = sinceTimestampMillis / 1000  // MediaStore uses seconds
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED, // seconds epoch
            MediaStore.Audio.Media.DURATION,      // milliseconds
            MediaStore.Audio.Media.DATA
        )

        // Filter: .m4a files (Samsung Voice Recorder format) modified after cursor
        val selection = "${MediaStore.Audio.Media.DATE_MODIFIED} > ? AND " +
                "${MediaStore.Audio.Media.MIME_TYPE} = ?"
        val selectionArgs = arrayOf(sinceSeconds.toString(), "audio/mp4")

        val cursor = context.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_MODIFIED} ASC"
        ) ?: return emptyList()

        val events = mutableListOf<RawIngestionEvent>()
        cursor.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val modifiedCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val durationCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (it.moveToNext()) {
                val mediaId = it.getLong(idCol)
                val name = it.getString(nameCol) ?: "Recording"
                val modifiedSeconds = it.getLong(modifiedCol)
                val durationMs = it.getLong(durationCol)
                val uri = ContentUris.withAppendedId(collection, mediaId)

                events.add(
                    RawIngestionEvent(
                        sourceType = RawIngestionEvent.SourceType.VOICE,
                        sourceRef = uri.toString(),
                        eventTitle = name,
                        durationSeconds = (durationMs / 1000).toInt().takeIf { d -> d > 0 },
                        timestamp = modifiedSeconds * 1000, // convert to millis
                        syncStatus = RawIngestionEvent.SyncStatus.PENDING
                    )
                )
            }
        }
        return events
    }

    // Metric accessor for monitoring (spec Phase 5 kill criterion)
    fun getContentObserverFireCount(): Long = contentObserverFireCount
}
