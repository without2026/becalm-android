package com.becalm.android.data.remote.gmail

/**
 * Scope filter for Gmail `messages.list` full-sync passes.
 *
 * EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`) + the ING-006 invariant
 * (`.spec/data-ingestion.spec.yml:62-65, 159`) pin the cold-start indexing
 * window to **INBOX + SENT** system labels with `Promotions` / `Social` /
 * `Updates` / `Forums` / `Spam` / `Trash` / `Drafts` excluded. The Gmail API's
 * `labelIds=` query parameter is AND-only — it cannot express negative filters —
 * so this enum encodes the equivalent negative filter as a Gmail search query
 * (`q=`) string per the plan's Appendix on `q=` vs `labelIds`.
 *
 * [queryString] is intentionally **unencoded** at this layer; the caller
 * ([GmailClientImpl.listMessagesFullSyncForLabel]) runs it through
 * [java.net.URLEncoder.encode] before appending to the request URL.
 *
 * @property queryString Gmail search syntax filter (`label:…`, `-category:…`,
 *   `-in:…`). Plain ASCII — must be URL-encoded before being sent.
 */
public enum class GmailLabelScope(public val queryString: String) {
    /**
     * Received mail with marketing / system categories excluded. Drives the
     * `INBOX → From` direction hint during EMAIL-002 person_ref derivation.
     */
    INBOX(
        "label:inbox -category:promotions -category:social -category:updates " +
            "-category:forums -in:spam -in:trash -in:drafts",
    ),

    /**
     * Sent mail with drafts / trash excluded. Drives the `SENT → To[0]`
     * direction hint during EMAIL-002 person_ref derivation.
     */
    SENT("label:sent -in:trash -in:drafts"),
}
