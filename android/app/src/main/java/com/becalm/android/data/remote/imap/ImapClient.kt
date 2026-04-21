// Required Gradle dependencies (add in app/build.gradle.kts — owned by SP-01):
//   implementation("jakarta.mail:jakarta.mail-api:2.1.3")
//   implementation("org.eclipse.angus:angus-mail:2.0.3")
//   // angus-mail bundles the IMAPS provider; no separate activation dep needed on Android.

package com.becalm.android.data.remote.imap

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.squareup.moshi.JsonWriter
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import okio.Buffer
import java.util.Date
import java.util.Properties
import javax.inject.Inject

// ─── Public value types ───────────────────────────────────────────────────────

/**
 * A single IMAP message reduced to the fields required by the ingestion pipeline.
 *
 * All nullable string fields follow "server didn't populate" semantics — the worker
 * layer treats null and empty as functionally identical for cursor/persistence purposes.
 *
 * ## BODYSTRUCTURE-only parsing (EMAIL-004)
 * [bodyPlain], [bodyHtml], and [attachmentsMeta] are populated by walking BODYSTRUCTURE
 * plus fetching the body for text parts only. Attachment bytes MUST NOT be downloaded
 * (`.spec/email-pipeline.spec.yml:40-45`) — [attachmentsMeta] carries `filename/mime/size`
 * metadata exclusively, never the payload.
 *
 * @param uid             IMAP UID for this message in its current mailbox.
 * @param uidValidity     UIDVALIDITY value of the mailbox at fetch time.
 * @param folder          Raw mailbox name this message was fetched from (e.g. `"INBOX"`,
 *                        `"보낸메일함"`). Worker layer uses this to derive `person_ref`
 *                        (INBOX→from, Sent→to[0]).
 * @param messageId       RFC 5322 `Message-Id` header value, or `null` when absent.
 * @param subject         Decoded subject line, or `null`.
 * @param fromEmail       First `From` address local+domain, or `null`.
 * @param fromDisplayName Personal name from the first `From` address, or `null`.
 * @param toAddresses     Decoded `To` recipients (lowercased, canonicalized where possible).
 *                        Empty list when the header is absent.
 * @param bodyPlain       First `text/plain` part as decoded UTF-8, or `null`. No size cap
 *                        — callers are responsible for truncating if needed.
 * @param bodyHtml        First `text/html` part as decoded UTF-8, or `null`.
 * @param attachmentsMeta Metadata descriptors for every non-text part (EMAIL-004 —
 *                        filename/mime/size only, never bytes).
 * @param inReplyTo       RFC 5322 `In-Reply-To` header, or `null` when absent.
 * @param references      RFC 5322 `References` header (may be multi-line joined), or `null`.
 * @param rawHeadersJson  JSON-object string of the full header map (lowercased keys,
 *                        first value only). Used as an escape hatch for future header needs.
 * @param sentAt          Sent timestamp; falls back to the IMAP INTERNALDATE when the
 *                        `Date` header is absent or unparseable.
 */
public data class ImapMessage(
    val uid: Long,
    val uidValidity: Long,
    val folder: String,
    val messageId: String?,
    val subject: String?,
    val fromEmail: String?,
    val fromDisplayName: String?,
    val toAddresses: List<String>,
    val bodyPlain: String?,
    val bodyHtml: String?,
    val attachmentsMeta: List<ImapAttachmentMeta>,
    val inReplyTo: String?,
    val references: String?,
    val rawHeadersJson: String,
    val sentAt: Instant,
)

/**
 * Result of a single [ImapClient.fetchSince] call against one folder.
 *
 * @param messages       Ordered list of messages fetched in this pass.
 * @param newUidValidity UIDVALIDITY of the selected folder at fetch time.
 * @param newUidNext     Server-observed UIDNEXT of the folder at fetch time; use as the next
 *                       cursor. Always the server's UIDNEXT value — never forced to `1`,
 *                       even after a UIDVALIDITY mismatch triggers a full resync.
 */
public data class ImapFetchResult(
    val messages: List<ImapMessage>,
    val newUidValidity: Long,
    val newUidNext: Long,
)

// ─── Interface ────────────────────────────────────────────────────────────────

