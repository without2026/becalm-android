package com.becalm.android.data.remote.msgraph

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import kotlinx.datetime.Instant

// ─── Domain models ────────────────────────────────────────────────────────────

/**
 * Paginated response envelope returned by MS Graph delta endpoints.
 *
 * When [nextLink] is non-null the caller must issue another request using that URL to
 * retrieve the next page within the same sync batch. When [deltaLink] is non-null the
 * batch is complete and the caller must persist that URL as the cursor for the next sync.
 * Exactly one of [nextLink] / [deltaLink] is present on any given response; both are null
 * only on parsing failure, which surfaces as a [BecalmError.Unknown].
 *
 * @param T Item type: [GraphMessage] for mail deltas, [GraphCalendarEvent] for calendar deltas.
 */
public data class GraphDeltaResponse<T>(
    val value: List<T>,
    /** `@odata.nextLink` — paging cursor within a single sync batch; null when on the last page. */
    val nextLink: String?,
    /** `@odata.deltaLink` — cursor for the next sync pass; null when more pages remain. */
    val deltaLink: String?,
)

/**
 * A single Outlook/Office 365 mail message as projected by the MS Graph messages delta endpoint.
 *
 * Only the fields selected by `$select=id,internetMessageId,subject,from,bodyPreview,receivedDateTime`
 * are mapped here; additional Graph fields are silently ignored by Moshi's default lenient mode.
 */
public data class GraphMessage(
    /** Immutable Graph-assigned message identifier. Used as `sourceRef` in ingestion. */
    val id: String,
    /** RFC 2822 Message-ID header value. May be absent for draft messages. */
    val internetMessageId: String?,
    /** Email subject line. Null for messages without a subject. */
    val subject: String?,
    /** Sender email address. Null when Graph omits the `from` field. */
    val fromEmail: String?,
    /** Sender display name. Null when Graph omits the `from` field. */
    val fromName: String?,
    /** First ~255 chars of the message body as plain text. */
    val bodyPreview: String?,
    /** When the message was received (UTC). */
    val receivedDateTime: Instant,
)

/**
 * A single Outlook/Office 365 calendar event as projected by the MS Graph events delta endpoint.
 *
 * Shared with SP-27 (OutlookCalendarWorker, Round 5). Only the fields required by both workers
 * are mapped; additional Graph fields are silently ignored.
 */
public data class GraphCalendarEvent(
    /** Immutable Graph-assigned event identifier. */
    val id: String,
    /** Event title. Null when absent. */
    val subject: String?,
    /** Start time in UTC. */
    val start: Instant,
    /** End time in UTC. */
    val end: Instant,
    /** Free-text location string. Null when not specified. */
    val location: String?,
    /**
     * Raw JSON-encoded attendee list as returned by Graph, stored verbatim.
     * SP-27 and the LLM pipeline parse this on demand rather than mapping it eagerly.
     */
    val attendeesRaw: String?,
)

/**
 * Paginated response envelope returned by the `/me/calendarView/delta` endpoint (SP-27b).
 *
 * Semantics mirror [GraphDeltaResponse]: exactly one of [nextLink] or [deltaLink] is
 * non-null on any successful response. Both null indicates a parse failure.
 *
 * @param value  List of calendar events in this page.
 * @param nextLink Cursor for the next page within the current sync batch. Non-null when
 *   more pages remain in this pass.
 * @param deltaLink Cursor to persist as the starting point for the next sync pass. Non-null
 *   when this is the last page of the current batch.
 */
public data class CalendarViewDeltaPage(
    val value: List<GraphCalendarEvent>,
    val nextLink: String?,
    val deltaLink: String?,
)

// ─── Token provider ───────────────────────────────────────────────────────────

/**
 * Supplies a valid Microsoft Graph bearer token for the current user.
 *
 * This is a stub interface — the real implementation will be provided by `AuthViewModel`
 * in Round 6 once the MSAL / Microsoft Identity integration lands. Inject a no-op or
 * test double until then.
 */
public interface MsGraphTokenProvider {
    /**
     * Returns the current Graph access token, or `null` when no Microsoft account is
     * linked (user not authenticated with MSAL).
     */
    public suspend fun getAccessToken(): String?
}

// ─── Client interface ─────────────────────────────────────────────────────────

/**
 * Low-level HTTP client for the Microsoft Graph delta APIs used by BeCalm's ingestion pipeline.
 *
 * This interface is intentionally general-purpose: it is shared between SP-25 (Outlook mail)
 * and SP-27 (Outlook calendar, Round 5). Callers are responsible for the pagination loop —
 * if [GraphDeltaResponse.nextLink] is non-null they must re-call with that URL.
 *
 * ## Error mapping
 * - HTTP 401 → [BecalmError.Unauthorized]
 * - HTTP 410 → [BecalmError.NotFound] (delta token expired; caller must clear cursor and full-sync)
 * - HTTP 429 → [BecalmError.RateLimited] with `retryAfterSeconds` parsed from the header
 * - HTTP 5xx → [BecalmError.ServerError]
 * - Network I/O failure → [BecalmError.Network]
 * - Unexpected error → [BecalmError.Unknown]
 */
public interface MsGraphClient {

    /**
     * Fetches a page of mail message deltas.
     *
     * @param deltaOrNextLink If `null`, issues the initial full sync request.
     *   If non-null, the value must be an opaque URL previously returned as
     *   [GraphDeltaResponse.nextLink] or [GraphDeltaResponse.deltaLink].
     */
    public suspend fun messagesDelta(deltaOrNextLink: String?): BecalmResult<GraphDeltaResponse<GraphMessage>>

    /**
     * Fetches a page of calendar event deltas.
     *
     * @param deltaOrNextLink Same semantics as [messagesDelta].
     */
    public suspend fun eventsDelta(deltaOrNextLink: String?): BecalmResult<GraphDeltaResponse<GraphCalendarEvent>>

    /**
     * Fetches a page of calendar event deltas via the `/me/calendarView/delta` endpoint.
     *
     * This endpoint is preferred over [eventsDelta] for time-bounded calendar sync because
     * `/me/calendarView/delta` honours `startDateTime` / `endDateTime` query parameters,
     * whereas `/me/events/delta` returns all events regardless of time window.
     *
     * Cursor semantics are identical to [messagesDelta]: pass `null` for the initial full
     * sync and the opaque URL from [CalendarViewDeltaPage.nextLink] or
     * [CalendarViewDeltaPage.deltaLink] for subsequent pages / sync passes.
     *
     * @param cursor If `null`, issues the initial full sync request.
     *   If non-null, must be a URL previously returned as [CalendarViewDeltaPage.nextLink]
     *   or [CalendarViewDeltaPage.deltaLink].
     */
    public suspend fun calendarViewDelta(cursor: String?): BecalmResult<CalendarViewDeltaPage>
}
