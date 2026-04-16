package com.becalm.android.data.remote.msgraph

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.days

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

// ─── Moshi JSON DTOs (private; not exposed outside this file) ─────────────────

/** Top-level Graph list/delta response envelope. */
@JsonClass(generateAdapter = true)
private data class GraphListDto<T>(
    @Json(name = "value") val value: List<T>,
    @Json(name = "@odata.nextLink") val nextLink: String?,
    @Json(name = "@odata.deltaLink") val deltaLink: String?,
)

/** Graph message DTO matching the `$select` projection. */
@JsonClass(generateAdapter = true)
private data class GraphMessageDto(
    @Json(name = "id") val id: String,
    @Json(name = "internetMessageId") val internetMessageId: String?,
    @Json(name = "subject") val subject: String?,
    @Json(name = "from") val from: GraphRecipientDto?,
    @Json(name = "bodyPreview") val bodyPreview: String?,
    @Json(name = "receivedDateTime") val receivedDateTime: String,
)

/** Graph `from` / `sender` nested object. */
@JsonClass(generateAdapter = true)
private data class GraphRecipientDto(
    @Json(name = "emailAddress") val emailAddress: GraphEmailAddressDto?,
)

/** Graph `emailAddress` object nested inside a recipient. */
@JsonClass(generateAdapter = true)
private data class GraphEmailAddressDto(
    @Json(name = "address") val address: String?,
    @Json(name = "name") val name: String?,
)

/** Graph calendar event DTO. */
@JsonClass(generateAdapter = true)
private data class GraphEventDto(
    @Json(name = "id") val id: String,
    @Json(name = "subject") val subject: String?,
    @Json(name = "start") val start: GraphDateTimeDto?,
    @Json(name = "end") val end: GraphDateTimeDto?,
    @Json(name = "location") val location: GraphLocationDto?,
    @Json(name = "attendees") val attendees: Any?,
)

/**
 * Graph `dateTime` block. The `dateTime` field is an ISO-8601 local datetime string;
 * `timeZone` is a Windows time zone name (e.g. "UTC", "Pacific Standard Time").
 * We parse only the `dateTime` field — Graph always returns UTC for the `dateTime`
 * value when `timeZone == "UTC"`, which is the default projection behavior.
 */
@JsonClass(generateAdapter = true)
private data class GraphDateTimeDto(
    @Json(name = "dateTime") val dateTime: String?,
    @Json(name = "timeZone") val timeZone: String?,
)

/** Graph `location` nested object. */
@JsonClass(generateAdapter = true)
private data class GraphLocationDto(
    @Json(name = "displayName") val displayName: String?,
)

// ─── Implementation ───────────────────────────────────────────────────────────

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

private const val INITIAL_MESSAGES_URL =
    "https://graph.microsoft.com/v1.0/me/messages/delta" +
        "?\$select=id,internetMessageId,subject,from,bodyPreview,receivedDateTime"

private const val INITIAL_EVENTS_URL =
    "https://graph.microsoft.com/v1.0/me/events/delta" +
        "?\$select=id,subject,start,end,location,attendees"

private const val INITIAL_CALENDAR_VIEW_DELTA_URL =
    "https://graph.microsoft.com/v1.0/me/calendarView/delta" +
        "?\$select=id,subject,start,end,attendees"

/**
 * OkHttp-direct implementation of [MsGraphClient].
 *
 * Uses the application-scoped [OkHttpClient] for transport and Moshi for JSON parsing.
 * No Retrofit service interface is used — MS Graph's delta endpoints return opaque full URLs
 * as `@odata.nextLink` / `@odata.deltaLink` values, which do not map cleanly to Retrofit's
 * base-URL-relative model.
 *
 * ## Token injection
 * A `Bearer` `Authorization` header is added manually from [tokenProvider]. The application
 * OkHttp interceptor chain ([com.becalm.android.data.remote.interceptor.AuthInterceptor]) is
 * host-guarded to the Railway backend and will pass Graph requests through untouched.
 *
 * @param okHttpClient Shared application OkHttp client.
 * @param moshi Shared Moshi instance with BeCalm adapters registered.
 * @param tokenProvider Microsoft Graph token source (stub until Round 6 MSAL lands).
 */
