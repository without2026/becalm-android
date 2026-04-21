package com.becalm.android.data.remote.imap

/**
 * A single IMAP folder returned by [ImapClient.listFolders].
 *
 * The [name] is the raw mailbox name as reported by the server's `LIST "" "*"` response
 * (UTF-7-decoded by Jakarta Mail before reaching this value type). [specialUse] is the
 * RFC 6154 SPECIAL-USE flag present on the folder — `\Inbox` / `\Sent` / `\Drafts` / …
 * — or `null` when the server did not advertise one (typical for Naver's `보낸메일함`
 * and Daum's `보낸편지함`: the callers apply a name-based fallback).
 *
 * ## Why a value type
 * The raw Jakarta Mail [jakarta.mail.Folder] object holds a live connection handle
 * that is invalid after the enclosing `store.close()` returns. Projecting the two
 * fields the worker layer actually needs onto an immutable data class lets the
 * discovery step share one connection with the subsequent `fetchSince` calls and
 * keeps the API free of jakarta.mail types (unit-testable without GreenMail).
 */
public data class ImapFolder(
    val name: String,
    val specialUse: ImapSpecialUse?,
)

/**
 * RFC 6154 SPECIAL-USE flag categories for [ImapFolder.specialUse].
 *
 * Maps 1:1 to the `\Inbox`, `\Sent`, `\Drafts`, `\Junk`, `\Trash`, `\All` attributes
 * a server MAY advertise on `LIST "" "*"`. `OTHER` covers servers that expose a
 * special-use attribute outside this subset (e.g. `\Important` on Gmail); callers
 * treat it the same as `null` — no routing decision is made from it.
 *
 * Spec: RFC 6154 §2 + `.spec/data-ingestion.spec.yml:78-85 § ING-008`.
 */
public enum class ImapSpecialUse {
    INBOX,
    SENT,
    DRAFTS,
    JUNK,
    TRASH,
    ALL,
    OTHER,
}
