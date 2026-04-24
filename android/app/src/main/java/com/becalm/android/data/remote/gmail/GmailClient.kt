package com.becalm.android.data.remote.gmail

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

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
 * The `body` sub-object of a message part, carrying either base64url-encoded inline
 * payload (for text parts) or a stand-alone attachmentId (for attachment parts that
 * must be fetched via a second `messages.attachments.get` call — currently OUT OF
 * SCOPE per EMAIL-004: body[part] bytes are never downloaded).
 *
 * @param size Declared size in bytes. Present for every part Gmail emits.
 * @param data Base64url-encoded inline bytes. Populated for text/plain and text/html
 *   parts; null for attachment parts (Gmail returns an `attachmentId` instead, which
 *   we ignore — only the metadata reaches [GmailAttachmentMeta]).
 * @param attachmentId Opaque handle for `messages.attachments.get`. Ignored here.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessagePartBody(
    @Json(name = "size") val size: Long? = null,
    @Json(name = "data") val data: String? = null,
    @Json(name = "attachmentId") val attachmentId: String? = null,
)

/**
 * One node in the recursive `payload.parts[]` MIME tree. Top-level payloads can be
 * flat (single-part messages) or nested (`multipart`). The worker walks this tree
 * in [GmailClientImpl.extractBodies] collecting the first text/plain, first
 * text/html, and every attachment descriptor.
 *
 * @param partId     Gmail-assigned identifier for the part; ignored.
 * @param mimeType   Declared MIME type (`text/plain`, `text/html`, `multipart/alternative`,
 *   `application/pdf`, …).
 * @param filename   `Content-Disposition` filename. Empty string for inline body parts;
 *   non-blank for attachments.
 * @param headers    Part-local headers; searched for `Content-Disposition: attachment`
 *   so parts without a `filename` but marked as attachments still get meta entries.
 * @param body       Inline payload (base64url) + size.
 * @param parts      Nested children for `multipart` parts; null for leaf parts.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessagePart(
    @Json(name = "partId") val partId: String? = null,
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "headers") val headers: List<GmailMessageHeader>? = null,
    @Json(name = "body") val body: GmailMessagePartBody? = null,
    @Json(name = "parts") val parts: List<GmailMessagePart>? = null,
)

/**
 * The `payload` sub-object of a full `messages.get` response.
 *
 * @param mimeType Top-level MIME type (e.g. `multipart/alternative` or `text/plain`).
 * @param filename Top-level filename. Usually empty for the root.
 * @param headers  Full header array. Emits [GmailMessage.rawHeadersJson] verbatim.
 * @param body     Inline body for single-part messages (non-multipart). Null for
 *   multipart payloads (children carry the actual bytes).
 * @param parts    Child parts for multipart payloads; null for single-part messages.
 */
@JsonClass(generateAdapter = true)
internal data class GmailMessagePayload(
    @Json(name = "mimeType") val mimeType: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "headers") val headers: List<GmailMessageHeader> = emptyList(),
    @Json(name = "body") val body: GmailMessagePartBody? = null,
    @Json(name = "parts") val parts: List<GmailMessagePart>? = null,
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
 * Gmail system labels the cold-start sync is scoped to.
 *
 * Retained only for the [GmailClient.listMessagesFullSync] backward-compatibility
 * shim (`@Deprecated` → [GmailClient.listMessagesFullSyncForLabel]). New code MUST
 * use [GmailLabelScope] which carries the full negative-filter query string.
 */
@Deprecated(
    message = "Replaced by GmailLabelScope which carries negative-filter semantics (EMAIL-001).",
    replaceWith = ReplaceWith(
        expression = "GmailLabelScope",
        imports = ["com.becalm.android.data.remote.gmail.GmailLabelScope"],
    ),
)
public enum class GmailLabel(
    /** The exact `labelIds` query value Gmail expects on `messages.list`. */
    internal val wire: String,
) {
    INBOX("INBOX"),
    SENT("SENT"),
}

