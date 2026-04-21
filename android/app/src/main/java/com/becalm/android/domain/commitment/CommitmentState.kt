package com.becalm.android.domain.commitment

/**
 * Spec-aligned lifecycle (action_state) for a commitment.
 *
 * Values mirror the six `action_state` enum entries defined in
 * `.spec/contracts/data-model.yml:199-208` and
 * `.spec/commitment-management.spec.yml` CMT-005/006/007/011/012.
 *
 * Wire form (server / Room) uses lowercase snake_case strings — see [wireValue] and
 * [fromWire]. Legal transitions between states are enforced by
 * [CommitmentStateMachine.transition].
 *
 * - [PENDING]     — Initial state; the commitment has been extracted but no follow-through
 *                   action has been taken. Default for every new row.
 * - [REMINDED]    — User has pressed [리마인드] for this commitment (CMT-005).
 * - [FOLLOWED_UP] — User has pressed [팔로업] (CMT-006); indicates a real follow-through
 *                   action was taken (message sent / call made / meeting scheduled).
 * - [COMPLETED]   — User has pressed [완료] (CMT-007); the commitment is closed. Terminal.
 * - [OVERDUE]     — System has auto-flagged the commitment past its due date (CMT-011).
 *                   Only reached via the internal [CommitmentEvent.MarkOverdue] event —
 *                   never set directly by user action. A user may still [완료] or [취소]
 *                   an overdue commitment (CMT-007, CMT-012).
 * - [CANCELLED]   — User has pressed [취소] (CMT-012); the commitment is discarded.
 *                   Terminal.
 */
public enum class CommitmentState(public val wireValue: String) {
    PENDING("pending"),
    REMINDED("reminded"),
    FOLLOWED_UP("followed_up"),
    COMPLETED("completed"),
    OVERDUE("overdue"),
    CANCELLED("cancelled");

    public companion object {

        /**
         * Reverse lookup from the wire/Room value to the typed enum.
         *
         * Unknown or malformed inputs fall back to [PENDING] rather than throwing: data
         * that flows in from the server must never crash the UI. Callers that care
         * about the distinction between "missing" and "known" must inspect the raw
         * string themselves.
         *
         * @param value The lowercase wire string (e.g. `"followed_up"`); may be a legacy
         *   value unknown to this app version.
         * @return The matching [CommitmentState], or [PENDING] when [value] is unknown.
         */
        public fun fromWire(value: String): CommitmentState {
            // NOTE: unknown inputs intentionally degrade to PENDING without throwing so
            // a stale app talking to a future backend keeps rendering commitment rows.
            // Callers that need to log the unknown value should do so at their own layer.
            return entries.firstOrNull { it.wireValue == value } ?: PENDING
        }
    }
}
