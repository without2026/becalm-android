package com.becalm.android.worker.ingestion

import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT

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
