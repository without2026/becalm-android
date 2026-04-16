package com.becalm.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// spec: data-model — calendar_events Room entity
// Mirrors Supabase calendar_events table.

@Entity(tableName = "calendar_events")
data class CalendarEvent(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    // spec: ING-009, ING-010 — google_calendar | outlook_calendar
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    // External calendar event ID for upsert deduplication
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,

    @ColumnInfo(name = "title")
    val title: String,

    // epoch millis
    @ColumnInfo(name = "start_at")
    val startAt: Long,

    @ColumnInfo(name = "end_at")
    val endAt: Long,

    // Raw JSON string of attendees list
    @ColumnInfo(name = "attendees_raw")
    val attendeesRaw: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING
) {
    object SyncStatus {
        const val PENDING = "pending"
        const val SYNCED = "synced"
    }
}
