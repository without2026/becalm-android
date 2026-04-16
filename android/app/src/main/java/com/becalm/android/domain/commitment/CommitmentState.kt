package com.becalm.android.domain.commitment

/**
 * Lifecycle states for a commitment in the SP-36 state machine.
 *
 * Legal transitions are enforced by [CommitmentStateMachine]. Any state not listed
 * as a target in [CommitmentStateMachine.transition] is a terminal or guarded state.
 *
 * - [DRAFT]      — Initial state; the commitment has been extracted but not yet acted on.
 * - [CONFIRMED]  — The user has acknowledged the commitment; ready to be scheduled or closed.
 * - [SCHEDULED]  — A concrete follow-up time has been assigned via [CommitmentEvent.Schedule].
 * - [DONE]       — The commitment has been fulfilled.
 * - [DISMISSED]  — The commitment has been discarded; terminal state with no further transitions.
 */
public enum class CommitmentState {
    DRAFT,
    CONFIRMED,
    SCHEDULED,
    DONE,
    DISMISSED,
}
