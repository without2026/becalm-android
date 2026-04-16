package com.becalm.android.domain

import com.becalm.android.data.local.entities.Commitment

// spec: CMT-005..CMT-007 — commitment action_state transition guard
// State machine:
//   pending    → reminded | followed_up | completed
//   reminded   → followed_up | completed
//   followed_up → completed
//   completed  → terminal (no exits)

object CommitmentTransitions {

    private val ALLOWED: Map<String, Set<String>> = mapOf(
        Commitment.ActionState.PENDING     to setOf(
            Commitment.ActionState.REMINDED,
            Commitment.ActionState.FOLLOWED_UP,
            Commitment.ActionState.COMPLETED
        ),
        Commitment.ActionState.REMINDED    to setOf(
            Commitment.ActionState.FOLLOWED_UP,
            Commitment.ActionState.COMPLETED
        ),
        Commitment.ActionState.FOLLOWED_UP to setOf(
            Commitment.ActionState.COMPLETED
        ),
        Commitment.ActionState.COMPLETED   to emptySet()
    )

    /**
     * Returns true iff the [from]→[to] transition is valid per the spec.
     * spec: CMT-005..CMT-007
     */
    fun isValid(from: String, to: String): Boolean =
        ALLOWED[from]?.contains(to) ?: false
}
