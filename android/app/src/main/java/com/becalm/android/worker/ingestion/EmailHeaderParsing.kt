package com.becalm.android.worker.ingestion

import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.data.remote.imap.ImapMessage
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Shared email-header parsing helpers retained for IMAP ingestion and header-focused tests.
 *
 * Gmail / Outlook Mail fetch is now backend-managed, but local email normalization still
 * needs a single canonical implementation so IMAP workers and spec tests do not fork
 * address parsing behavior.
 */
internal fun canonicalizeEmail(raw: String): String? {
    val candidate = raw
        .trim()
        .substringAfterLast('<', raw.trim())
        .substringBefore('>')
        .trim()
        .lowercase()
    return candidate.ifBlank { null }
}

/**
 * Normalizes RFC 5322 Message-ID enough for local idempotency while preserving the
 * provider identity. JavaMail commonly returns the value without angle brackets,
 * but some servers keep them; stripping only the wrapper avoids treating those
 * representations as different messages.
 */
internal fun canonicalizeMessageId(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return trimmed
        .removePrefix("<")
        .removeSuffix(">")
        .trim()
        .takeIf { it.isNotBlank() }
}

internal fun imapProviderMessageId(
    messageId: String?,
    uidValidity: Long,
    uid: Long,
): String = canonicalizeMessageId(messageId) ?: "$uidValidity:$uid"

internal fun imapClientEventId(
    provider: String,
    folder: String,
    providerMessageId: String,
): String = stableClientEventId("$provider:${folder.lowercase()}:message:$providerMessageId")

internal fun legacyImapClientEventId(
    provider: String,
    folder: String,
    uid: Long,
    uidValidity: Long,
): String = "$provider:${folder.lowercase()}:$uid:$uidValidity"

internal fun stableClientEventId(sourceKey: String): String =
    UUID.nameUUIDFromBytes(sourceKey.toByteArray(StandardCharsets.UTF_8)).toString()

internal fun ImapMessage.providerMessageId(): String =
    imapProviderMessageId(messageId = messageId, uidValidity = uidValidity, uid = uid)

internal fun ImapMessage.stableImapClientEventId(provider: String, folder: String): String =
    imapClientEventId(
        provider = provider,
        folder = folder,
        providerMessageId = providerMessageId(),
    )

internal fun ImapMessage.legacyImapClientEventId(provider: String, folder: String): String =
    legacyImapClientEventId(
        provider = provider,
        folder = folder,
        uid = uid,
        uidValidity = uidValidity,
    )

/**
 * Returns the first recipient email from a comma-delimited header, ignoring commas inside
 * quoted display names.
 */
internal fun firstRecipientEmail(raw: String): String? {
    if (raw.isBlank()) return null
    val first = StringBuilder()
    var inQuotes = false
    for (ch in raw) {
        when {
            ch == '"' -> {
                inQuotes = !inQuotes
                first.append(ch)
            }
            ch == ',' && !inQuotes -> break
            else -> first.append(ch)
        }
    }
    return canonicalizeEmail(first.toString())
}

/**
 * Legacy Gmail label mapper kept for spec tests that pin EMAIL-001 folder precedence.
 */
internal fun gmailLabelsToFolder(labels: List<String>): String? = when {
    labels.any { it.equals(FOLDER_SENT, ignoreCase = true) || it.equals("SENT", ignoreCase = true) } -> FOLDER_SENT
    labels.any { it.equals(FOLDER_INBOX, ignoreCase = true) || it.equals("INBOX", ignoreCase = true) } -> FOLDER_INBOX
    else -> null
}
