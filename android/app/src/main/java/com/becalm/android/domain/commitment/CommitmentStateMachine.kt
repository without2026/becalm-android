package com.becalm.android.domain.commitment

import kotlinx.datetime.Clock

/**
 * Pure, stateless state machine for commitment lifecycle transitions.
 *
 * All logic is expressed as a single [transition] function with no side effects.
 * Callers are responsible for persisting the resulting state.
 *
 * ## Legal edges
 * ```
 * DRAFT      + Confirm       → CONFIRMED
 * DRAFT      + Dismiss       → DISMISSED
 * CONFIRMED  + Schedule      → SCHEDULED
 * CONFIRMED  + MarkDone      → DONE
 * CONFIRMED  + Dismiss       → DISMISSED
 * SCHEDULED  + MarkDone      → DONE
 * SCHEDULED  + Dismiss       → DISMISSED
 * DONE       + ReopenFromDone→ CONFIRMED
 * DISMISSED  + (any)         → [TransitionError.IllegalTransition]  (terminal)
 * ```
 * All other combinations return [TransitionError.IllegalTransition].
 */
public object CommitmentStateMachine {

    /**
     * Applies [event] to [current] and returns the resulting state, or a [TransitionError]
     * when the transition is illegal or a precondition fails.
     *
     * @param current The present lifecycle state of the commitment.
     * @param event   The event to apply.
     * @return [TransitionResult.Ok] with the next [CommitmentState], or
     *   [TransitionResult.Err] with the reason the transition was rejected.
     */
    public fun transition(
        current: CommitmentState,
        event: CommitmentEvent,
    ): TransitionResult = when (current) {
        CommitmentState.DRAFT -> when (event) {
            CommitmentEvent.Confirm  -> TransitionResult.Ok(CommitmentState.CONFIRMED)
            CommitmentEvent.Dismiss  -> TransitionResult.Ok(CommitmentState.DISMISSED)
            else                     -> TransitionResult.Err(TransitionError.IllegalTransition(current, event))
        }

        CommitmentState.CONFIRMED -> when (event) {
            is CommitmentEvent.Schedule -> {
                if (event.at <= Clock.System.now()) {
                    TransitionResult.Err(TransitionError.MissingSchedule)
                } else {
                    TransitionResult.Ok(CommitmentState.SCHEDULED)
                }
            }
            CommitmentEvent.MarkDone    -> TransitionResult.Ok(CommitmentState.DONE)
            CommitmentEvent.Dismiss     -> TransitionResult.Ok(CommitmentState.DISMISSED)
            else                        -> TransitionResult.Err(TransitionError.IllegalTransition(current, event))
        }

        CommitmentState.SCHEDULED -> when (event) {
            CommitmentEvent.MarkDone -> TransitionResult.Ok(CommitmentState.DONE)
            CommitmentEvent.Dismiss  -> TransitionResult.Ok(CommitmentState.DISMISSED)
            else                     -> TransitionResult.Err(TransitionError.IllegalTransition(current, event))
        }

        CommitmentState.DONE -> when (event) {
            CommitmentEvent.ReopenFromDone -> TransitionResult.Ok(CommitmentState.CONFIRMED)
            else                           -> TransitionResult.Err(TransitionError.IllegalTransition(current, event))
        }

        CommitmentState.DISMISSED ->
            TransitionResult.Err(TransitionError.IllegalTransition(current, event))
    }
}

/**
 * Discriminated union returned by [CommitmentStateMachine.transition].
 *
 * Using a local sealed type keeps the domain layer free of [com.becalm.android.core.result.BecalmError]
 * while still providing exhaustive `when` handling at call sites.
 */
public sealed interface TransitionResult {

    /** The transition succeeded; [state] is the new [CommitmentState]. */
    public data class Ok(val state: CommitmentState) : TransitionResult

    /** The transition was rejected; [error] describes the reason. */
    public data class Err(val error: TransitionError) : TransitionResult
}