/**
 * A fully resolved Gmail message with the fields needed to build a
 * [com.becalm.android.data.local.db.entity.RawIngestionEventEntity] and a matching
 * [com.becalm.android.data.local.db.entity.EmailBodyEntity].
 *
 * All body/header/attachment fields are populated from Gmail's `format=full` response
 * via [GmailClientImpl.getMessage]; the pre-EMAIL-004 `format=metadata` shape only
 * filled [messageId], [subject], [from], [to], [snippet], [internalDate], and
 * [labelIds] — every other field is new.
 *
 * @param messageId    The message's stable Gmail ID (`messages.get` `id` field).
 * @param subject      Value of the `Subject` header; null when absent.
 * @param from         Raw value of the `From` header; null when absent.
 * @param to           Raw value of the `To` header — comma-separated address list per
 *   RFC 5322. Parsed lazily by the worker via `firstRecipientEmail` for SENT personRef.
 * @param toAddresses  Tokenised recipient list (one entry per top-level comma; verbatim,
 *   not canonicalised). Empty when the `To` header is absent or parses to no tokens.
 *   Used by the worker to decide the group_email threshold (>10).
 * @param bodyPlain    First `text/plain` MIME part (base64url-decoded, UTF-8). Null when
 *   the message has no plain-text part.
 * @param bodyHtml     First `text/html` MIME part. Null when the message has no HTML part.
 * @param attachmentsMeta Descriptor list for every part Gmail surfaces with a non-blank
 *   `filename` or `Content-Disposition: attachment`. Empty when there are no attachments.
 * @param messageIdHeader Value of the RFC 2822 `Message-Id` header (case-insensitive
 *   match). Distinct from [messageId] which is the Gmail-internal primary key.
 * @param inReplyTo   Value of the `In-Reply-To` header; null when absent. Preserved in
 *   the [com.becalm.android.data.remote.email.SourceRefEnvelope] for reply threading.
 * @param references  Value of the `References` header (whitespace-separated Message-Ids);
 *   null when absent.
 * @param rawHeadersJson Verbatim JSON serialisation of Gmail's `payload.headers` array.
 *   Stored on [com.becalm.android.data.local.db.entity.EmailBodyEntity.rawHeaders] for
 *   future thread-view UIs; never uploaded (EMAIL-006 room-only).
 * @param snippet      Gmail-generated short preview of the message body. Retained for
 *   backward compatibility — callers SHOULD use
 *   [com.becalm.android.domain.email.EmailSnippetBuilder.buildSnippet] against
 *   [bodyPlain]/[bodyHtml] instead (EMAIL-003 determinism contract).
 * @param internalDate Epoch milliseconds when Gmail received the message.
 * @param labelIds     The Gmail system labels applied to this message (e.g. `INBOX`,
 *   `SENT`, `DRAFT`). EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`) uses this to
 *   derive the `raw_ingestion_events.folder` direction hint — "INBOX" → recipient view,
 *   "SENT" → sender view. Empty when Gmail omits `labelIds` from the response.
 */
