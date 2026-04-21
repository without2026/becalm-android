package com.becalm.android.worker.ingestion

import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT

/**
 * Header / label-parsing helpers extracted from [GmailWorker] so the worker
 * stays focused on WorkManager orchestration and the per-message fetch/insert
 * pipeline. Side-effect-free, unit-testable without spinning up the worker.
 */

/**
 * Extracts and canonicalizes a single email address from an RFC 5322 `From`-style
 * value: either `"Display Name <addr@example.com>"` or `"addr@example.com"`.
 *
 * Uses [String.indexOf] rather than a regex to eliminate any backtracking risk.
 * Normalization lowercases the whole address; trailing/leading whitespace is
 * trimmed. A blank result returns null so callers drop the field rather than
 * persisting empty-string `person_ref`s.
 */
internal fun canonicalizeEmail(fromHeader: String): String? {
    if (fromHeader.isBlank()) return null
    val angleBracketStart = fromHeader.indexOf('<')
    val angleBracketEnd = fromHeader.indexOf('>')
    val raw = if (angleBracketStart >= 0 && angleBracketEnd > angleBracketStart) {
        fromHeader.substring(angleBracketStart + 1, angleBracketEnd).trim()
    } else {
        fromHeader.trim()
    }
    return raw.lowercase().ifBlank { null }
}

/**
 * Extracts and canonicalizes the first address from an RFC 5322 address list.
 *
 * Gmail's `To` / `Cc` / `Bcc` headers can carry multiple recipients separated by
 * commas, with optional display names and angle-bracketed addresses — including
 * **quoted display names that embed commas** such as
 * `"Doe, Jane" <jane@example.com>, Bob <bob@example.com>`. A naive
 * `substringBefore(',')` split on that input would truncate inside the display
 * name and hand `"Doe` to [canonicalizeEmail], persisting a malformed
 * `person_ref`. This helper therefore walks the header once and splits on the
 * first top-level comma — one that is not inside an RFC 5322 quoted-string or
 * angle-bracketed address.
 *
 * Escape handling is the common subset: a backslash inside a quoted string
 * escapes the following character (including `"`); everything else is verbatim.
 * Full RFC 5322 `quoted-pair` / `obs-qp` coverage is intentionally out of scope
 * for a Gmail `To` header — Gmail rewrites exotic forms server-side before
 * surfacing them to the REST API.
 */
internal fun firstRecipientEmail(header: String): String? {
    val firstToken = firstAddressToken(header).trim()
    if (firstToken.isEmpty()) return null
    return canonicalizeEmail(firstToken)
}

/**
 * Returns the first address token of [header] — the substring up to (but not
 * including) the first top-level comma. Commas inside quoted-strings (`"..."`)
 * and inside angle-bracket groups (`<...>`) do not terminate the token.
 *
 * When no top-level comma exists, the entire header is treated as a single token.
 */
private fun firstAddressToken(header: String): String {
    var inQuotes = false
    var inAngle = false
    var i = 0
    while (i < header.length) {
        when (header[i]) {
            '\\' -> if (inQuotes && i + 1 < header.length) i++ // skip escaped char
            '"' -> inQuotes = !inQuotes
            '<' -> if (!inQuotes) inAngle = true
            '>' -> if (!inQuotes) inAngle = false
            ',' -> if (!inQuotes && !inAngle) return header.substring(0, i)
            else -> Unit
        }
        i++
    }
    return header
}

/**
 * Maps Gmail system labels to the EMAIL-001 `folder` direction hint
 * (`.spec/email-pipeline.spec.yml:15-18`).
 *
 * Gmail history and message-list pages can surface messages from any label, so
 * this mapper is driven per-message off the `labelIds` Gmail returns on
 * `messages.get`. `SENT` takes precedence over `INBOX` because a thread-shared
 * message that Gmail tags with both is authoritatively the sender-side view for
 * EMAIL-002 `person_ref` derivation. Messages outside `{INBOX, SENT}` (drafts,
 * trash, spam, user labels without either flag) return `null` — the pipeline
 * then falls back to the pre-EMAIL-001 server-side derivation path.
 */
internal fun gmailLabelsToFolder(labelIds: List<String>): String? = when {
    labelIds.contains("SENT") -> FOLDER_SENT
    labelIds.contains("INBOX") -> FOLDER_INBOX
    else -> null
}
