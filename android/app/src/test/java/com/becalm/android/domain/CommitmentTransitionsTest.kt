package com.becalm.android.domain

import com.becalm.android.data.local.entities.Commitment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// spec: CMT-005..CMT-007 — commitment action_state transition guard

class CommitmentTransitionsTest {

    // spec: CMT-005 — pending → reminded is allowed
    @Test
    fun `transition_pendingToReminded_allowed`() {
        assertTrue(CommitmentTransitions.isValid(
            Commitment.ActionState.PENDING,
            Commitment.ActionState.REMINDED
        ))
    }

    // spec: CMT-006 — pending → followed_up is allowed
    @Test
    fun `transition_pendingToFollowedUp_allowed`() {
        assertTrue(CommitmentTransitions.isValid(
            Commitment.ActionState.PENDING,
            Commitment.ActionState.FOLLOWED_UP
        ))
    }

    // spec: CMT-007 — pending → completed is allowed
    @Test
    fun `transition_pendingToCompleted_allowed`() {
        assertTrue(CommitmentTransitions.isValid(
            Commitment.ActionState.PENDING,
            Commitment.ActionState.COMPLETED
        ))
    }

    // spec: CMT-006 — reminded → followed_up is allowed
    @Test
    fun `transition_remindedToFollowedUp_allowed`() {
        assertTrue(CommitmentTransitions.isValid(
            Commitment.ActionState.REMINDED,
            Commitment.ActionState.FOLLOWED_UP
        ))
    }

    // spec: CMT-007 — reminded → completed is allowed
    @Test
    fun `transition_remindedToCompleted_allowed`() {
        assertTrue(CommitmentTransitions.isValid(
            Commitment.ActionState.REMINDED,
            Commitment.ActionState.COMPLETED
        ))
    }

    // spec: CMT-007 — followed_up → completed is allowed
    @Test
    fun `transition_followedUpToCompleted_allowed`() {
        assertTrue(CommitmentTransitions.isValid(
            Commitment.ActionState.FOLLOWED_UP,
            Commitment.ActionState.COMPLETED
        ))
    }

    // completed is terminal — no exits allowed
    @Test
    fun `transition_completedToPending_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.COMPLETED,
            Commitment.ActionState.PENDING
        ))
    }

    @Test
    fun `transition_completedToReminded_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.COMPLETED,
            Commitment.ActionState.REMINDED
        ))
    }

    @Test
    fun `transition_completedToFollowedUp_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.COMPLETED,
            Commitment.ActionState.FOLLOWED_UP
        ))
    }

    // No backward transitions allowed
    @Test
    fun `transition_followedUpToReminded_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.FOLLOWED_UP,
            Commitment.ActionState.REMINDED
        ))
    }

    @Test
    fun `transition_remindedToPending_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.REMINDED,
            Commitment.ActionState.PENDING
        ))
    }

    // Self-transitions rejected (no no-op transitions)
    @Test
    fun `transition_pendingToPending_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.PENDING,
            Commitment.ActionState.PENDING
        ))
    }

    @Test
    fun `transition_completedToCompleted_rejected`() {
        assertFalse(CommitmentTransitions.isValid(
            Commitment.ActionState.COMPLETED,
            Commitment.ActionState.COMPLETED
        ))
    }

    // Unknown states are rejected
    @Test
    fun `transition_unknownFromState_rejected`() {
        assertFalse(CommitmentTransitions.isValid("unknown_state", Commitment.ActionState.PENDING))
    }
}
