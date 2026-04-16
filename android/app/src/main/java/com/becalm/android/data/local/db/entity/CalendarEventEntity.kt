package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Room entity mirroring the `calendar_events` Supabase table.
 *
 * This table is the local cache for calendar events synced from Railway.
 * Android never writes directly to Supabase; all remote writes flow through
 * the Railway batch API. [syncStatus] tracks whether a locally-staged mutation
 * has been acknowledged by Railway.
 *
 * Two indices are maintained:
 * 1. `(user_id, start_at)` — primary timeline query backing [CalendarEventDao.observeUpcoming]
 *    and [CalendarEventDao.observeInRange].
 * 2. `(user_id, source_type, source_ref)` unique — backs upsert-by-external-ref dedup in
 *    [CalendarEventDao.findBySourceRef]. Room treats NULLs in a UNIQUE index as distinct,
 *    so rows with `source_ref = null` do not conflict with each other.
 *
 * Valid [sourceType] values: "google_calendar" | "outlook_calendar"
 * Valid [syncStatus] values: "pending" | "synced" | "failed"
 */
@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["user_id", "start_at"]),
        Index(value = ["user_id", "source_type", "source_ref"], unique = true),
    ],
)
public data class CalendarEventEntity(

    /** Supabase-assigned UUID primary key. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** Supabase auth.users UUID of the owning user. */
    @ColumnInfo(name = "user_id")
    val userId: String,

    /**
     * Calendar source that produced this event.
     * Valid values: "google_calendar" | "outlook_calendar"
     */
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    /**
     * External calendar event ID used for server-side upsert deduplication.
     * Null when the calendar provider does not expose a stable external ID.
     * Room treats NULL values in the unique index on (user_id, source_type, source_ref)
     * as distinct, so multiple null-ref rows for the same user are permitted.
     */
    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,

    /** Calendar event title / summary. */
    @ColumnInfo(name = "title")
    val title: String,

    /** Event start timestamp (UTC). */
    @ColumnInfo(name = "start_at")
    val startAt: Instant,

    /** Event end timestamp (UTC). */
    @ColumnInfo(name = "end_at")
    val endAt: Instant,

    /**
     * Raw attendee list as stored by Railway.
     * Format is source-dependent (e.g. comma-separated email addresses).
     * Null when no attendees are recorded.
     */
    @ColumnInfo(name = "attendees_raw")
    val attendeesRaw: String?,

    /**
     * Railway/Room sync status for this record.
     * Valid values: "pending" | "synced" | "failed"
     *
     * Defaults to "pending" so newly inserted rows are picked up by the
     * next sync worker pass without requiring an explicit value at insert time.
     */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "pending",
)
