package com.becalm.android.data.remote.gmail

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

// ─── Auth token provider ─────────────────────────────────────────────────────

/**
 * Provides a Google OAuth2 bearer token for Gmail API requests.
 *
 * Concrete implementation: [com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl].
 * Bound in [com.becalm.android.core.di.AuthModule]. Scope: `gmail.readonly` (ING-006 MVP).
 *
 * [currentToken] is synchronous because it is called on the OkHttp dispatcher thread
 * inside [GmailClientImpl]'s request-building path. It must not block on network I/O.
 * Returns `null` when the user has not connected their Google account.
 */
public interface GoogleAuthTokenProvider {
    /** Returns the current Google OAuth2 access token, or `null` when not signed in. */
    public fun currentToken(): String?
}

// ─── Wire types (private to this file) ───────────────────────────────────────

/**
 * One entry in the `history.list` response messages-added list.
 *
 * @param id Gmail message ID (string-encoded uint64).
 */
@JsonClass(generateAdapter = true)
internal data class GmailHistoryMessage(
    @Json(name = "id") val id: String,
)

/**
 * Wrapper around a `messages` sub-array within a `history.list` history record.
 *
 * @param messagesAdded Null when this history record contains no message additions.
 */
@JsonClass(generateAdapter = true)
internal data class GmailHistoryRecord(
    @Json(name = "messagesAdded") val messagesAdded: List<GmailHistoryMessageAdded>?,
)

/**
 * One element of the `messagesAdded` array.
 *
 * @param message The partial message stub; only `id` is used for incremental sync.
 */
@JsonClass(generateAdapter = true)
internal data class GmailHistoryMessageAdded(
    @Json(name = "message") val message: GmailHistoryMessage,
)

/**
 * Paginated response from `history.list`.
 *
 * @param history       List of history records; absent (null) when there are no changes.
 * @param nextPageToken Continuation token; null when this is the last page.
 * @param historyId     The current mailbox historyId to persist as the next cursor.
 */
@JsonClass(generateAdapter = true)
internal data class GmailHistoryListResponse(
    @Json(name = "history") val history: List<GmailHistoryRecord>?,
    @Json(name = "nextPageToken") val nextPageToken: String?,
    @Json(name = "historyId") val historyId: String?,
)

/**
 * One entry in the `messages.list` response (minimal stub for full-sync pagination).
 *
 * @param id Gmail message ID.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessageStub(
    @Json(name = "id") val id: String,
)

/**
 * Paginated response from `messages.list`.
 *
 * @param messages      List of message stubs; absent (null) when the mailbox is empty.
 * @param nextPageToken Continuation token; null on the last page.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessageListResponse(
    @Json(name = "messages") val messages: List<GmailMessageStub>?,
    @Json(name = "nextPageToken") val nextPageToken: String?,
)

/**
 * A single message payload header entry (e.g. "From", "Subject").
 *
 * @param name  Header name.
 * @param value Header value.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessageHeader(
    @Json(name = "name") val name: String,
    @Json(name = "value") val value: String,
)

/**
 * The `payload` sub-object of a full `messages.get` response, carrying headers.
 *
 * @param headers List of message headers. May be empty but never null per Gmail API spec.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessagePayload(
    @Json(name = "headers") val headers: List<GmailMessageHeader>,
)

// ─── Public domain types ──────────────────────────────────────────────────────

/**
 * Represents a page of results from `history.list`.
 *
 * @param messageIds    Deduplicated set of message IDs that were added in this history slice.
 * @param nextPageToken Continuation token for the next page; null on the last page.
 * @param historyId     Latest `historyId` to store as the cursor after this page is persisted.
 */
public data class GmailHistoryPage(
    val messageIds: List<String>,
    val nextPageToken: String?,
    val historyId: String?,
)

/**
 * Represents a page of results from `messages.list` (used for full-sync).
 *
 * @param messageIds    Message IDs on this page.
 * @param nextPageToken Continuation token; null on the last page.
 */
