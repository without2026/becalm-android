package com.becalm.android.domain.commitment

/**
 * Shared normalization + validation helpers for the `counterparty_ref` column used across
 * the commitment edit (EDIT-004) and manual-create (MAN-005) flows.
 *
 * A [CommitmentEntity.counterpartyRef][com.becalm.android.data.local.db.entity.CommitmentEntity.counterpartyRef]
 * is a canonicalized counterparty identifier following the precedence rule:
 * E.164 phone > lowercase email > normalized display name. This object pins the
 * normalize + phone-shape validation rules in one place so MAN-005 and EDIT-004
 * stay aligned.
 *
 * Both [CommitmentEditValidator] and [CommitmentManualValidator] delegate here
 * so the phone-shape rules stay in one place.
 */
public object CounterpartyRefNormalizer {

    /** Strict E.164 per ITU-T recommendation: leading `+`, 8..15 digits, no spaces. */
    private val E164_REGEX: Regex = Regex("""^\+[1-9]\d{7,14}$""")

    /** Characters permitted in a phone-shaped person reference. */
    private val PHONE_SHAPE_CHARS: Regex = Regex("""^[+\-\d\s]+$""")

    /**
     * Normalizes [raw] to its persistable form.
     *
     * - Trims leading/trailing whitespace.
     * - Lowercases (email normalization + consistent display-name comparison).
     * - Returns `null` for blank input (acceptable — "Unassigned" per spec).
     */
    public fun normalize(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

    /**
     * When [normalized] looks phone-shaped (only `+`, `-`, space, digits; at least
     * one digit), asserts it compacts to a valid E.164 string. Free-form names
     * skip this check.
     *
     * @return true when the value is acceptable (either free-form OR valid phone).
     */
    public fun isValidPhoneShapeOrFreeForm(normalized: String): Boolean {
        if (!PHONE_SHAPE_CHARS.matches(normalized)) return true
        if (!normalized.any { it.isDigit() }) return true
        val compact = normalized.replace(Regex("""[\s\-]"""), "")
        return E164_REGEX.matches(compact)
    }
}
