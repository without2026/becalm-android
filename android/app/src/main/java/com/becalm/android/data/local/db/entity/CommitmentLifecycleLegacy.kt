package com.becalm.android.data.local.db.entity

/**
 * Dead-code legacy SP-36 lifecycle enum, retained ONLY so Room can deserialize rows
 * written by app versions that predate the Wave-4 UI / state-machine alignment.
 *
 * The backing `commitments.commitment_state` column is a dead column as of Wave 4:
 * no production code reads or writes it any more — the spec-aligned lifecycle now
 * lives on the `action_state` TEXT column (values `pending` / `reminded` /
 * `followed_up` / `completed` / `overdue` / `cancelled`) consumed via
 * [com.becalm.android.domain.commitment.CommitmentState].
 *
 * Both this enum and the underlying column are scheduled for removal by the
 * separate `db-commitment-drop-commitment-state-column` plan. Do not reference
 * these values from any new code.
 */
public enum class CommitmentLifecycleLegacy {
    DRAFT,
    CONFIRMED,
    SCHEDULED,
    DONE,
    DISMISSED,
}
