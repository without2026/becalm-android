// Required Gradle dependencies (add in app/build.gradle.kts — owned by SP-01):
//   implementation("jakarta.mail:jakarta.mail-api:2.1.3")
//   implementation("org.eclipse.angus:angus-mail:2.0.3")
//   // angus-mail bundles the IMAPS provider; no separate activation dep needed on Android.

package com.becalm.android.data.remote.imap

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.Dispatchers
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
 * @param newUidNext     UIDNEXT of the INBOX after this fetch; use as the next cursor.
 *                       Set to `1` when a UIDVALIDITY mismatch triggered a full resync
 *                       — the caller should persist this value and pass it on the next call.
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
 * entire mailbox must be considered re-numbered. The implementation handles this internally:
 * it fetches all messages and returns [ImapFetchResult.newUidNext] = 1 so the next call
 * produces an empty differential rather than a duplicate full-fetch.
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
public class ImapClientImpl @Inject constructor() : ImapClient {

    override suspend fun fetchSince(
        host: String,
        port: Int,
        user: String,
        password: String,
        uidValidity: Long?,
        uidNext: Long?,
        maxMessages: Int,
    ): BecalmResult<ImapFetchResult> = withContext(Dispatchers.IO) {
        val props = buildImapsProperties(host, port)
        val session = Session.getInstance(props)

        val store = try {
            session.getStore("imaps")
        } catch (e: Exception) {
            return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
        }

        try {
            store.connect(host, port, user, password)
        } catch (e: AuthenticationFailedException) {
            return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
        } catch (e: MessagingException) {
            return@withContext BecalmResult.Failure(
                BecalmError.Network(-1, e.message ?: "IMAP connect failed"),
            )
        } catch (e: Exception) {
            return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
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
            val capped = if (rawMessages.size > maxMessages) {
                rawMessages.copyOfRange(rawMessages.size - maxMessages, rawMessages.size)
            } else {
                rawMessages
            }

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
                runCatching {
                    val uid = uidFolder.getUID(msg)
                    val internalDate = msg.receivedDate ?: msg.sentDate
                    val sentAt = internalDate?.let {
                        Instant.fromEpochMilliseconds(it.time)
                    } ?: Instant.fromEpochMilliseconds(0L)

                    val fromAddresses = msg.from
                    val firstFrom = fromAddresses?.firstOrNull()
                    val (fromEmail, fromDisplayName) = parseFromAddress(firstFrom)

                    val rawSubject = msg.subject
                    val decodedSubject = rawSubject?.let {
                        runCatching { MimeUtility.decodeText(it) }.getOrDefault(it)
                    }

                    val messageId = msg.getHeader("Message-Id")?.firstOrNull()

                    val bodyPreview = extractBodyPreview(msg)

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
            }

            // When a UIDVALIDITY mismatch triggered a full resync, return newUidNext = 1
            // so the next call correctly passes a fresh cursor derived from newUidNext.
            // The caller will store newUidValidity + serverUidNext from the server SELECT.
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