/**
 * Stateless IMAPS (IMAP-over-SSL) fetch client.
 *
 * Each call opens a fresh connection, performs its work, and closes the connection. The
 * caller is responsible for persisting the returned cursor pair
 * ([ImapFetchResult.newUidValidity] + [ImapFetchResult.newUidNext]) after a durable write.
 *
 * ## Folder discovery — [listFolders]
 * Ingestion workers call [listFolders] once per sync cycle to resolve the provider-specific
 * `\Inbox` + `\Sent` folders, then iterate `fetchSince(folder=...)` for each pass. This
 * satisfies ING-008 (`.spec/data-ingestion.spec.yml:78-85`) — the 2-pass INBOX+Sent scope.
 *
 * ## UIDVALIDITY semantics
 * IMAP [RFC 3501 §2.3.1.1](https://www.rfc-editor.org/rfc/rfc3501#section-2.3.1.1):
 * if the server returns a UIDVALIDITY value different from the stored [uidValidity] the
 * entire mailbox must be considered re-numbered. The implementation handles this via
 * `ReceivedDateTerm(>=, now - sinceDays)` — bounded resync within the last [sinceDays]
 * days rather than the full mailbox (ING-013, `.spec/data-ingestion.spec.yml:105-110`).
 * The returned [ImapFetchResult.newUidNext] is always the server's observed UIDNEXT,
 * so the next call uses a fresh cursor above the highest UID seen.
 *
 * ## BODYSTRUCTURE-only (EMAIL-004)
 * [fetchSince] walks BODYSTRUCTURE + reads body text for `text` MIME parts only. Attachment
 * bytes MUST NOT be fetched; [ImapMessage.attachmentsMeta] carries
 * filename/mime/size metadata only (`.spec/email-pipeline.spec.yml:40-45`).
 */
public interface ImapClient {

    /**
     * Connects to [host]:[port] over SSL, authenticates with [user]/[password], and returns
     * the list of top-level folders exposed by the server.
     *
     * Uses `store.defaultFolder.list("*")` — a single `LIST "" "*"` round-trip returning the
     * full mailbox tree. Each [ImapFolder] carries the raw mailbox [ImapFolder.name] (for
     * subsequent [fetchSince] calls) and an RFC 6154 [ImapFolder.specialUse] flag when the
     * server advertises one. Servers that omit SPECIAL-USE (both Naver and Daum typically
     * do for their Korean-named Sent folder) leave [ImapFolder.specialUse] as `null`;
     * callers apply a name-based fallback.
     *
     * @return [BecalmResult.Success] with the flat list of discovered folders, or:
     *   - [BecalmResult.Failure] with [BecalmError.Unauthorized] on authentication failure.
     *   - [BecalmResult.Failure] with [BecalmError.Network] on transport / DNS errors.
     *   - [BecalmResult.Failure] with [BecalmError.Unknown] for all other failures.
     */
    public suspend fun listFolders(
        host: String,
        port: Int,
        user: String,
        password: String,
    ): BecalmResult<List<ImapFolder>>

    /**
     * Connects, authenticates, opens [mailbox] read-only, and returns messages with
     * UID > [uidNext] (or — on UIDVALIDITY mismatch / cold start — all messages in the
     * last [sinceDays] days selected via `SEARCH SINCE`).
     *
     * The [mailbox] parameter is the raw folder name from [listFolders] (e.g. `"INBOX"`,
     * `"보낸메일함"`, `"보낸편지함"`). Callers MUST NOT pass user-supplied strings — only
     * names previously observed from the server's own `LIST` response.
     *
     * @param host         IMAPS server hostname, e.g. `imap.naver.com`.
     * @param port         IMAPS port, typically 993.
     * @param user         Login username (full email address for Naver/Daum Mail).
     * @param password     App password or account password.
     * @param mailbox      Raw folder name to select (see above).
     * @param uidValidity  Stored UIDVALIDITY from the previous sync of this folder, or
     *                     `null` for first run.
     * @param uidNext      Stored UIDNEXT cursor from the previous sync of this folder, or
     *                     `null` for first run.
     * @param sinceDays    Upper bound on message age for cold-start / UIDVALIDITY-rebuild
     *                     passes. Default 30 days per ING-013
     *                     (`.spec/data-ingestion.spec.yml:105-110`). Unused on the normal
     *                     incremental path (UID-based).
     *
     * @return [BecalmResult.Success] with an [ImapFetchResult], or:
     *   - [BecalmResult.Failure] with [BecalmError.Unauthorized] on authentication failure.
     *   - [BecalmResult.Failure] with [BecalmError.Network] on transport / DNS errors.
     *   - [BecalmResult.Failure] with [BecalmError.Unknown] for all other failures.
     */
    public suspend fun fetchSince(
        host: String,
        port: Int,
        user: String,
        password: String,
        mailbox: String,
        uidValidity: Long?,
        uidNext: Long?,
        sinceDays: Int = 30,
    ): BecalmResult<ImapFetchResult>
}

