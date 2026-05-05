package com.becalm.android.domain.commitment

import kotlinx.datetime.Instant

/**
 * Validated, persistable representation of a manually-created commitment.
 *
 * Produced by [CommitmentManualValidator.normalise] from a
 * [ManualCommitmentDraft]. The repository layer accepts this directly and
 * writes the row with `source_type = 'manual'`, `confidence = 1.0`,
 * `source_ref = null`, `source_event_title = null`, and
 * `source_event_occurred_at = created_at` per `.spec/manual-commitment.spec.yml`
 * MAN-003 + invariants.
 *
 * @property title Trimmed commitment title, 1..200 chars.
 * @property direction Commitment direction — always "give" or "take".
 * @property quote User-written context, 1..500 chars. Distinct from the LLM
 *   pipeline's verbatim-source quote: here it is the user's own notes, but
 *   stored in the same legally-evidentiary column.
 * @property counterpartyRef Normalised counterparty reference (lowercase +
 *   E.164 if phone-shaped). Null when the user did not supply one.
 * @property dueAt Optional deadline instant.
 * @property dueHint Optional verbatim due-date expression.
 * @property dueIsApproximate True when the deadline was entered as approximate.
 */
public data class ManualCommitmentInput(
    val title: String,
    val direction: String,
    val quote: String,
    val counterpartyRef: String?,
    val dueAt: Instant?,
    val dueHint: String?,
    val dueIsApproximate: Boolean,
)