public data class GmailMessagePage(
    val messageIds: List<String>,
    val nextPageToken: String?,
)

/**
 * Gmail system labels the cold-start sync is scoped to. EMAIL-001 requires both
 * inbound and sent mail to populate the `raw_ingestion_events.folder` direction
 * hint during first-run backfill; [INBOX] drives the inbound side,
 * [SENT] drives the sent side.
 */
public enum class GmailLabel(
    /** The exact `labelIds` query value Gmail expects on `messages.list`. */
    internal val wire: String,
) {
    INBOX("INBOX"),
    SENT("SENT"),
}

/**
 * A fully resolved Gmail message with the fields needed to build a
 * [com.becalm.android.data.local.db.entity.RawIngestionEventEntity].
 *
 * @param messageId    The message's stable Gmail ID (`messages.get` `id` field).
 * @param subject      Value of the `Subject` header; null when absent.
 * @param from         Raw value of the `From` header; null when absent.
 * @param snippet      Gmail-generated short preview of the message body.
 * @param internalDate Epoch milliseconds when Gmail received the message.
 */
public data class GmailMessage(
    val messageId: String,
    val subject: String?,
    val from: String?,
    /**
     * Raw `To` header value. Parsed from the message's metadata headers so the
     * worker can derive `personRef` from the recipient when the message is in
     * the `SENT` label (sender-side view — `from` is the signed-in user's own
     * address for sent mail and would produce self-as-counterparty rows).
     * Null when the header is absent.
     */
    val to: String?,
    val snippet: String?,
    val internalDate: Long,
    /**
     * The Gmail system labels applied to this message (e.g. `INBOX`, `SENT`, `DRAFT`).
     * EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`) uses this to derive the
     * `raw_ingestion_events.folder` direction hint — "INBOX" → recipient view,
     * "SENT" → sender view. Empty when Gmail omits `labelIds` from the response.
     */
    val labelIds: List<String> = emptyList(),
)

// ─── Interface ────────────────────────────────────────────────────────────────

/**
 * HTTP client for Google's Gmail v1 REST API.
 *
 * Uses [OkHttpClient] directly (rather than Retrofit) because the Gmail API response
 * shapes differ meaningfully per endpoint. All JSON parsing is done with Moshi.
 *
 * ## Error mapping
 * - 401 → [BecalmError.Unauthorized] (user must re-auth via SP-38)
 * - 404 → [BecalmError.NotFound] (historyId has expired; caller falls back to full-sync)
 * - 410 → [BecalmError.NotFound] (historyId gone; same fallback)
 * - 429 → [BecalmError.RateLimited] with parsed `Retry-After` seconds
 * - HTTP 5xx → [BecalmError.ServerError]
 * - Network / IO → [BecalmError.Network]
 * - Other non-2xx → [BecalmError.Network] with HTTP code
 */
public interface GmailClient {

    /**
     * Fetches a page of Gmail history records since [startHistoryId].
     *
     * Maps to `GET /gmail/v1/users/me/history?startHistoryId=…&historyTypes=messageAdded`.
     *
     * @param startHistoryId Cursor from the previous successful sync (a string-encoded uint64).
     * @return [BecalmResult.Success] with [GmailHistoryPage], or a typed [BecalmError].
     *   Returns [BecalmError.NotFound] on HTTP 404 / 410 (expired historyId).
     */
    public suspend fun listHistory(startHistoryId: String): BecalmResult<GmailHistoryPage>

