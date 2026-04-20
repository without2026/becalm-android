package com.becalm.android.domain.commitment

/**
 * Errors that can be returned by [CommitmentStateMachine.transition].
 *
 * - [IllegalTransition] — the requested [CommitmentEvent] is not a legal edge from [from].
 * - [MissingSchedule]   — a [CommitmentEvent.Schedule] event carried a past [at] value.
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

    /**
     * A [CommitmentEvent.Schedule] event was applied but the [CommitmentEvent.Schedule.at]
     * instant refers to a past moment.
     */
    public data object MissingSchedule : TransitionError
}
