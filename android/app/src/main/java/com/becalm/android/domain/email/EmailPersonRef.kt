package com.becalm.android.domain.email

import com.becalm.android.worker.ingestion.canonicalizeEmail

/**
 * EMAIL-002 person_ref derivation shared by local email-ingestion adapters.
 *
 * ## Contract (`.spec/email-pipeline.spec.yml:22-27 § EMAIL-002`)
 *
 * | Folder | Recipient count | person_ref resolves to              |
 * |--------|-----------------|-------------------------------------|
 * | INBOX  | n/a             | `canonicalizeEmail(fromAddress)`    |
 * | SENT   | 1..10           | `canonicalizeEmail(toAddresses[0])` |
 * | SENT   | 0 or > 10       | `null` (group-email quarantine)     |
 *
 * The > 10 recipient cutoff comes directly from the spec — a blast email
 * cannot name a single counterparty, so quarantining to `null` keeps
 * downstream counterparty resolution from making up a person.
 *
 * Local adapters used to carry their own `derivePersonRef` copy plus a private
 * `GROUP_EMAIL_RECIPIENT_THRESHOLD = 10` companion constant; this object is
 * the single source of truth.
 */
public object EmailPersonRef {

    /**
     * Upper bound on `SENT` recipient count before person_ref is quarantined
     * to `null` per EMAIL-002 (`.spec/email-pipeline.spec.yml:22-27`).
     */
    public const val GROUP_EMAIL_RECIPIENT_THRESHOLD: Int = 10

    /**
     * Resolves the person_ref for an INBOX message. Returns `null` when
     * [fromAddress] is null / blank / canonicalises to an empty string
     * (malformed sender header).
     */
    public fun forInbox(fromAddress: String?): String? =
        fromAddress?.let(::canonicalizeEmail)

    /**
     * Resolves the person_ref for a SENT message:
     * - empty or oversized [toAddresses] → `null` (group-email quarantine)
     * - 1..[GROUP_EMAIL_RECIPIENT_THRESHOLD] → `canonicalizeEmail(toAddresses[0])`
     *
     * The helper is total: callers never need to special-case the recipient
     * count or the empty-list edge.
     */
    public fun forSent(toAddresses: List<String>): String? = when {
        toAddresses.isEmpty() -> null
        toAddresses.size > GROUP_EMAIL_RECIPIENT_THRESHOLD -> null
        else -> canonicalizeEmail(toAddresses.first())
    }

    /**
     * Returns `true` when a SENT-folder message with [recipientCount] To-header
     * addresses is considered a group email and should set
     * [com.becalm.android.data.local.db.entity.EmailBodyEntity.groupEmail] = `true`.
     *
     * INBOX messages never flip this bit (spec invariant), so callers pass the
     * SENT condition in from the folder context; passing an INBOX row here is
     * a caller bug — the helper short-circuits to `false` via the threshold
     * check instead of raising, because worker code has no meaningful recovery
     * from a bad folder label at insert time.
     */
    public fun isGroupEmail(recipientCount: Int): Boolean =
        recipientCount > GROUP_EMAIL_RECIPIENT_THRESHOLD
}
