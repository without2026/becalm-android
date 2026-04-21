package com.becalm.android.data.remote.msgraph

/**
 * Well-known MS Graph mail folder identifier used by [MsGraphClient.messagesDeltaForFolder]
 * to scope the Outlook delta sync to a single system folder.
 *
 * Per ING-007 (`.spec/data-ingestion.spec.yml:69-74`) BeCalm indexes only the `Inbox`
 * and `Sent Items` system folders — the rest (`Drafts`, `Deleted Items`, `Junk Email`,
 * `Archive`) are intentionally excluded by *not* providing a matching enum entry.
 * Extending this enum is a policy change, not an implementation detail.
 *
 * ## URL assembly
 * [endpointPath] is plugged verbatim into the `/me/mailFolders/{path}/messages/delta`
 * URL template. The well-known folder names accept the lowercase singular path
 * segment form (`inbox`, `sentitems`) — this is the form Graph uses in the
 * [Delta query documentation](https://learn.microsoft.com/en-us/graph/delta-query-messages)
 * and in the `/mailFolders('inbox')` literal-addressing alternative.
 *
 * Spec refs: ING-007, `.spec/data-ingestion.spec.yml:159`, EMAIL-001.
 */
public enum class OutlookMailFolder(public val endpointPath: String) {
    /** Received mail — drives `INBOX` direction hint on downstream `raw_ingestion_events.folder`. */
    INBOX("inbox"),

    /** Sent mail — drives `SENT` direction hint so EMAIL-002 picks the first `To` recipient. */
    SENT("sentitems"),
}
