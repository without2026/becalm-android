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
 * Mirrors the `$select` set used by [MsGraphClient.messagesDeltaForFolder]:
 * `id,internetMessageId,conversationId,parentFolderId,subject,from,toRecipients,
 * ccRecipients,bccRecipients,body,hasAttachments,internetMessageHeaders,receivedDateTime`.
 * Additional Graph fields that fall outside the projection are silently ignored by
 * Moshi's default lenient parser.
 *
 * Spec refs: EMAIL-001 (folder direction), EMAIL-002 (person_ref derivation),
 * EMAIL-004 (attachments meta), EMAIL-005 (threading headers), EMAIL-006 (EmailBody columns).
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
    /**
     * Direction hint derived from the delta endpoint scope — `"INBOX"` for
     * `/me/mailFolders/inbox/messages/delta`, `"SENT"` for `/me/mailFolders/sentitems/...`.
     * Drives EMAIL-002 person_ref assignment in the ingestion worker. Never null because
     * the client always knows the folder scope it queried.
     */
    val folder: String,
    /**
     * `To` recipient email addresses extracted from `toRecipients[*].emailAddress.address`.
     * Lowercase normalisation is deferred to the worker (see
     * `com.becalm.android.worker.ingestion.canonicalizeEmail`). Empty list when Graph omits
     * the header (e.g., malformed sent-items entries).
     */
    val toRecipients: List<String>,
    /**
     * `Cc` recipient email addresses. Preserved verbatim for EmailBody.raw_headers / UI; not
     * used for personRef derivation.
     */
    val ccRecipients: List<String>,
    /**
     * `Bcc` recipient email addresses. Same treatment as [ccRecipients]. Typically populated
     * only for messages the authenticated user sent.
     */
    val bccRecipients: List<String>,
    /**
     * HTML body content when `body.contentType == "html"`, else null. The worker hands this
     * to `EmailSnippetBuilder` for Jsoup parsing (EMAIL-003 + EMAIL-007).
     */
    val bodyHtml: String?,
    /**
     * Plain-text body content when `body.contentType == "text"`, else null. Preferred over
     * [bodyHtml] when both are available via Jsoup fall-through in the snippet builder.
     */
    val bodyPlain: String?,
    /**
     * Attachment descriptors populated by a *separate* `/me/messages/{id}/attachments?$select=…`
     * call — the messages-delta projection itself never inlines attachment arrays. The worker
     * issues this follow-up only when [hasAttachments] is true (quota optimisation).
     */
    val attachmentsMeta: List<GraphAttachmentMeta>,
    /** `In-Reply-To` header value from `internetMessageHeaders`, null when header absent. */
    val inReplyTo: String?,
    /** `References` header value from `internetMessageHeaders`, null when header absent. */
    val references: String?,
    /**
     * JSON-encoded copy of the raw `internetMessageHeaders` array as returned by Graph.
     * Stored verbatim in `EmailBody.raw_headers` for future thread-aware UI + provenance
     * audit. Defaults to `"[]"` when the tenant strips headers server-side.
     */
    val rawHeadersJson: String,
    /**
     * Optimisation flag mirroring Graph's `hasAttachments` boolean. The worker uses this to
     * skip the `/attachments` round-trip for messages without any.
     */
    val hasAttachments: Boolean,
    /**
     * Outlook conversation identifier, stored for future thread-grouping UI. Null when Graph
     * does not supply it (rare — usually populated for every message).
     */
    val conversationId: String?,
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
 * Production binds [MsGraphTokenProviderImpl] via Hilt. Tests may still substitute a
 * no-op or fake implementation when they need deterministic auth behavior.
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
     * Fetches a page of mail message deltas from the global `/me/messages/delta` endpoint.
     *
     * Delegates to [messagesDeltaForFolder] with [OutlookMailFolder.INBOX] — the global
     * endpoint is not spec-compliant (see ING-007: indexing must be scoped to
     * `Inbox` + `Sent Items` only, excluding `Drafts`, `Deleted Items`, `Junk Email`,
     * `Archive`), so this method is retained only for API backward compatibility and
     * must not be used by new call sites.
     *
     * @param deltaOrNextLink If `null`, issues the initial full sync request.
     *   If non-null, the value must be an opaque URL previously returned as
     *   [GraphDeltaResponse.nextLink] or [GraphDeltaResponse.deltaLink].
     */
    @Deprecated(
        message = "Use messagesDeltaForFolder(OutlookMailFolder, cursor). Global /me/messages/delta" +
            " violates ING-007 folder scoping.",
        replaceWith = ReplaceWith(
            expression = "messagesDeltaForFolder(OutlookMailFolder.INBOX, deltaOrNextLink)",
        ),
    )
    public suspend fun messagesDelta(deltaOrNextLink: String?): BecalmResult<GraphDeltaResponse<GraphMessage>>

    /**
     * Fetches a page of mail message deltas scoped to a single Outlook system [folder].
     *
     * Maps to `/me/mailFolders/{folder.endpointPath}/messages/delta` with a fixed
     * `$select` projection of
     * `id,internetMessageId,conversationId,parentFolderId,subject,from,toRecipients,
     * ccRecipients,bccRecipients,body,hasAttachments,internetMessageHeaders,
     * receivedDateTime`. Per ING-013 a bounded 30-day `$filter=receivedDateTime ge …`
     * is applied on the initial (cursor == null) call only; subsequent `@odata.nextLink`
     * URLs are used verbatim because Graph invalidates the delta token if query
     * parameters are mutated on follow-up pages.
     *
     * @param folder Which Outlook system folder to scope the delta to — INBOX or SENT.
     * @param deltaOrNextLink If `null`, issues the initial full sync request. If non-null,
     *   must be an opaque URL previously returned as [GraphDeltaResponse.nextLink] or
     *   [GraphDeltaResponse.deltaLink]. Cursors are tracked per folder.
     */
    public suspend fun messagesDeltaForFolder(
        folder: OutlookMailFolder,
        deltaOrNextLink: String?,
    ): BecalmResult<GraphDeltaResponse<GraphMessage>>

    /**
     * Fetches the attachment metadata list for a single message.
     *
     * Maps to `/me/messages/{messageId}/attachments?$select=name,contentType,size`.
     * Per EMAIL-004 the caller (OutlookMailWorker) MUST gate this call on
     * [GraphMessage.hasAttachments] to avoid spending quota on messages without any.
     * Metadata only — binary content is never fetched to honour the on-device
     * footprint budget.
     *
     * @param messageId Graph-assigned message id (the `id` field of [GraphMessage]).
     */
    public suspend fun messageAttachments(
        messageId: String,
    ): BecalmResult<List<GraphAttachmentMeta>>

    /**
     * Fetches a page of calendar event deltas via the `/me/calendarView/delta` endpoint.
     *
     * `/me/calendarView/delta` honours `startDateTime` / `endDateTime` query parameters,
     * constraining the delta to a bounded time window (unlike `/me/events/delta`, which
     * returns all events regardless of window).
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