public data class GmailMessage(
    val messageId: String,
    val subject: String?,
    val from: String?,
    val to: String?,
    val toAddresses: List<String> = emptyList(),
    val bodyPlain: String? = null,
    val bodyHtml: String? = null,
    val attachmentsMeta: List<GmailAttachmentMeta> = emptyList(),
    val messageIdHeader: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val rawHeadersJson: String = "[]",
    val snippet: String?,
    val internalDate: Long,
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
     * Fetches a page of message IDs scoped to [label] using Gmail search syntax.
     *
     * Maps to `GET /gmail/v1/users/me/messages?q=<URL-encoded queryString>&pageToken=…`.
     *
     * Unlike the `labelIds=` parameter this endpoint previously used, the `q=`
     * parameter supports negative filters (`-category:promotions`, `-in:spam`, …).
     * EMAIL-001 + ING-006 require the INBOX pass to exclude `CATEGORY_PROMOTIONS`,
     * `CATEGORY_SOCIAL`, `CATEGORY_UPDATES`, `CATEGORY_FORUMS`, `SPAM`, `TRASH`, and
     * `DRAFT`; the SENT pass excludes `TRASH` and `DRAFT`. Those filters are baked
     * into [GmailLabelScope.queryString].
     *
     * @param label Scope filter — INBOX (received mail, marketing excluded) or SENT
     *   (outbound mail, drafts/trash excluded).
     * @param pageToken Pagination token from the previous page; null for the first page.
     * @return [BecalmResult.Success] with [GmailMessagePage], or a typed [BecalmError].
     */
    public suspend fun listMessagesFullSyncForLabel(
        label: GmailLabelScope,
        pageToken: String?,
        lookbackDays: Int? = null,
    ): BecalmResult<GmailMessagePage>

    /**
     * Legacy entrypoint kept for binary compatibility with existing call sites that
     * already migrated to the `GmailLabel` two-pass contract. Delegates to
     * [listMessagesFullSyncForLabel] with the corresponding [GmailLabelScope]. New
     * callers MUST use [listMessagesFullSyncForLabel] directly — the scope enum
     * carries the full negative-filter query string this method cannot express.
     */
    @Deprecated(
        message = "Use listMessagesFullSyncForLabel(GmailLabelScope, pageToken) " +
            "which carries EMAIL-001 negative-filter semantics.",
        replaceWith = ReplaceWith(
            expression = "listMessagesFullSyncForLabel(GmailLabelScope.valueOf(label.name), pageToken)",
            imports = ["com.becalm.android.data.remote.gmail.GmailLabelScope"],
        ),
    )
    @Suppress("DEPRECATION")
    public suspend fun listMessagesFullSync(
        label: GmailLabel,
        pageToken: String?,
    ): BecalmResult<GmailMessagePage> =
        listMessagesFullSyncForLabel(GmailLabelScope.valueOf(label.name), pageToken)

    /**
     * Fetches a single message by [messageId] in `format=full` (full MIME tree).
     *
     * Maps to `GET /gmail/v1/users/me/messages/{messageId}?format=full`.
     *
     * Walks `payload.parts[]` to extract the first `text/plain`, the first `text/html`,
     * and every part carrying a non-blank `filename` or `Content-Disposition: attachment`
     * into [GmailMessage.attachmentsMeta]. Also reads `From` / `To` / `Subject` /
     * `Message-Id` / `In-Reply-To` / `References` headers from `payload.headers` and
     * preserves the full header array as [GmailMessage.rawHeadersJson].
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
    private val headersListAdapter: JsonAdapter<List<GmailMessageHeader>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, GmailMessageHeader::class.java))
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

    override suspend fun listMessagesFullSyncForLabel(
        label: GmailLabelScope,
        pageToken: String?,
        lookbackDays: Int?,
    ): BecalmResult<GmailMessagePage> {
        // `q=` carries Gmail search syntax (label: / -category: / -in:) so the
        // INBOX pass can exclude CATEGORY_PROMOTIONS & friends which `labelIds=`
        // cannot express. URLEncoder is required because the query contains
        // spaces and the `-` operator — Gmail rejects raw unencoded values.
        val encodedQuery = URLEncoder.encode(label.withLookbackDays(lookbackDays), Charsets.UTF_8.name())
        val url = buildString {
            append("$GMAIL_BASE_URL/messages?q=")
            append(encodedQuery)
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
        // EMAIL-004 + EMAIL-005: full payload is required so that body_plain /
        // body_html / attachments_meta / Message-Id / In-Reply-To / References
        // all reach EmailBodyEntity. `format=metadata` cannot surface parts[]
        // nor body data.
        val url = "$GMAIL_BASE_URL/messages/$messageId?format=full"
        return executeRequest(url) { body ->
            parseOrFail(messageGetAdapter, body, "messages.get") { parsed ->
                val payload = parsed.payload
                val headers = payload?.headers.orEmpty()
                val extracted = extractBodies(payload)
                val rawHeadersJson = headersListAdapter.toJson(headers)

                GmailMessage(
                    messageId = parsed.id,
                    subject = headerValue(headers, "Subject"),
                    from = headerValue(headers, "From"),
                    to = headerValue(headers, "To"),
                    toAddresses = parseAddressList(headerValue(headers, "To")),
                    bodyPlain = extracted.bodyPlain,
                    bodyHtml = extracted.bodyHtml,
                    attachmentsMeta = extracted.attachments,
                    messageIdHeader = headerValue(headers, "Message-Id"),
                    inReplyTo = headerValue(headers, "In-Reply-To"),
                    references = headerValue(headers, "References"),
                    rawHeadersJson = rawHeadersJson,
                    snippet = parsed.snippet,
                    internalDate = parsed.internalDate,
                    labelIds = parsed.labelIds.orEmpty(),
                )
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Case-insensitive header lookup; Gmail emits `Message-ID` / `Message-Id` interchangeably. */
    private fun headerValue(headers: List<GmailMessageHeader>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /**
     * Tokenises an RFC 5322 address list header value into one entry per top-level
     * comma. Quoted display names that embed commas (`"Doe, Jane" <jane@x>`) and
     * angle-bracketed addresses are preserved intact.
     *
     * Shared with the worker's `firstRecipientEmail` helper — the worker calls
     * `canonicalizeEmail` on the first entry, here we keep the original casing so
     * threading / display logic can recover display names later.
     */
    private fun parseAddressList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        var inQuotes = false
        var inAngle = false
        var tokenStart = 0
        var i = 0
        while (i < raw.length) {
            when (raw[i]) {
                '\\' -> if (inQuotes && i + 1 < raw.length) i++
                '"' -> inQuotes = !inQuotes
                '<' -> if (!inQuotes) inAngle = true
                '>' -> if (!inQuotes) inAngle = false
                ',' -> if (!inQuotes && !inAngle) {
                    val t = raw.substring(tokenStart, i).trim()
                    if (t.isNotEmpty()) tokens += t
                    tokenStart = i + 1
                }
                else -> Unit
            }
            i++
        }
        val tail = raw.substring(tokenStart).trim()
        if (tail.isNotEmpty()) tokens += tail
        return tokens
    }

    /** Accumulator for the recursive MIME walk. */
    private data class BodyExtraction(
        val bodyPlain: String?,
        val bodyHtml: String?,
        val attachments: List<GmailAttachmentMeta>,
    )

    /**
     * Walks the Gmail `payload` tree collecting the first `text/plain` body, the
     * first `text/html` body, and every attachment part (non-blank `filename` or
     * `Content-Disposition: attachment`). EMAIL-004 is metadata-only for
     * attachments: the body[part] byte fetch is intentionally skipped.
     */
    private fun extractBodies(payload: GmailMessagePayload?): BodyExtraction {
        if (payload == null) return BodyExtraction(null, null, emptyList())
        var plain: String? = null
        var html: String? = null
        val attachments = mutableListOf<GmailAttachmentMeta>()

        // The top-level payload may itself be a leaf (single-part message). Walk a
        // synthetic leaf with the same shape as a nested part to reuse the logic.
        val rootPart = GmailMessagePart(
            mimeType = payload.mimeType,
            filename = payload.filename,
            headers = payload.headers,
            body = payload.body,
            parts = payload.parts,
        )

        fun visit(part: GmailMessagePart) {
            val mime = part.mimeType ?: ""
            val filename = part.filename.orEmpty()
            val disposition = part.headers.orEmpty()
                .firstOrNull { it.name.equals("Content-Disposition", ignoreCase = true) }
                ?.value
                ?.trim()
                .orEmpty()
            val isAttachment = filename.isNotBlank() || disposition.startsWith("attachment", ignoreCase = true)

            if (isAttachment) {
                attachments += GmailAttachmentMeta(
                    filename = filename.ifBlank { part.body?.attachmentId ?: "attachment" },
                    mime = mime,
                    sizeBytes = part.body?.size ?: 0L,
                )
                // Attachments do not contribute body text even if Gmail emits inline data.
            } else {
                when {
                    mime.equals("text/plain", ignoreCase = true) && plain == null -> {
                        plain = decodeBase64Url(part.body?.data)
                    }
                    mime.equals("text/html", ignoreCase = true) && html == null -> {
                        html = decodeBase64Url(part.body?.data)
                    }
                }
            }

            part.parts.orEmpty().forEach { child -> visit(child) }
        }

        visit(rootPart)
        return BodyExtraction(bodyPlain = plain, bodyHtml = html, attachments = attachments)
    }

    /**
     * Decodes Gmail's base64url body data. Returns null on decode failure so the
     * caller falls through to the next candidate part rather than crashing the
     * worker on malformed input.
     */
    private fun decodeBase64Url(data: String?): String? {
        if (data.isNullOrEmpty()) return null
        return try {
            String(Base64.getUrlDecoder().decode(data), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

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
 * Full `messages.get` response envelope (format=full).
 *
 * @param id           The message's stable Gmail ID.
 * @param snippet      Gmail-generated short body preview.
 * @param internalDate Epoch milliseconds of message receipt (string in the wire format).
 * @param payload      Root message part containing headers and MIME tree.
 * @param labelIds     Gmail system labels applied to this message.
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
