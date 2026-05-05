package com.becalm.android.domain.commitment

import kotlinx.datetime.Instant

/**
 * Validated patch applied by
 * [com.becalm.android.data.repository.CommitmentRepository.editCommitment]
 * against an existing commitment row.
 *
 * Shape rules, enforced upstream by [CommitmentEditValidator]:
 * - [title] is always non-blank, 1..200 chars after trim.
 * - [dueAt] may be null (EDIT-004 allows no-deadline commitments).
 * - [dueHint] is a verbatim LLM expression preserved as-is; may be null.
 * - [dueIsApproximate] defaults to `false` in the edit form.
 * - [counterpartyRef] is already canonicalized (lowercase / E.164) or null/blank.
 * - [direction] is exactly `"give"` or `"take"`.
 *
 * ## Intentionally not in this class
 *
 * - [com.becalm.android.data.local.db.entity.CommitmentEntity.quote] is
 *   legally evidentiary per `.spec/commitment-edit.spec.yml` invariant 1
 *   — the dispute flow (EDIT-005) exposes a flag, never the string itself.
 * - `source_*` and `confidence` are immutable on edit.
 * - `deleted_at` and the audit columns (`last_edited_*`) are repository-owned
 *   and written automatically; callers never provide them.
 *
 * Spec refs: EDIT-002, EDIT-003, EDIT-004.
 */
public data class CommitmentEditPatch(
    val title: String,
    val dueAt: Instant?,
    val dueHint: String?,
    val dueIsApproximate: Boolean,
    val counterpartyRef: String?,
    val direction: String,
)
