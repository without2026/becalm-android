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

// ─── Endpoint URLs ────────────────────────────────────────────────────────────

private const val INITIAL_MESSAGES_URL =
    "https://graph.microsoft.com/v1.0/me/messages/delta" +
        "?\$select=id,internetMessageId,subject,from,bodyPreview,receivedDateTime"

private const val INITIAL_EVENTS_URL =
    "https://graph.microsoft.com/v1.0/me/events/delta" +
        "?\$select=id,subject,start,end,location,attendees"

private const val INITIAL_CALENDAR_VIEW_DELTA_URL =
    "https://graph.microsoft.com/v1.0/me/calendarView/delta" +
        "?\$select=id,subject,start,end,attendees"

// ─── Implementation ───────────────────────────────────────────────────────────

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

    // Single adapter instance — both message and event endpoints share the same envelope shape.
    private val deltaListAdapter by lazy {
        moshi.adapter(GraphListDto::class.java)
    }

    override suspend fun messagesDelta(
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphMessage>> {
        val url = resolveDeltaUrl(deltaOrNextLink) { INITIAL_MESSAGES_URL }
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }
        return parseDeltaDto(rawJson, "messages delta") { dto ->
            BecalmResult.Success(
                GraphDeltaResponse(
                    value = dto.value.map { parseMessageMap(it) },
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        }
    }

    override suspend fun eventsDelta(
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphCalendarEvent>> {
        val url = resolveDeltaUrl(deltaOrNextLink) { INITIAL_EVENTS_URL }
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }
        return parseDeltaDto(rawJson, "events delta") { dto ->
            BecalmResult.Success(
                GraphDeltaResponse(
                    value = dto.value.map { parseEventMap(it) },
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        }
    }

    override suspend fun calendarViewDelta(
        cursor: String?,
    ): BecalmResult<CalendarViewDeltaPage> {
        // When cursor is null this is the initial sync request. Build the URL dynamically with
        // a 30-day-back to 90-day-forward window; Graph returns HTTP 400 without these params.
        val url = resolveDeltaUrl(cursor) {
            val start = (Clock.System.now() - 30.days).toString()
            val end = (Clock.System.now() + 90.days).toString()
            "$INITIAL_CALENDAR_VIEW_DELTA_URL&startDateTime=$start&endDateTime=$end"
        }
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }
        return parseDeltaDto(rawJson, "calendarView delta") { dto ->
            BecalmResult.Success(
                CalendarViewDeltaPage(
                    value = dto.value.map { parseEventMap(it) },
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        }
    }

    /**
     * delta 요청 URL 선택 로직을 3개 메서드에서 한 곳으로 추출.
     * cursor(= nextLink/deltaLink)가 있으면 그대로, 없으면 initial 람다로 초기 URL을 생성한다.
     * 보존 포인트: Graph 엔드포인트/쿼리 파라미터($select, $deltatoken 등)는 호출부의 initial 블록에서만 구성한다.
     */
    private fun resolveDeltaUrl(cursor: String?, initial: () -> String): String = cursor ?: initial()

    /**
     * Deserialises [rawJson] into [GraphListDto] and delegates to [block] for domain mapping.
     * Centralises the null-DTO guard and catch-all exception wrapping shared by all three
     * public delta methods.
     *
     * @param context Human-readable label included in the error message when the DTO is null.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> parseDeltaDto(
        rawJson: String,
        context: String,
        block: (GraphListDto<Map<String, Any?>>) -> BecalmResult<T>,
    ): BecalmResult<T> = try {
        val dto = deltaListAdapter.fromJson(rawJson) as? GraphListDto<Map<String, Any?>>
            ?: return BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null DTO for $context")))
        block(dto)
    } catch (e: Exception) {
        BecalmResult.Failure(BecalmError.Unknown(e))
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

    // 아래 두 파서를 하나로 합치지 않는 이유:
    //  1) 입력 타입이 다르다 — parseGraphDateTime은 { dateTime, timeZone } 중첩 맵을,
    //     parseInstantField는 평탄한 문자열(Any?)을 받는다.
    //  2) 정규화 로직이 다르다 — Graph 이벤트의 dateTime은 timeZone == "UTC"일 때도 'Z'가 빠진 채로 오므로
    //     수동으로 'Z'를 붙여야 하지만, 메일의 receivedDateTime은 이미 'Z'가 포함된 ISO-8601 문자열이다.
    //  둘을 합치면 호출부에서 타입 분기 또는 불필요한 정규화 비용이 생겨 오히려 복잡해진다.

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
