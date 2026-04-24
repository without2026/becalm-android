package com.becalm.android.data.remote.msgraph

import android.util.Log
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.getOrElse
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.days
import javax.inject.Inject

// ─── Moshi JSON DTOs (internal for Moshi codegen — generic classes can't be private) ─────────

/** Top-level Graph list/delta response envelope. */
@JsonClass(generateAdapter = true)
internal data class GraphListDto<T>(
    @Json(name = "value") val value: List<T>,
    @Json(name = "@odata.nextLink") val nextLink: String?,
    @Json(name = "@odata.deltaLink") val deltaLink: String?,
)

// ─── Endpoint URLs ────────────────────────────────────────────────────────────

private const val TAG = "MsGraphClient"

/**
 * `$select` field list shared by INBOX / SENT initial delta calls. Plan §5.1 /
 * §5 Appendix require: `id,internetMessageId,conversationId,parentFolderId,subject,
 * from,toRecipients,ccRecipients,bccRecipients,body,hasAttachments,
 * internetMessageHeaders,receivedDateTime`. Kept here as a single constant so both
 * folder endpoints emit the identical projection — Graph invalidates the delta token
 * if the `$select` value differs across the batch.
 */
private const val MESSAGES_DELTA_SELECT =
    "id,internetMessageId,conversationId,parentFolderId,subject,from," +
        "toRecipients,ccRecipients,bccRecipients,body,hasAttachments," +
        "internetMessageHeaders,receivedDateTime"

private const val INITIAL_CALENDAR_VIEW_DELTA_URL =
    "https://graph.microsoft.com/v1.0/me/calendarView/delta" +
        "?\$select=id,subject,start,end,attendees"

/**
 * How far back the initial delta sync reaches on a cold start (ING-013 bound).
 * Applied via `$filter=receivedDateTime ge <now-30d>Z` on the cursor == null call
 * only — Graph invalidates delta tokens if filters mutate on subsequent pages.
 */
private const val DELTA_INITIAL_LOOKBACK_DAYS: Int = 30

/**
 * Builds the initial `/me/mailFolders/{folder}/messages/delta` URL.
 * Factored out so both [MsGraphClientImpl.messagesDelta] (deprecated, INBOX-only
 * backward-compat path) and [MsGraphClientImpl.messagesDeltaForFolder] share one
 * URL template and `$select` projection.
 */
private fun initialMessagesUrl(folder: OutlookMailFolder): String {
    val lookbackStart = (Clock.System.now() - DELTA_INITIAL_LOOKBACK_DAYS.days).toString()
    return "https://graph.microsoft.com/v1.0/me/mailFolders/${folder.endpointPath}/messages/delta" +
        "?\$select=$MESSAGES_DELTA_SELECT" +
        "&\$filter=receivedDateTime ge $lookbackStart"
}

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
public class MsGraphClientImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val tokenProvider: MsGraphTokenProvider,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MsGraphClient {