    /**
     * Fetches a page of message IDs scoped to a single Gmail system label (`INBOX` or
     * `SENT`) — the cold-start backfill primitive.
     *
     * Maps to `GET /gmail/v1/users/me/messages?labelIds=<label>&pageToken=…`.
     *
     * @param label Gmail system label to filter on. Use [GmailLabel.INBOX] for inbound
     *   mail and [GmailLabel.SENT] for sent mail; the EMAIL-001 direction hint
     *   (`.spec/email-pipeline.spec.yml:15-18`) requires both sides of the mailbox
     *   to reach ingestion during first-run sync.
     * @param pageToken Pagination token from the previous page; null for the first page.
     * @return [BecalmResult.Success] with [GmailMessagePage], or a typed [BecalmError].
     */
    public suspend fun listMessagesFullSync(
        label: GmailLabel,
        pageToken: String?,
    ): BecalmResult<GmailMessagePage>

    /**
     * Fetches a single message by [messageId] in `format=metadata` (headers + snippet only).
     *
     * Maps to `GET /gmail/v1/users/me/messages/{messageId}?format=metadata&metadataHeaders=From&metadataHeaders=Subject`.
     *
     * @param messageId The Gmail message ID (string-encoded uint64).
     * @return [BecalmResult.Success] with [GmailMessage], or a typed [BecalmError].
     */
    public suspend fun getMessage(messageId: String): BecalmResult<GmailMessage>
}

// ─── Implementation ───────────────────────────────────────────────────────────

private const val GMAIL_BASE_URL = "https://gmail.googleapis.com/gmail/v1/users/me"

/**
 * Production [GmailClient] backed by raw [OkHttpClient] + Moshi.
 *
 * SP-38 will provide the Hilt `@Provides` binding for this class once
 * [GoogleAuthTokenProvider] has a concrete implementation. Until then this class
 * can be instantiated directly in tests.
 *
 * @param okHttpClient Shared OkHttpClient (unauthenticated; bearer token is injected
 *   per-request from [authTokenProvider]).
 * @param moshi        Application-scoped Moshi instance.
 * @param authTokenProvider Source of the Google OAuth2 access token.
 */