// ─── Implementation ───────────────────────────────────────────────────────────

/**
 * Production [ImapClient] backed by Angus Mail (jakarta.mail API).
 *
 * All blocking I/O is dispatched on [Dispatchers.IO]. The [Session], [Folder], and
 * [jakarta.mail.Store] are created and closed within each call — no connection state is
 * retained between calls.
 */
public class ImapClientImpl @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ImapClient {

    override suspend fun listFolders(
        host: String,
        port: Int,
        user: String,
        password: String,
    ): BecalmResult<List<ImapFolder>> = withContext(ioDispatcher) {
        val props = buildImapsProperties(host, port)
        val session = Session.getInstance(props)

        val store = when (val r = connectStore(session, host, port, user, password)) {
            is BecalmResult.Success -> r.value
            is BecalmResult.Failure -> return@withContext BecalmResult.Failure(r.error)
        }

        return@withContext try {
            val rawFolders = store.defaultFolder.list("*")
            val folders = rawFolders.mapNotNull { raw ->
                runCatching {
                    ImapFolder(
                        name = raw.fullName,
                        specialUse = mapSpecialUse(raw.attributes()),
                    )
                }.getOrNull()
            }
            BecalmResult.Success(folders)
        } catch (e: AuthenticationFailedException) {
            BecalmResult.Failure(BecalmError.Unauthorized)
        } catch (e: MessagingException) {
            BecalmResult.Failure(BecalmError.Network(-1, e.message ?: "IMAP LIST failed"))
        } catch (e: Exception) {
            BecalmResult.Failure(BecalmError.Unknown(e))
        } finally {
            runCatching { store.close() }
        }
    }