    // Single adapter instance — both message and event endpoints share the same envelope shape.
    private val deltaListAdapter: JsonAdapter<GraphListDto<Map<String, Any?>>> by lazy {
        val mapType = Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Any::class.java,
        )
        val envelopeType = Types.newParameterizedType(GraphListDto::class.java, mapType)
        moshi.adapter<GraphListDto<Map<String, Any?>>>(envelopeType)
    }

    @Suppress("DEPRECATION")
    @Deprecated(
        message = "Use messagesDeltaForFolder(OutlookMailFolder, cursor). Global /me/messages/delta" +
            " violates ING-007 folder scoping.",
        replaceWith = ReplaceWith(
            expression = "messagesDeltaForFolder(OutlookMailFolder.INBOX, deltaOrNextLink)",
        ),
    )
    override suspend fun messagesDelta(
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphMessage>> =
        // Backward-compat shim: all new call sites use messagesDeltaForFolder directly. The
        // INBOX delegate preserves the prior behaviour for any external caller that still
        // references this symbol; it was never Sent-aware, so narrowing to INBOX is a
        // strict subset of the historical semantics (Drafts/Junk/Deleted are excluded
        // rather than included, which is the desired direction per ING-007).
        messagesDeltaForFolder(OutlookMailFolder.INBOX, deltaOrNextLink)

    override suspend fun messagesDeltaForFolder(
        folder: OutlookMailFolder,
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphMessage>> {
        val url = resolveDeltaUrl(deltaOrNextLink) { initialMessagesUrl(folder) }
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }
        val folderLabel = folder.name // "INBOX" | "SENT" — mirrors FOLDER_INBOX/FOLDER_SENT
        return parseDeltaDto(rawJson, "messages delta (${folder.name})") { dto ->
            BecalmResult.Success(
                GraphDeltaResponse(
                    value = dto.value.map { parseMessageMap(it, folderLabel) },
                    nextLink = dto.nextLink,
                    deltaLink = dto.deltaLink,
                ),
            )
        }
    }

    override suspend fun messageAttachments(
        messageId: String,
    ): BecalmResult<List<GraphAttachmentMeta>> =
        fetchAttachments(attachmentsBaseUrl + attachmentsPath(messageId))

    /**
     * Test seam — the production call path [messageAttachments] assembles the URL from
     * [attachmentsBaseUrl]; tests that use MockWebServer invoke this directly with a
     * localhost URL so the parsing + HTTP status mapping can be asserted without
     * network access.
     */
    internal suspend fun fetchAttachments(url: String): BecalmResult<List<GraphAttachmentMeta>> {
        val rawJson = fetchRaw(url).getOrElse { return BecalmResult.Failure(it) }
        return parseDeltaDto(rawJson, "message attachments") { dto ->
            BecalmResult.Success(dto.value.map { parseAttachmentMap(it) })
        }
    }

    /**
     * Base URL for the Graph attachments endpoint. `internal` + `var` so tests can
     * redirect to a MockWebServer port; production code never mutates this.
     */
    internal var attachmentsBaseUrl: String = "https://graph.microsoft.com/v1.0"

    private fun attachmentsPath(messageId: String): String =
        "/me/messages/$messageId/attachments?\$select=name,contentType,size"

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

        return withContext(ioDispatcher) {
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    mapHttpStatus(response, body)
                }
            } catch (e: IOException) {
                BecalmResult.Failure(BecalmError.Network(-1, e.message ?: "network I/O failure"))
            } catch (e: Exception) {
                BecalmResult.Failure(BecalmError.Unknown(e))
            }
        }
    }

    /**
     * Maps an OkHttp [response] + already-consumed [body] into a typed [BecalmResult].
     *
     * Branch preservation (keep byte-identical error strings / error types):
     *  - 2xx → Success(body)
     *  - 401 → Unauthorized
     *  - 410 → NotFound("MS Graph delta token expired — full re-sync required")
     *  - 429 → RateLimited(retryAfter) from `Retry-After` header (seconds)
     *  - 5xx → ServerError(code, body.take(200))
     *  - other → Network(code, body.take(200))
     */
    private fun mapHttpStatus(response: Response, body: String): BecalmResult<String> =
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

    // ─── Map → domain model parsers ───────────────────────────────────────────

    /**
     * Converts a raw deserialized map (Moshi's untyped `Any` path) into a [GraphMessage].
     * All fields except `id` and `receivedDateTime` are treated as optional so a single
     * malformed response key does not poison the whole page.
     *
     * Folder scope is injected by the caller because Graph does not embed it in the
     * message payload — the `/me/mailFolders/{folder}/messages/delta` endpoint *is* the
     * folder signal per ING-007.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseMessageMap(raw: Map<String, Any?>, folder: String): GraphMessage {
        val fromObj = raw["from"] as? Map<String, Any?>
        val emailObj = fromObj?.get("emailAddress") as? Map<String, Any?>

        // ── Recipients ────────────────────────────────────────────────────────
        val toList = extractRecipientAddresses(raw["toRecipients"])
        val ccList = extractRecipientAddresses(raw["ccRecipients"])
        val bccList = extractRecipientAddresses(raw["bccRecipients"])

        // ── Body split by contentType ─────────────────────────────────────────
        val bodyObj = raw["body"] as? Map<String, Any?>
        val bodyContentType = (bodyObj?.get("contentType") as? String)?.lowercase()
        val bodyContent = bodyObj?.get("content") as? String
        val bodyHtml = if (bodyContentType == "html") bodyContent else null
        val bodyPlain = if (bodyContentType == "text") bodyContent else null

        // ── Headers: extract In-Reply-To / References + preserve raw JSON ─────
        val headersRaw = raw["internetMessageHeaders"] as? List<Map<String, Any?>>
        val inReplyTo = findHeader(headersRaw, "In-Reply-To")
        val references = findHeader(headersRaw, "References")
        val rawHeadersJson = serializeHeadersJson(headersRaw)

        return GraphMessage(
            id = raw["id"] as? String ?: "",
            internetMessageId = raw["internetMessageId"] as? String,
            subject = raw["subject"] as? String,
            fromEmail = emailObj?.get("address") as? String,
            fromName = emailObj?.get("name") as? String,
            // Historical field — kept for callers that still read the short preview
            // (e.g., Wave 1 OutlookMailWorker before the EmailSnippetBuilder migration).
            bodyPreview = raw["bodyPreview"] as? String,
            receivedDateTime = parseInstantField(raw["receivedDateTime"]),
            folder = folder,
            toRecipients = toList,
            ccRecipients = ccList,
            bccRecipients = bccList,
            bodyHtml = bodyHtml,
            bodyPlain = bodyPlain,
            // Attachment metadata is never inlined in the delta payload — the worker issues
            // a follow-up `/me/messages/{id}/attachments` call, gated on hasAttachments.
            attachmentsMeta = emptyList(),
            inReplyTo = inReplyTo,
            references = references,
            rawHeadersJson = rawHeadersJson,
            hasAttachments = raw["hasAttachments"] as? Boolean == true,
            conversationId = raw["conversationId"] as? String,
        )
    }

    /**
     * Extracts `emailAddress.address` strings from a Graph recipient array.
     *
     * Graph returns recipients as `[{ emailAddress: { address: "...", name: "..." } }, ...]`;
     * malformed entries (missing `emailAddress` nested map or a non-string `address`) are
     * silently dropped rather than aborting the whole page.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractRecipientAddresses(rawRecipients: Any?): List<String> {
        val list = rawRecipients as? List<Map<String, Any?>> ?: return emptyList()
        return list.mapNotNull { entry ->
            val email = entry["emailAddress"] as? Map<String, Any?>
            email?.get("address") as? String
        }
    }

    /**
     * Case-insensitive lookup of a single `internetMessageHeaders` entry by name.
     * Returns the first non-null `value`, or null when the header is absent / blank.
     */
    private fun findHeader(headers: List<Map<String, Any?>>?, name: String): String? {
        if (headers == null) return null
        val lower = name.lowercase()
        return headers.firstOrNull { entry ->
            (entry["name"] as? String)?.lowercase() == lower
        }?.get("value") as? String
    }

    /**
     * Serialises the raw `internetMessageHeaders` array back to JSON for verbatim storage
     * in [com.becalm.android.data.local.db.entity.EmailBodyEntity.rawHeaders]. Falls back
     * to `"[]"` on any serialisation error — the empty JSON array is a well-formed default
     * that downstream parsers can tolerate.
     */
    private fun serializeHeadersJson(headers: List<Map<String, Any?>>?): String =
        try {
            if (headers.isNullOrEmpty()) {
                "[]"
            } else {
                moshi.adapter(Any::class.java).toJson(headers)
            }
        } catch (e: Exception) {
            Log.w(TAG, "serializeHeadersJson failed", e)
            "[]"
        }

    /**
     * Converts a single `/me/messages/{id}/attachments` entry into a [GraphAttachmentMeta].
     * `size` is decoded as a [Long] to accommodate attachments over 2 GiB. Missing fields
     * fall back to empty string / zero — Graph's `$select=name,contentType,size` projection
     * always populates all three in practice.
     */
    private fun parseAttachmentMap(raw: Map<String, Any?>): GraphAttachmentMeta {
        val size = when (val rawSize = raw["size"]) {
            is Number -> rawSize.toLong()
            is String -> rawSize.toLongOrNull() ?: 0L
            else -> 0L
        }
        return GraphAttachmentMeta(
            name = raw["name"] as? String ?: "",
            contentType = raw["contentType"] as? String ?: "",
            sizeBytes = size,
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
        } catch (e: Exception) {
            Log.w(TAG, "parseGraphDateTime failed raw=${raw.take(40)}", e)
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