public class GmailClientImpl(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private val authTokenProvider: GoogleAuthTokenProvider,
) : GmailClient {

    private val historyListAdapter by lazy {
        moshi.adapter(GmailHistoryListResponse::class.java)
    }
    private val messageListAdapter by lazy {
        moshi.adapter(GmailMessageListResponse::class.java)
    }
    private val messageGetAdapter by lazy {
        moshi.adapter(GmailMessageGetResponse::class.java)
    }

    override suspend fun listHistory(startHistoryId: String): BecalmResult<GmailHistoryPage> {
        val url = "$GMAIL_BASE_URL/history" +
            "?startHistoryId=$startHistoryId" +
            "&historyTypes=messageAdded"
        return executeRequest(url) { body ->
            parseOrFail(historyListAdapter, body, "history.list") { parsed ->
                val messageIds = parsed.history
                    ?.flatMap { record -> record.messagesAdded.orEmpty() }
                    ?.map { added -> added.message.id }
                    ?.distinct()
                    ?: emptyList()
                GmailHistoryPage(
                    messageIds = messageIds,
                    nextPageToken = parsed.nextPageToken,
                    historyId = parsed.historyId,
                )
            }
        }
    }

    override suspend fun listMessagesFullSync(
        label: GmailLabel,
        pageToken: String?,
    ): BecalmResult<GmailMessagePage> {
        val url = buildString {
            append("$GMAIL_BASE_URL/messages?labelIds=${label.wire}")
            if (pageToken != null) append("&pageToken=$pageToken")
        }
        return executeRequest(url) { body ->
            parseOrFail(messageListAdapter, body, "messages.list") { parsed ->
                GmailMessagePage(
                    messageIds = parsed.messages?.map { it.id } ?: emptyList(),
                    nextPageToken = parsed.nextPageToken,
                )
            }
        }
    }

    override suspend fun getMessage(messageId: String): BecalmResult<GmailMessage> {
        val url = "$GMAIL_BASE_URL/messages/$messageId" +
            "?format=metadata" +
            "&metadataHeaders=From" +
            "&metadataHeaders=To" +
            "&metadataHeaders=Subject"
        return executeRequest(url) { body ->
            parseOrFail(messageGetAdapter, body, "messages.get") { parsed ->
                val headers = parsed.payload?.headers.orEmpty()
                GmailMessage(
                    messageId = parsed.id,
                    subject = headers.firstOrNull { it.name.equals("Subject", ignoreCase = true) }?.value,
                    from = headers.firstOrNull { it.name.equals("From", ignoreCase = true) }?.value,
                    to = headers.firstOrNull { it.name.equals("To", ignoreCase = true) }?.value,
                    snippet = parsed.snippet,
                    internalDate = parsed.internalDate,
                    labelIds = parsed.labelIds.orEmpty(),
                )
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * 세 엔드포인트(history.list / messages.list / messages.get)에서 반복되던
     * "adapter.fromJson → null이면 Unknown 실패, 아니면 매핑해서 Success" 패턴을 한 곳으로 모은다.
     * 원본 `BecalmError.Unknown(IllegalStateException("null body from <label>"))` 페이로드 형태를 그대로 보존한다.
     */
    private inline fun <T, R> parseOrFail(
        adapter: JsonAdapter<T>,
        body: String,
        label: String,
        map: (T) -> R,
    ): BecalmResult<R> {
        val parsed = adapter.fromJson(body)
            ?: return BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("null body from $label")),
            )
        return BecalmResult.Success(map(parsed))
    }

    /**
     * Executes an authenticated GET request, maps HTTP error codes to [BecalmError],
     * and delegates body parsing to [parseBody].
     *
     * Returns [BecalmError.Unauthorized] for 401 — the token was revoked or expired.
     * Returns [BecalmError.NotFound] for 404 / 410 — historyId expired; caller must full-sync.
     * Returns [BecalmError.RateLimited] for 429, parsing `Retry-After` when present.
     * Returns [BecalmError.Network] for other non-2xx codes or [IOException].
     */
    private suspend fun <T> executeRequest(
        url: String,
        parseBody: (String) -> BecalmResult<T>,
    ): BecalmResult<T> {
        val token = authTokenProvider.currentToken()
            ?: return BecalmResult.Failure(BecalmError.Unauthorized)

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                mapResponse(response, parseBody)
            }
        } catch (e: IOException) {
            BecalmResult.Failure(BecalmError.Network(-1, e.message ?: "network error"))
        }
    }

    private fun <T> mapResponse(
        response: Response,
        parseBody: (String) -> BecalmResult<T>,
    ): BecalmResult<T> = when (response.code) {
        in 200..299 -> {
            val bodyString = response.body?.string() ?: ""
            parseBody(bodyString)
        }
        401 -> BecalmResult.Failure(BecalmError.Unauthorized)
        404, 410 -> BecalmResult.Failure(BecalmError.NotFound("gmail_history"))
        429 -> {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
        }
        in 500..599 -> BecalmResult.Failure(
            BecalmError.ServerError(response.code, response.body?.string()),
        )
        else -> BecalmResult.Failure(
            BecalmError.Network(response.code, response.message),
        )
    }
}

// ─── Internal response shape for messages.get ────────────────────────────────

/**
 * Full `messages.get` response envelope (format=metadata).
 *
 * @param id           The message's stable Gmail ID.
 * @param snippet      Gmail-generated short body preview.
 * @param internalDate Epoch milliseconds of message receipt (string in the wire format).
 * @param payload      Message part containing headers.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessageGetResponse(
    @Json(name = "id") val id: String,
    @Json(name = "snippet") val snippet: String?,
    @Json(name = "internalDate") val internalDate: Long,
    @Json(name = "payload") val payload: GmailMessagePayload?,
    /**
     * Gmail system labels (`INBOX`, `SENT`, `DRAFT`, …). Included so EMAIL-001 can
     * resolve the folder direction hint without a second API round-trip.
     */
    @Json(name = "labelIds") val labelIds: List<String>? = null,
)