public class MsGraphClientImpl(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val tokenProvider: MsGraphTokenProvider,
) : MsGraphClient {

    private val messageListAdapter by lazy {
        moshi.adapter(GraphListDto::class.java)
    }

    private val eventListAdapter by lazy {
        moshi.adapter(GraphListDto::class.java)
    }

    override suspend fun messagesDelta(
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphMessage>> {
        val url = deltaOrNextLink ?: INITIAL_MESSAGES_URL
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }

        return try {
            @Suppress("UNCHECKED_CAST")
            val dto = messageListAdapter.fromJson(rawJson) as? GraphListDto<Map<String, Any?>>
                ?: return BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null DTO for messages delta")))

            val messages = dto.value.map { parseMessageMap(it) }
            BecalmResult.Success(
                GraphDeltaResponse(
                    value = messages,
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        } catch (e: Exception) {
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    override suspend fun eventsDelta(
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphCalendarEvent>> {
        val url = deltaOrNextLink ?: INITIAL_EVENTS_URL
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }

        return try {
            @Suppress("UNCHECKED_CAST")
            val dto = eventListAdapter.fromJson(rawJson) as? GraphListDto<Map<String, Any?>>
                ?: return BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null DTO for events delta")))

            val events = dto.value.map { parseEventMap(it) }
            BecalmResult.Success(
                GraphDeltaResponse(
                    value = events,
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        } catch (e: Exception) {
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    override suspend fun calendarViewDelta(
        cursor: String?,
    ): BecalmResult<CalendarViewDeltaPage> {
        // When cursor is null this is the initial sync request. Build the URL dynamically with
        // a 30-day-back to 90-day-forward window; Graph returns HTTP 400 without these params.
        val url = cursor ?: run {
            val start = (Clock.System.now() - 30.days).toString()
            val end = (Clock.System.now() + 90.days).toString()
            "$INITIAL_CALENDAR_VIEW_DELTA_URL&startDateTime=$start&endDateTime=$end"
        }
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }

        return try {
            @Suppress("UNCHECKED_CAST")
            val dto = eventListAdapter.fromJson(rawJson) as? GraphListDto<Map<String, Any?>>
                ?: return BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null DTO for calendarView delta")))

            val events = dto.value.map { parseEventMap(it) }
            BecalmResult.Success(
                CalendarViewDeltaPage(
                    value = events,
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        } catch (e: Exception) {
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    // ─── HTTP fetch ───────────────────────────────────────────────────────────

    /**
     * Issues a GET request to [url] with a Bearer token and returns the raw response body
     * string, or a typed [BecalmError] for non-2xx / transport failures.
     */
    private suspend fun fetchRaw(url: String): BecalmResult<String> {
        val token = tokenProvider.getAccessToken()
            ?: return BecalmResult.Failure(BecalmError.Unauthorized)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                when (response.code) {
                    in 200..299 -> BecalmResult.Success(body)
                    401 -> BecalmResult.Failure(BecalmError.Unauthorized)
                    410 -> BecalmResult.Failure(
                        BecalmError.NotFound("MS Graph delta token expired — full re-sync required"),
                    )
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                        BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
                    }
                    in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(response.code, body.take(200)))
                    else -> BecalmResult.Failure(BecalmError.Network(response.code, body.take(200)))
                }
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Network(-1, e.message ?: "network I/O failure"))
            } catch (e: Exception) {
                BecalmResult.Failure(BecalmError.Unknown(e))
            }
        }
    }

    // ─── Map → domain model parsers ───────────────────────────────────────────

    /**
     * Converts a raw deserialized map (Moshi's untyped `Any` path) into a [GraphMessage].
     * All fields except `id` and `receivedDateTime` are treated as optional.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseMessageMap(raw: Map<String, Any?>): GraphMessage {
        val fromObj = raw["from"] as? Map<String, Any?>
        val emailObj = fromObj?.get("emailAddress") as? Map<String, Any?>
        return GraphMessage(
            id = raw["id"] as? String ?: "",
            internetMessageId = raw["internetMessageId"] as? String,
            subject = raw["subject"] as? String,
            fromEmail = emailObj?.get("address") as? String,
            fromName = emailObj?.get("name") as? String,
            bodyPreview = raw["bodyPreview"] as? String,
            receivedDateTime = parseInstantField(raw["receivedDateTime"]),
        )
    }

    /**
     * Converts a raw deserialized map into a [GraphCalendarEvent].
     * Start and end are parsed from the Graph `dateTime` + `timeZone` sub-objects.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseEventMap(raw: Map<String, Any?>): GraphCalendarEvent {
        val startObj = raw["start"] as? Map<String, Any?>
        val endObj = raw["end"] as? Map<String, Any?>
        val locationObj = raw["location"] as? Map<String, Any?>

        val attendeesJson = try {
            moshi.adapter(Any::class.java).toJson(raw["attendees"])
        } catch (_: Exception) {
            null
        }

        return GraphCalendarEvent(
            id = raw["id"] as? String ?: "",
            subject = raw["subject"] as? String,
            start = parseGraphDateTime(startObj),
            end = parseGraphDateTime(endObj),
            location = locationObj?.get("displayName") as? String,
            attendeesRaw = attendeesJson,
        )
    }

    /**
     * Parses a Graph `{ dateTime: "...", timeZone: "UTC" }` map into an [Instant].
     * Falls back to [Instant.DISTANT_PAST] on any parse failure so a single malformed
     * event does not abort the whole page.
     */
    private fun parseGraphDateTime(obj: Map<String, Any?>?): Instant {
        val raw = obj?.get("dateTime") as? String ?: return Instant.DISTANT_PAST
        return try {
            // Graph returns ISO-8601 local datetime without a trailing 'Z' when timeZone == "UTC".
            // Append 'Z' if absent so kotlinx.datetime can parse it.
            val normalized = if (raw.endsWith("Z")) raw else "${raw}Z"
            Instant.parse(normalized)
        } catch (_: Exception) {
            Instant.DISTANT_PAST
        }
    }

    /** Parses a nullable Graph datetime string field (already carries 'Z' for mail). */
    private fun parseInstantField(raw: Any?): Instant {
        val str = raw as? String ?: return Instant.DISTANT_PAST
        return try {
            Instant.parse(str)
        } catch (_: Exception) {
            Instant.DISTANT_PAST
        }
    }
}