    override suspend fun fetchSince(
        host: String,
        port: Int,
        user: String,
        password: String,
        mailbox: String,
        uidValidity: Long?,
        uidNext: Long?,
        sinceDays: Int,
    ): BecalmResult<ImapFetchResult> = withContext(ioDispatcher) {
        val props = buildImapsProperties(host, port)
        val session = Session.getInstance(props)

        val store = when (val r = connectStore(session, host, port, user, password)) {
            is BecalmResult.Success -> r.value
            is BecalmResult.Failure -> return@withContext BecalmResult.Failure(r.error)
        }

        var folder: Folder? = null
        return@withContext try {
            folder = store.getFolder(mailbox)
            folder.open(Folder.READ_ONLY)

            val uidFolder = folder as UIDFolder
            val serverUidValidity = uidFolder.uidValidity
            val serverUidNext = uidFolder.uidNext

            // Branch between normal incremental (UID-based) and cold/rebuild (date-based).
            // The rebuild path uses SEARCH SINCE to enforce the ING-013 30-day bound
            // instead of a blanket getMessagesByUID(1, *) scan.
            val needsRebuild = uidValidity == null || uidValidity != serverUidValidity
            val capped: Array<jakarta.mail.Message> = if (needsRebuild) {
                val sinceDate = Date(System.currentTimeMillis() - sinceDays * MILLIS_PER_DAY)
                val term = ReceivedDateTerm(ComparisonTerm.GE, sinceDate)
                folder.search(term) ?: emptyArray()
            } else {
                val fetchFromUid = uidNext ?: 1L
                uidFolder.getMessagesByUID(fetchFromUid, UIDFolder.LASTUID) ?: emptyArray()
            }

            if (capped.isNotEmpty()) {
                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(UIDFolder.FetchProfileItem.UID)
                    add(FetchProfile.Item.CONTENT_INFO)
                    add(FetchProfile.Item.FLAGS)
                    // HEADERS so Message-Id / In-Reply-To / References / Date arrive in one
                    // round-trip. Without this, each getHeader() triggers a FETCH BODY.PEEK[HEADER].
                    add("Message-Id")
                    add("In-Reply-To")
                    add("References")
                    add("Date")
                }
                folder.fetch(capped, fp)
            }

            val messages = capped.mapNotNull { msg ->
                msg.toImapMessage(uidFolder, serverUidValidity, mailbox)
            }

            BecalmResult.Success(
                ImapFetchResult(
                    messages = messages,
                    newUidValidity = serverUidValidity,
                    newUidNext = serverUidNext,
                ),
            )
        } catch (e: AuthenticationFailedException) {
            BecalmResult.Failure(BecalmError.Unauthorized)
        } catch (e: MessagingException) {
            BecalmResult.Failure(BecalmError.Network(-1, e.message ?: "IMAP fetch failed"))
        } catch (e: Exception) {
            BecalmResult.Failure(BecalmError.Unknown(e))
        } finally {
            runCatching { folder?.close(false) }
            runCatching { store.close() }
        }
    }

    // ─── Connection ──────────────────────────────────────────────────────────

    /**
     * Opens an IMAPS [jakarta.mail.Store] against [host]:[port] and authenticates with [user]/[password].
     *
     * Error mapping preserved byte-identical with the former inlined sequence in [fetchSince]:
     *  - `session.getStore("imaps")` Exception → [BecalmError.Unknown]
     *  - `AuthenticationFailedException` on connect → [BecalmError.Unauthorized]
     *  - `MessagingException` on connect → [BecalmError.Network] with "IMAP connect failed" default message
     *  - Any other Exception on connect → [BecalmError.Unknown]
     */
    private fun connectStore(
        session: Session,
        host: String,
        port: Int,
        user: String,
        password: String,
    ): BecalmResult<jakarta.mail.Store> {
        val store = try {
            session.getStore("imaps")
        } catch (e: Exception) {
            return BecalmResult.Failure(BecalmError.Unknown(e))
        }
        try {
            store.connect(host, port, user, password)
        } catch (e: AuthenticationFailedException) {
            return BecalmResult.Failure(BecalmError.Unauthorized)
        } catch (e: MessagingException) {
            return BecalmResult.Failure(
                BecalmError.Network(-1, e.message ?: "IMAP connect failed"),
            )
        } catch (e: Exception) {
            return BecalmResult.Failure(BecalmError.Unknown(e))
        }
        return BecalmResult.Success(store)
    }

    // ─── Message projection ──────────────────────────────────────────────────

    /**
     * Projects a prefetched [jakarta.mail.Message] onto the display-safe [ImapMessage] shape.
     *
     * Runs inside `runCatching` so a single malformed message (bad header, decoding failure, etc.)
     * skips that message rather than aborting the whole batch.
     */
    private fun jakarta.mail.Message.toImapMessage(
        uidFolder: UIDFolder,
        serverUidValidity: Long,
        mailbox: String,
    ): ImapMessage? = runCatching {
        val uid = uidFolder.getUID(this)
        val internalDate = receivedDate ?: sentDate
        val sentAt = internalDate?.let {
            Instant.fromEpochMilliseconds(it.time)
        } ?: Instant.fromEpochMilliseconds(0L)

        val fromAddresses = from
        val firstFrom = fromAddresses?.firstOrNull()
        val (fromEmail, fromDisplayName) = parseFromAddress(firstFrom)

        val toAddresses = parseRecipientEmails(
            getRecipients(jakarta.mail.Message.RecipientType.TO),
        )

        val rawSubject = subject
        val decodedSubject = rawSubject?.let {
            runCatching { MimeUtility.decodeText(it) }.getOrDefault(it)
        }

        val messageId = getHeader("Message-Id")?.firstOrNull()?.trim()
        val inReplyTo = getHeader("In-Reply-To")?.firstOrNull()?.trim()
        val references = getHeader("References")?.firstOrNull()?.trim()

        val bodyParts = BodyParts()
        walkBodyStructure(this, bodyParts)

        val rawHeadersJson = buildRawHeadersJson(this)

        ImapMessage(
            uid = uid,
            uidValidity = serverUidValidity,
            folder = mailbox,
            messageId = messageId,
            subject = decodedSubject,
            fromEmail = fromEmail,
            fromDisplayName = fromDisplayName,
            toAddresses = toAddresses,
            bodyPlain = bodyParts.plain,
            bodyHtml = bodyParts.html,
            attachmentsMeta = bodyParts.attachments.toList(),
            inReplyTo = inReplyTo,
            references = references,
            rawHeadersJson = rawHeadersJson,
            sentAt = sentAt,
        )
    }.getOrNull()

    /**
     * Mutable accumulator used by [walkBodyStructure] to collect the first `text/plain` body,
     * the first `text/html` body, and every attachment descriptor in a single BODYSTRUCTURE
     * traversal.
     *
     * Only the FIRST `text/plain` / `text/html` is retained — nested `multipart/alternative`
     * siblings are the common case (provider sends both plaintext + HTML copies of the same
     * message). Later parts are considered attachments or alternate representations.
     */
    private class BodyParts {
        var plain: String? = null
        var html: String? = null
        val attachments: MutableList<ImapAttachmentMeta> = mutableListOf()
    }

    /**
     * Recursive BODYSTRUCTURE traversal. For every leaf [Part] the content-type determines
     * the action:
     *  - `text/plain` (inline): read the decoded body string into [BodyParts.plain] unless
     *    one is already set.
     *  - `text/html` (inline): same for [BodyParts.html].
     *  - anything with a filename OR a non-text MIME: record a metadata entry in
     *    [BodyParts.attachments] — NEVER read `part.content` (EMAIL-004, forbidden network
     *    round-trip).
     *
     * For [Multipart] containers the function recurses into each body part.
     *
     * Per-part operations are wrapped in `runCatching` so a single malformed part does not
     * abort the walk.
     */
    private fun walkBodyStructure(part: Part, out: BodyParts) {
        runCatching {
            val contentType = part.contentType?.lowercase().orEmpty()
            val disposition = part.disposition?.lowercase().orEmpty()
            val filename = part.fileName
            val hasAttachmentDisposition = Part.ATTACHMENT.equals(disposition, ignoreCase = true)
            val isAttachment = hasAttachmentDisposition || filename != null

            // Multipart container: recurse.
            if (contentType.startsWith("multipart/")) {
                val multipart = runCatching { part.content as? Multipart }.getOrNull()
                if (multipart != null) {
                    for (i in 0 until multipart.count) {
                        val child = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
                        walkBodyStructure(child, out)
                    }
                }
                return@runCatching
            }

            // Attachment leaf — record metadata only. EMAIL-004: do NOT call part.content.
            if (isAttachment) {
                val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
                val size = runCatching { part.size }.getOrDefault(-1)
                val decodedName = filename?.let {
                    runCatching { MimeUtility.decodeText(it) }.getOrDefault(it)
                } ?: "(unnamed)"
                out.attachments += ImapAttachmentMeta(
                    filename = decodedName,
                    mime = mime,
                    sizeBytes = if (size >= 0) size.toLong() else 0L,
                )
                return@runCatching
            }

            // Inline text/plain.
            if (contentType.startsWith(CONTENT_TYPE_PLAIN) && out.plain == null) {
                val text = runCatching { part.content as? String }.getOrNull()
                if (!text.isNullOrBlank()) out.plain = text
                return@runCatching
            }

            // Inline text/html.
            if (contentType.startsWith(CONTENT_TYPE_HTML) && out.html == null) {
                val text = runCatching { part.content as? String }.getOrNull()
                if (!text.isNullOrBlank()) out.html = text
                return@runCatching
            }

            // Inline text/* other than plain/html — record under plain as a last-resort fallback
            // when nothing else is found (helps on servers that flag the body as text/enriched).
            if (contentType.startsWith(CONTENT_TYPE_TEXT_PREFIX) && out.plain == null) {
                val text = runCatching { part.content as? String }.getOrNull()
                if (!text.isNullOrBlank()) out.plain = text
            }
        }
    }

    /**
     * Serialises every message header (lowercased name, first value only) to a JSON object
     * string. The output is stored in [ImapMessage.rawHeadersJson] as an escape hatch
     * for future header-dependent features — never written to logs (PII).
     */
    private fun buildRawHeadersJson(message: jakarta.mail.Message): String {
        val buffer = Buffer()
        return try {
            JsonWriter.of(buffer).use { writer ->
                writer.beginObject()
                val seen = HashSet<String>()
                val enumeration = runCatching { message.allHeaders }.getOrNull()
                while (enumeration?.hasMoreElements() == true) {
                    val header = enumeration.nextElement() ?: continue
                    val name = header.name?.lowercase() ?: continue
                    if (!seen.add(name)) continue
                    writer.name(name).value(header.value.orEmpty())
                }
                writer.endObject()
            }
            buffer.readUtf8()
        } catch (e: Exception) {
            "{}"
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Maps RFC 6154 SPECIAL-USE attribute strings to [ImapSpecialUse].
     *
     * Jakarta Mail exposes attributes via [Folder.getAttributes] as a plain `String[]`
     * containing the raw attribute tokens (e.g. `\Sent`, `\Inbox`, `\Noselect`). Attribute
     * matching is case-insensitive per RFC 3501 §5.1.
     */
    private fun mapSpecialUse(attributes: Array<String>?): ImapSpecialUse? {
        if (attributes == null) return null
        for (attr in attributes) {
            when (attr.lowercase()) {
                "\\inbox" -> return ImapSpecialUse.INBOX
                "\\sent" -> return ImapSpecialUse.SENT
                "\\drafts" -> return ImapSpecialUse.DRAFTS
                "\\junk" -> return ImapSpecialUse.JUNK
                "\\trash" -> return ImapSpecialUse.TRASH
                "\\all" -> return ImapSpecialUse.ALL
                "\\important", "\\flagged" -> return ImapSpecialUse.OTHER
            }
        }
        return null
    }

    private fun buildImapsProperties(host: String, port: Int): Properties = Properties().apply {
        put("mail.store.protocol", "imaps")
        put("mail.imaps.host", host)
        put("mail.imaps.port", port.toString())
        put("mail.imaps.ssl.enable", "true")
        // MED-01: enforce SSL hostname verification to prevent MITM attacks.
        // Jakarta Mail / Angus Mail does not verify hostname by default on Android.
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.connectiontimeout", "15000")
        put("mail.imaps.timeout", "15000")
        // Disable STARTTLS — we connect directly over SSL/TLS on port 993.
        // IMAPS on 993 uses implicit TLS; STARTTLS is for port 143 (IMAP).
        put("mail.imaps.starttls.enable", "false")
    }

    /**
     * Parses the local-part email address and personal display name from a
     * [jakarta.mail.Address]. Returns a pair of (email, displayName), either may be null.
     *
     * PII note: the returned values are stored in the DB but never written to logs.
     */
    private fun parseFromAddress(
        address: jakarta.mail.Address?,
    ): Pair<String?, String?> {
        if (address == null) return Pair(null, null)
        return when (address) {
            is InternetAddress -> {
                val email = address.address?.takeIf { it.isNotBlank() }
                val name = address.personal
                    ?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
                    ?.takeIf { it.isNotBlank() }
                Pair(email, name)
            }
            else -> Pair(address.toString().takeIf { it.isNotBlank() }, null)
        }
    }

    /**
     * Extracts the email addresses (lowercased) from an array of `To` [jakarta.mail.Address]
     * headers. Empty / null input → empty list. Non-InternetAddress entries are skipped.
     */
    private fun parseRecipientEmails(addresses: Array<jakarta.mail.Address>?): List<String> {
        if (addresses == null) return emptyList()
        return addresses.mapNotNull { addr ->
            when (addr) {
                is InternetAddress -> addr.address?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                else -> addr.toString().trim().lowercase().takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * Safe wrapper around Jakarta Mail's `Folder.getAttributes()`. The base [Folder] class
     * only declares `protected String[] getAttributes()` in some Angus Mail versions — we
     * use reflection to avoid a hard compile-time dependency on the IMAP subclass while
     * still reading RFC 6154 SPECIAL-USE flags when they are advertised.
     */
    private fun Folder.attributes(): Array<String>? = runCatching {
        val m = this::class.java.getMethod("getAttributes")
        @Suppress("UNCHECKED_CAST")
        m.invoke(this) as? Array<String>
    }.getOrNull()

    private companion object {
        private const val CONTENT_TYPE_PLAIN = "text/plain"
        private const val CONTENT_TYPE_HTML = "text/html"
        private const val CONTENT_TYPE_TEXT_PREFIX = "text/"
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

