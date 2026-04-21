package com.becalm.android.domain.commitment

/**
 * Errors that can be returned by [CommitmentStateMachine.transition].
 *
 * Only [IllegalTransition] is modelled in the Wave-4 spec-aligned state machine —
 * there are no event payloads that can fail their own preconditions.
 */
public sealed interface TransitionError {

    /**
     * The [event] is not a valid transition from the [from] state.
     *
     * @property from  The state the commitment was in when the event was applied.
     * @property event The event that was rejected.
     */
    public data class IllegalTransition(
        val from: CommitmentState,
        val event: CommitmentEvent,
    ) : TransitionError
}
