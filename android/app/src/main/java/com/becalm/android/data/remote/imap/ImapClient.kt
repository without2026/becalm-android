// Required Gradle dependencies (add in app/build.gradle.kts — owned by SP-01):
//   implementation("jakarta.mail:jakarta.mail-api:2.1.3")
//   implementation("org.eclipse.angus:angus-mail:2.0.3")
//   // angus-mail bundles the IMAPS provider; no separate activation dep needed on Android.

package com.becalm.android.data.remote.imap

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import java.util.Properties
import javax.inject.Inject

// ─── Public value types ───────────────────────────────────────────────────────

/**
 * A single IMAP message reduced to the fields required by the ingestion pipeline.
 *
 * All string fields are nullable: servers are not required to populate them and
 * Android strict-mode prohibits silent assumptions about header presence.
 *
 * @param uid           IMAP UID for this message in its current mailbox.
 * @param uidValidity   UIDVALIDITY value of the mailbox at fetch time.
 * @param messageId     RFC 5322 `Message-Id` header value, or `null` when absent.
 * @param subject       Decoded subject line, or `null`.
 * @param fromEmail     First `From` address local+domain, or `null`.
 * @param fromDisplayName  Personal name from the first `From` address, or `null`.
 * @param bodyPreview   First 200 characters of the plaintext body, or `null` when
 *                      the body is absent or purely binary.
 * @param sentAt        Sent timestamp; falls back to the IMAP INTERNALDATE when the
 *                      `Date` header is absent or unparseable.
 */
public data class ImapMessage(
    val uid: Long,
    val uidValidity: Long,
    val messageId: String?,
    val subject: String?,
    val fromEmail: String?,
    val fromDisplayName: String?,
    val bodyPreview: String?,
    val sentAt: Instant,
)

/**
 * Result of a single [ImapClient.fetchSince] call.
 *
 * @param messages       Ordered list of messages whose UID > the supplied cursor.
 * @param newUidValidity UIDVALIDITY of the INBOX as observed during this fetch.
 * @param newUidNext     Server-observed UIDNEXT of the INBOX at fetch time; use as the next
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
 * Each [fetchSince] call opens a fresh connection, fetches new messages, and closes the
 * connection. The caller is responsible for persisting the returned cursor pair
 * ([ImapFetchResult.newUidValidity] + [ImapFetchResult.newUidNext]) after a durable write.
 *
 * ## UIDVALIDITY semantics
 * IMAP [RFC 3501 §2.3.1.1](https://www.rfc-editor.org/rfc/rfc3501#section-2.3.1.1):
 * if the server returns a UIDVALIDITY value different from the stored [uidValidity] the
 * entire mailbox must be considered re-numbered. The implementation handles this internally
 * by fetching all messages from UID 1. The returned [ImapFetchResult.newUidNext] is always
 * the server's observed UIDNEXT value (never forced to 1), so the next call uses a fresh
 * cursor above the highest UID seen.
 */
public interface ImapClient {

    /**
     * Connects to [host]:[port] over SSL, authenticates with [user]/[password], selects
     * INBOX, and returns all messages with UID >= [uidNext] (or all messages when
     * [uidValidity] is `null`).
     *
     * @param host          IMAPS server hostname, e.g. "imap.naver.com".
     * @param port          IMAPS port, typically 993.
     * @param user          Login username (full email address for Naver Mail).
     * @param password      App password or account password.
     * @param uidValidity   Stored UIDVALIDITY from the previous sync, or `null` for first run.
     * @param uidNext       Stored UIDNEXT cursor from the previous sync, or `null` for first run.
     * @param maxMessages   Upper bound on the number of messages returned per call. Limits memory
     *                      impact on large mailboxes. Defaults to 100.
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
        uidValidity: Long?,
        uidNext: Long?,
        maxMessages: Int = 100,
    ): BecalmResult<ImapFetchResult>
}

// ─── Implementation ───────────────────────────────────────────────────────────

/**
 * Production [ImapClient] backed by Angus Mail (jakarta.mail API).
 *
 * All blocking I/O is dispatched on [Dispatchers.IO]. The [Session], [Folder], and
 * [UIDFolder.IMAPStore] (referred to here as the generic [jakarta.mail.Store]) are created
 * and closed within each [fetchSince] call — no connection state is retained between calls.
 */
public class ImapClientImpl @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ImapClient {

    override suspend fun fetchSince(
        host: String,
        port: Int,
        user: String,
        password: String,
        uidValidity: Long?,
        uidNext: Long?,
        maxMessages: Int,
    ): BecalmResult<ImapFetchResult> = withContext(ioDispatcher) {
        val props = buildImapsProperties(host, port)
        val session = Session.getInstance(props)

        val store = when (val r = connectStore(session, host, port, user, password)) {
            is BecalmResult.Success -> r.value
            is BecalmResult.Failure -> return@withContext BecalmResult.Failure(r.error)
        }

        var folder: Folder? = null
        return@withContext try {
            folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)

            val uidFolder = folder as UIDFolder
            val serverUidValidity = uidFolder.uidValidity
            val serverUidNext = uidFolder.uidNext

            // Determine the start UID for incremental fetch.
            // A UIDVALIDITY mismatch means UIDs are invalid — fetch from UID 1 (full resync).
            val fetchFromUid: Long = when {
                uidValidity == null -> 1L                    // first run
                uidValidity != serverUidValidity -> 1L       // resync after UIDVALIDITY change
                else -> uidNext ?: 1L                        // normal incremental
            }

            // Fetch UIDs in range [fetchFromUid, *].
            val rawMessages = uidFolder.getMessagesByUID(fetchFromUid, UIDFolder.LASTUID)

            // Cap to maxMessages (newest messages are at higher UIDs — take the tail).
            val capped = rawMessages.capTail(maxMessages)

            // Prefetch headers + partial body in a single round-trip.
            if (capped.isNotEmpty()) {
                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(UIDFolder.FetchProfileItem.UID)
                    add(FetchProfile.Item.CONTENT_INFO)
                }
                folder.fetch(capped, fp)
            }

