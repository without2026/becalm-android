package com.becalm.android.domain.commitment

import kotlinx.datetime.Instant

/**
 * Events that drive the [CommitmentStateMachine].
 *
 * Each subtype represents a user or system action that may trigger a state transition.
 * Illegal event/state combinations are rejected by [CommitmentStateMachine.transition]
 * and surface as [TransitionError.IllegalTransition].
 */
public sealed interface CommitmentEvent {

    /** The user has acknowledged and confirmed the commitment. Transitions DRAFT → CONFIRMED. */
    public data object Confirm : CommitmentEvent

    /**
     * A follow-up time has been assigned to the commitment.
     *
     * @property at The absolute instant at which the follow-up is scheduled.
     *   Must be non-null and in the future relative to the caller's clock;
     *   violations surface as [TransitionError.MissingSchedule].
     */
    public data class Schedule(val at: Instant) : CommitmentEvent

    /** The commitment has been fulfilled. Transitions CONFIRMED or SCHEDULED → DONE. */
    public data object MarkDone : CommitmentEvent

    /** The commitment has been discarded. Transitions DRAFT, CONFIRMED, or SCHEDULED → DISMISSED. */
    public data object Dismiss : CommitmentEvent

    /** Reopens a completed commitment for continued tracking. Transitions DONE → CONFIRMED. */
    public data object ReopenFromDone : CommitmentEvent
}
