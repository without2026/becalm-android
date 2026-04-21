package com.becalm.android.domain.commitment

/**
 * Events that drive the [CommitmentStateMachine].
 *
 * Each subtype represents a user action (or, for [MarkOverdue], a system-internal
 * sweep) that may trigger a state transition. Illegal event/state combinations are
 * rejected by [CommitmentStateMachine.transition] and surface as
 * [TransitionError.IllegalTransition].
 *
 * Spec alignment: `.spec/commitment-management.spec.yml` CMT-005 / CMT-006 / CMT-007 /
 * CMT-011 / CMT-012.
 */
public sealed interface CommitmentEvent {

    /** User pressed [리마인드]. Transitions PENDING → REMINDED. (CMT-005) */
    public data object Remind : CommitmentEvent

    /** User pressed [팔로업]. Transitions PENDING or REMINDED → FOLLOWED_UP. (CMT-006) */
    public data object FollowUp : CommitmentEvent

    /**
     * User pressed [완료]. Transitions PENDING, REMINDED, FOLLOWED_UP, or OVERDUE →
     * COMPLETED. COMPLETED is terminal. (CMT-007)
     */
    public data object Complete : CommitmentEvent

    /**
     * User pressed [취소]. Transitions PENDING, REMINDED, FOLLOWED_UP, or OVERDUE →
     * CANCELLED. CANCELLED is terminal. (CMT-012)
     */
    public data object Cancel : CommitmentEvent

    /**
     * System auto-sweep event — the commitment is past its due_at window.
     * Transitions PENDING, REMINDED, or FOLLOWED_UP → OVERDUE.
     *
     * CMT-011 invariant: overdue is **system-only**. This event must be raised only by
     * domain/worker code (e.g. `OverdueSweepWorker`); the user-facing ViewModel layer
     * must never trigger it. Enforced by convention + code review; Kotlin does not allow
     * `internal` modifier on nested members of a sealed interface, so visibility alone
     * cannot enforce this.
     */
    public data object MarkOverdue : CommitmentEvent
}