            val messages = capped.mapNotNull { msg ->
                msg.toImapMessage(uidFolder, serverUidValidity)
            }

            // newUidNext is always set to serverUidNext (even after a UIDVALIDITY
            // mismatch / full resync), so the next call passes a fresh cursor derived
            // from newUidNext. The caller will store newUidValidity + serverUidNext
            // from the server SELECT.
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

    /**
     * Returns a trailing slice of this array of at most [maxSize] elements. When the array is
     * already within the bound the receiver is returned unchanged (no copy).
     *
     * Newest IMAP messages sit at higher UIDs — taking the tail yields the newest [maxSize].
     */
    private fun Array<jakarta.mail.Message>.capTail(maxSize: Int): Array<jakarta.mail.Message> =
        if (size > maxSize) copyOfRange(size - maxSize, size) else this

    /**
     * Projects a prefetched [jakarta.mail.Message] onto the display-safe [ImapMessage] shape.
     *
     * Runs inside `runCatching` so a single malformed message (bad header, decoding failure, etc.)
     * skips that message rather than aborting the whole batch — preserving the prior inlined
     * `mapNotNull { runCatching { ... }.getOrNull() }` semantics.
     */
    private fun jakarta.mail.Message.toImapMessage(
        uidFolder: UIDFolder,
        serverUidValidity: Long,
    ): ImapMessage? = runCatching {
        val uid = uidFolder.getUID(this)
        val internalDate = receivedDate ?: sentDate
        val sentAt = internalDate?.let {
            Instant.fromEpochMilliseconds(it.time)
        } ?: Instant.fromEpochMilliseconds(0L)

        val fromAddresses = from
        val firstFrom = fromAddresses?.firstOrNull()
        val (fromEmail, fromDisplayName) = parseFromAddress(firstFrom)

        val rawSubject = subject
        val decodedSubject = rawSubject?.let {
            runCatching { MimeUtility.decodeText(it) }.getOrDefault(it)
        }

        val messageId = getHeader("Message-Id")?.firstOrNull()

        val bodyPreview = extractBodyPreview(this)

        ImapMessage(
            uid = uid,
            uidValidity = serverUidValidity,
            messageId = messageId,
            subject = decodedSubject,
            fromEmail = fromEmail,
            fromDisplayName = fromDisplayName,
            bodyPreview = bodyPreview,
            sentAt = sentAt,
        )
    }.getOrNull()

    // ─── Helpers ──────────────────────────────────────────────────────────────

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
            is jakarta.mail.internet.InternetAddress -> {
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
     * Attempts to extract up to 200 characters of plaintext body from [msg].
     *
     * For plain-text messages the body content is truncated directly. For multipart
     * messages the first text/plain part is used. Returns `null` when the body is absent,
     * binary-only, or throws during extraction.
     *
     * PII note: the returned preview is stored in the DB but never written to logs.
     */
    private fun extractBodyPreview(msg: jakarta.mail.Message): String? = runCatching {
        val content = msg.content ?: return null
        when {
            content is String -> content.take(BODY_PREVIEW_LENGTH).takeIf { it.isNotBlank() }
            content is jakarta.mail.Multipart -> extractFromMultipart(content)
            else -> null
        }
    }.getOrNull()

    /**
     * Single-pass extraction: prefers the first `text/plain` part; if none exists, falls back
     * to the first `text/*` part (e.g. `text/html`). Avoids iterating the parts array twice.
     */
    private fun extractFromMultipart(multipart: jakarta.mail.Multipart): String? {
        var textFallback: String? = null
        for (i in 0 until multipart.count) {
            val bodyPart = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
            val contentType = bodyPart.contentType?.lowercase() ?: continue
            val text = runCatching { bodyPart.content as? String }.getOrNull() ?: continue
            if (CONTENT_TYPE_PLAIN in contentType) {
                return text.take(BODY_PREVIEW_LENGTH).takeIf { it.isNotBlank() }
            }
            if (textFallback == null && contentType.startsWith(CONTENT_TYPE_TEXT_PREFIX)) {
                textFallback = text.take(BODY_PREVIEW_LENGTH).takeIf { it.isNotBlank() }
            }
        }
        return textFallback
    }

    private companion object {
        private const val BODY_PREVIEW_LENGTH = 200
        private const val CONTENT_TYPE_PLAIN = "text/plain"
        private const val CONTENT_TYPE_TEXT_PREFIX = "text/"
    }
}
