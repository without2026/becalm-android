package com.becalm.android.data.remote.gcal

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * A single Google Calendar event as it would be returned by the Google Calendar API
 * `events.list` endpoint.
 *
 * This model is intentionally decoupled from [com.becalm.android.data.remote.dto.CalendarEventDto]
 * (the Railway wire DTO) so that a future direct-API path can normalise fields independently
 * before mapping to the Room entity. See [GCalClient] for the current production path.
 *
 * @property id          Stable Google-assigned event ID; used as the deduplication key.
 * @property htmlLink    Browser-viewable link to this event in Google Calendar, or `null`.
 * @property summary     Event title / summary string, or `null` for untitled events.
 * @property location    Free-text location string, or `null` when absent.
 * @property start       Parsed event start as a UTC [Instant].
 * @property end         Parsed event end as a UTC [Instant].
 * @property attendeesJson Raw JSON array of attendee objects from the `attendees` field;
 *                         `null` when no attendees are present. Decoded downstream by a
 *                         dedicated mapper (SP-26.1 follow-up).
 * @property updatedAt   Timestamp of the most recent modification to this event.
 */
public data class GCalEvent(
    val id: String,
    val htmlLink: String?,
    val summary: String?,
    val location: String?,
    val start: Instant,
    val end: Instant,
    val attendeesJson: String?,
    val updatedAt: Instant,
)

/**
 * Outcome of a single `events.list` API call, supporting both incremental and paginated sync.
 *
 * The Google Calendar API uses two orthogonal token types:
 * - **syncToken** (`nextSyncToken`): returned on the final page of a complete sync pass.
 *   Supply it on the next call to fetch only events changed since that pass.
 * - **pageToken** (`nextPageToken`): returned when the result set spans multiple pages within
 *   a single pass. Supply it to continue reading the current pass.
 *
 * At any point exactly one of [nextSyncToken] or [nextPageToken] is non-null; the caller
 * must check [nextPageToken] first and continue paging before storing [nextSyncToken] as
 * a cursor.
 *
 * @property events         Events returned in this page.
 * @property nextSyncToken  Opaque cursor for the next incremental sync pass; non-null only
 *                          on the last page of a complete pass. Store via
 *                          [com.becalm.android.data.local.datastore.SyncCursorStore].
 * @property nextPageToken  Within-batch page continuation token; non-null when more pages
 *                          exist in the current pass.
 */
public data class GCalSyncResult(
    val events: List<GCalEvent>,
    val nextSyncToken: String?,
    val nextPageToken: String?,
)

/**
 * Forward-compat client interface for direct Google Calendar API access.
 *
 * ## Current production path
 * Android does **not** call the Google Calendar API directly in the MVP. The production
 * sync flow is:
 * 1. [com.becalm.android.worker.ingestion.GoogleCalendarWorker] calls
 *    [com.becalm.android.data.repository.CalendarEventRepository.triggerServerSync] to
 *    tell Railway to pull the user's calendar via the user's server-stored OAuth token.
 * 2. The worker then calls [com.becalm.android.data.repository.CalendarEventRepository.refreshSince]
 *    to pull the canonicalised rows back into Room.
 *
 * ## Future direct-API path (SP-26.1)
 * This interface is provided so a follow-up sprint can wire [GCalClientImpl] (not yet
 * written) for offline catch-up or latency-sensitive flows without changing
 * [GoogleCalendarWorker]'s call site. The Hilt binding belongs in a dedicated
 * `GCalModule` — see the `// TODO(SP-06 augment)` comment below.
 *
 * @see GCalClientStub
 */
public interface GCalClient {

    /**
     * Fetches a page of events from Google Calendar for the given [calendarId].
     *
     * Pass a **syncToken** as [syncTokenOrPageToken] to fetch only events changed since the
     * last completed pass (incremental sync). Pass a **pageToken** to continue an in-progress
     * pass. Pass `null` for a full initial fetch.
     *
     * @param calendarId           Google Calendar ID (e.g. `"primary"`).
     * @param syncTokenOrPageToken Opaque token from a previous [GCalSyncResult]; `null` for
     *                             a full initial fetch.
     * @return [BecalmResult.Success] with a [GCalSyncResult], or a typed failure.
     */
    public suspend fun listEvents(
        calendarId: String,
        syncTokenOrPageToken: String?,
    ): BecalmResult<GCalSyncResult>
}

// TODO(SP-06 augment): Add a GCalModule that binds GCalClientStub → GCalClient.
// Do not edit RepositoryModule or NetworkModule from this SP — those are out of scope.
// When SP-26.1 lands a real implementation, replace the binding target with GCalClientImpl
// and inject the Google Sign-In credential via a dedicated qualifier.

/**
 * Placeholder [GCalClient] that always returns a typed failure.
 *
 * Present so that the Hilt graph can resolve [GCalClient] as soon as a binding is
 * declared in a follow-up module, and so that callers have a well-defined contract to
 * test against. The stub is never called by [com.becalm.android.worker.ingestion.GoogleCalendarWorker]
 * because the current production flow goes through Railway, not the Google API directly.
 *
 * Replace with a real implementation in SP-26.1 if direct-API catch-up is required.
 */
public class GCalClientStub @Inject constructor() : GCalClient {

    override suspend fun listEvents(
        calendarId: String,
        syncTokenOrPageToken: String?,
    ): BecalmResult<GCalSyncResult> =
        BecalmResult.Failure(
            BecalmError.Unknown(
                UnsupportedOperationException(
                    "Direct Google Calendar fetch not yet implemented — " +
                        "server-side sync via Railway is the current production path (SP-26.1 follow-up)",
                ),
            ),
        )
}
