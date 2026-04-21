package com.becalm.android.domain.commitment

/**
 * Pure, stateless state machine for the spec-aligned commitment lifecycle.
 *
 * All logic is expressed as a single [transition] function with no side effects.
 * Callers are responsible for persisting the resulting state.
 *
 * ## Legal edges (spec CMT-005/006/007/011/012)
 * ```
 * PENDING     + Remind       → REMINDED
 * PENDING     + FollowUp     → FOLLOWED_UP
 * PENDING     + Complete     → COMPLETED
 * PENDING     + Cancel       → CANCELLED
 * PENDING     + MarkOverdue  → OVERDUE                (internal-only event)
 *
 * REMINDED    + FollowUp     → FOLLOWED_UP
 * REMINDED    + Complete     → COMPLETED
 * REMINDED    + Cancel       → CANCELLED
 * REMINDED    + MarkOverdue  → OVERDUE                (internal-only event)
 *
 * FOLLOWED_UP + Complete     → COMPLETED
 * FOLLOWED_UP + Cancel       → CANCELLED
 * FOLLOWED_UP + MarkOverdue  → OVERDUE                (internal-only event)
 *
 * OVERDUE     + Complete     → COMPLETED
 * OVERDUE     + Cancel       → CANCELLED
 *
 * COMPLETED   + (any)        → IllegalTransition      (terminal)
 * CANCELLED   + (any)        → IllegalTransition      (terminal)
 * ```
 * All other combinations return [TransitionError.IllegalTransition].
 *
 * Per spec CMT-007, COMPLETED is terminal; per CMT-012, CANCELLED is terminal. Once a
 * commitment reaches either state it cannot be reopened by further events (the
 * forthcoming Undo Snackbar path, CMT-013, is out of scope here and will be added via
 * a dedicated plan).
 *
 * Per spec CMT-011, the OVERDUE state is only reachable via the internal
 * [CommitmentEvent.MarkOverdue] event raised by the overdue-sweep worker — the user
 * can never transition *into* OVERDUE manually. From OVERDUE the user may still
 * [완료] or [취소] (CMT-007 / CMT-012) to close out the row.
 */
public object CommitmentStateMachine {

    /**
     * Applies [event] to [current] and returns the resulting state, or a
     * [TransitionError.IllegalTransition] when the transition is not a legal edge.
     *
     * @param current The present lifecycle state of the commitment.
     * @param event   The event to apply.
     * @return [TransitionResult.Ok] with the next [CommitmentState], or
     *   [TransitionResult.Err] with the reason the transition was rejected.
     */
    public fun transition(
        current: CommitmentState,
        event: CommitmentEvent,
    ): TransitionResult {
        val next: CommitmentState? = when (current) {
            CommitmentState.PENDING -> when (event) {
                CommitmentEvent.Remind -> CommitmentState.REMINDED
                CommitmentEvent.FollowUp -> CommitmentState.FOLLOWED_UP
                CommitmentEvent.Complete -> CommitmentState.COMPLETED
                CommitmentEvent.Cancel -> CommitmentState.CANCELLED
                CommitmentEvent.MarkOverdue -> CommitmentState.OVERDUE
            }

            CommitmentState.REMINDED -> when (event) {
                CommitmentEvent.FollowUp -> CommitmentState.FOLLOWED_UP
                CommitmentEvent.Complete -> CommitmentState.COMPLETED
                CommitmentEvent.Cancel -> CommitmentState.CANCELLED
                CommitmentEvent.MarkOverdue -> CommitmentState.OVERDUE
                CommitmentEvent.Remind -> null
            }

            CommitmentState.FOLLOWED_UP -> when (event) {
                CommitmentEvent.Complete -> CommitmentState.COMPLETED
                CommitmentEvent.Cancel -> CommitmentState.CANCELLED
                CommitmentEvent.MarkOverdue -> CommitmentState.OVERDUE
                CommitmentEvent.Remind, CommitmentEvent.FollowUp -> null
            }

            CommitmentState.OVERDUE -> when (event) {
                CommitmentEvent.Complete -> CommitmentState.COMPLETED
                CommitmentEvent.Cancel -> CommitmentState.CANCELLED
                CommitmentEvent.Remind,
                CommitmentEvent.FollowUp,
                CommitmentEvent.MarkOverdue,
                -> null
            }

            // Terminal — every event is illegal.
            CommitmentState.COMPLETED, CommitmentState.CANCELLED -> null
        }
        return if (next != null) {
            TransitionResult.Ok(next)
        } else {
            TransitionResult.Err(TransitionError.IllegalTransition(current, event))
        }
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
