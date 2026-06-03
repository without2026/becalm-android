package com.becalm.android.data.remote.dto

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Json
import kotlinx.datetime.Instant

/**
 * Represents a single calendar event as returned by Railway.
 *
 * Used as items in [CalendarEventListResponse.data] for GET /v1/calendar_events.
 * Mirrors the `calendar_events` Supabase table (data-model.yml).
 *
 * @property sourceType Valid values: [SourceType.GOOGLE_CALENDAR] | [SourceType.OUTLOOK_CALENDAR]
 */
@JsonClass(generateAdapter = true)
public data class CalendarEventDto(
    /** Supabase-assigned UUID primary key. */
    @field:Json(name = "id") val id: String,

    /** Supabase auth.users UUID of the owning user. */
    @field:Json(name = "user_id") val userId: String,

    /**
     * Calendar source type.
     * Valid values: [SourceType.GOOGLE_CALENDAR] ("google_calendar") |
     * [SourceType.OUTLOOK_CALENDAR] ("outlook_calendar").
     */
    @field:Json(name = "source_type") val sourceType: String,

    /**
     * External calendar event ID used for server-side upsert deduplication.
     * Null if the calendar system does not expose a stable external ID.
     */
    @field:Json(name = "source_ref") val sourceRef: String? = null,

    /** Calendar event title / summary. */
    @field:Json(name = "title") val title: String,

    /** Event start timestamp. */
    @field:Json(name = "start_at") val startAt: Instant,

    /** Event end timestamp. */
    @field:Json(name = "end_at") val endAt: Instant,

    /** Provider-local start value. All-day events use YYYY-MM-DD. */
    @field:Json(name = "start_local") val startLocal: String? = null,

    /** Provider-local end value. All-day events use YYYY-MM-DD. */
    @field:Json(name = "end_local") val endLocal: String? = null,

    /** Provider/calendar timezone used to interpret local values. */
    @field:Json(name = "time_zone") val timeZone: String? = null,

    /** True when the provider event is an all-day calendar event. */
    @field:Json(name = "is_all_day") val isAllDay: Boolean = false,

    /**
     * Raw attendee list as stored by Railway.
     * Format is source-dependent (e.g. comma-separated email addresses).
     * Null when no attendees are recorded.
     */
    @field:Json(name = "attendees_raw") val attendeesRaw: String? = null,

    /** Provider event status. */
    @field:Json(name = "status") val status: String = "confirmed",

    /** Provider free/busy availability. Distinct from event status. */
    @field:Json(name = "availability") val availability: String? = null,

    /** Provider location text, if present. */
    @field:Json(name = "location") val location: String? = null,

    /** Provider-side last modified timestamp. */
    @field:Json(name = "provider_updated_at") val providerUpdatedAt: Instant? = null,

    /** Structured attendee facts retained only for calendar-origin events. */
    @field:Json(name = "attendees_json") val attendeesJson: List<CalendarAttendeeDto> = emptyList(),

    /** Structured organizer fact retained only for calendar-origin events. */
    @field:Json(name = "organizer_json") val organizerJson: CalendarOrganizerDto? = null,

    /** Provider recurrence payload, serialized by the server when present. */
    @field:Json(name = "recurrence") val recurrence: Any? = null,

    /** Original recurring-instance start instant when exposed by the provider. */
    @field:Json(name = "original_start_at") val originalStartAt: Instant? = null,

    /** Hash of the provider payload used by reconciliation to detect reopen-worthy changes. */
    @field:Json(name = "provider_payload_hash") val providerPayloadHash: String? = null,

)

@JsonClass(generateAdapter = true)
public data class CalendarAttendeeDto(
    @field:Json(name = "email") val email: String? = null,
    @field:Json(name = "name") val name: String? = null,
    @field:Json(name = "response_status") val responseStatus: String? = null,
    @field:Json(name = "role") val role: String? = null,
    @field:Json(name = "optional") val optional: Boolean? = null,
    @field:Json(name = "self") val self: Boolean? = null,
)

@JsonClass(generateAdapter = true)
public data class CalendarOrganizerDto(
    @field:Json(name = "email") val email: String? = null,
    @field:Json(name = "name") val name: String? = null,
    @field:Json(name = "self") val self: Boolean? = null,
)

/**
 * Paginated list response for GET /v1/calendar_events.
 *
 * Wire format: { data: CalendarEvent[], cursor: string, has_more: boolean }
 *
 * Pass [cursor] as the `cursor` query parameter on the next request when [hasMore] is true.
 * Supports incremental sync via the `since` query parameter (ISO datetime).
 */
@JsonClass(generateAdapter = true)
public data class CalendarEventListResponse(
    @field:Json(name = "data") val data: List<CalendarEventDto>,

    /**
     * Opaque pagination cursor. Pass as `cursor` query param on next request.
     * Value is undefined when [hasMore] is false; still present in response per contract.
     */
    @field:Json(name = "cursor") val cursor: String,

    /** True when additional pages exist beyond this response. */
    @field:Json(name = "has_more") val hasMore: Boolean,
)

/**
 * Response body for POST /v1/calendar_events:sync (HTTP 200).
 *
 * Wire format: { synced: int }
 *
 * Triggers a server-side sync from the connected calendar provider(s).
 * Returns 429 if rate-limited (respect Retry-After header).
 */
@JsonClass(generateAdapter = true)
public data class CalendarSyncResponse(
    /** Number of calendar events synced from upstream provider(s) during this request. */
    @field:Json(name = "synced") val synced: Int,
    @field:Json(name = "job_id") val jobId: String? = null,
    @field:Json(name = "status") val status: String? = null,
    @field:Json(name = "accepted") val accepted: Boolean = false,
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Long? = null,
    @field:Json(name = "error_code") val errorCode: String? = null,
    @field:Json(name = "error_message") val errorMessage: String? = null,
    @field:Json(name = "stage") val stage: String? = null,
    @field:Json(name = "progress") val progress: Double? = null,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "sync_mode") val syncMode: String? = null,
    @field:Json(name = "has_more_pages") val hasMorePages: Boolean = false,
    @field:Json(name = "backfill_complete") val backfillComplete: Boolean? = null,
)

/**
 * Response body for `GET /v1/oauth/calendar/{provider}:start`.
 */
@JsonClass(generateAdapter = true)
public data class CalendarOAuthStartResponse(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "authorization_url") val authorizationUrl: String,
    @field:Json(name = "redirect_uri") val redirectUri: String,
    @field:Json(name = "state") val state: String,
)

/**
 * Response body for `GET /v1/oauth/calendar/{provider}:status`.
 */
@JsonClass(generateAdapter = true)
public data class CalendarOAuthStatusResponse(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "connected") val connected: Boolean,
    @field:Json(name = "account_email") val accountEmail: String? = null,
    @field:Json(name = "display_name") val displayName: String? = null,
    @field:Json(name = "connections") val connections: List<SourceConnectionDto> = emptyList(),
)
