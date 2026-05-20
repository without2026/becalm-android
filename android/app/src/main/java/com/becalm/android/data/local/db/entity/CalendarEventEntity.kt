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
 * Indices:
 * 1. `(user_id, start_at)` — primary timeline query backing [CalendarEventDao.observeInRange].
 *    Defined by `.spec/contracts/data-model.yml`.
 *
 * Logical foreign key (not enforced at the Room layer because `auth.users` is a Supabase
 * Auth-managed table that Android never mirrors locally):
 *   [userId] -> auth.users.id (many-to-one, on_delete: cascade).
 * See `.spec/contracts/data-model.yml` `relationships` section.
 *
 * Valid [sourceType] values: "google_calendar" | "outlook_calendar"
 * Valid [syncStatus] values: "pending" | "synced" | "failed"
 */
@Entity(
    tableName = "calendar_events",
    indices = [
        Index(value = ["user_id", "start_at"]),
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
     * Dedup is performed by Railway on the server side; Room does not enforce a
     * local uniqueness constraint on `(user_id, source_type, source_ref)`.
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

    /** Provider-local start value. All-day events store YYYY-MM-DD. */
    @ColumnInfo(name = "start_local")
    val startLocal: String? = null,

    /** Provider-local end value. All-day events store YYYY-MM-DD. */
    @ColumnInfo(name = "end_local")
    val endLocal: String? = null,

    /** Provider/calendar timezone used to interpret local values. */
    @ColumnInfo(name = "time_zone")
    val timeZone: String? = null,

    /** True when this is an all-day provider calendar event. */
    @ColumnInfo(name = "is_all_day", defaultValue = "0")
    val isAllDay: Boolean = false,

    /**
     * Raw attendee list as stored by Railway.
     * Format is source-dependent (e.g. comma-separated email addresses).
     * Null when no attendees are recorded.
     */
    @ColumnInfo(name = "attendees_raw")
    val attendeesRaw: String?,

    /** Provider event status. Valid values: "confirmed" | "cancelled" | "tentative". */
    @ColumnInfo(name = "status", defaultValue = "confirmed")
    val status: String = "confirmed",

    /** Provider free/busy availability. Distinct from event status. */
    @ColumnInfo(name = "availability")
    val availability: String? = null,

    /** Provider location text, if present. */
    @ColumnInfo(name = "location")
    val location: String? = null,

    /** Provider-side last modified timestamp, if exposed by the calendar API. */
    @ColumnInfo(name = "provider_updated_at")
    val providerUpdatedAt: Instant? = null,

    /** Structured attendee facts serialized as JSON for calendar-origin events. */
    @ColumnInfo(name = "attendees_json")
    val attendeesJson: String? = null,

    /** Structured organizer fact serialized as JSON for calendar-origin events. */
    @ColumnInfo(name = "organizer_json")
    val organizerJson: String? = null,

    /** Provider recurrence payload serialized as JSON when present. */
    @ColumnInfo(name = "recurrence_json")
    val recurrenceJson: String? = null,

    /** Original recurring-instance start instant when exposed by the provider. */
    @ColumnInfo(name = "original_start_at")
    val originalStartAt: Instant? = null,

    /** Hash of the provider payload used by reconciliation to detect changes. */
    @ColumnInfo(name = "provider_payload_hash")
    val providerPayloadHash: String? = null,

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
