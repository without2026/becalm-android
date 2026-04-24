package com.becalm.android.unit.domain.commitment

import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.CommitmentStateMachine
import com.becalm.android.domain.commitment.TransitionError
import com.becalm.android.domain.commitment.TransitionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CommitmentStateMachineSpecTest {

    @Test
    fun `CMT state machine allows every legal edge`() {
        assertTransition(CommitmentState.PENDING, CommitmentEvent.Remind, CommitmentState.REMINDED)
        assertTransition(CommitmentState.PENDING, CommitmentEvent.FollowUp, CommitmentState.FOLLOWED_UP)
        assertTransition(CommitmentState.PENDING, CommitmentEvent.Complete, CommitmentState.COMPLETED)
        assertTransition(CommitmentState.PENDING, CommitmentEvent.Cancel, CommitmentState.CANCELLED)
        assertTransition(CommitmentState.PENDING, CommitmentEvent.MarkOverdue, CommitmentState.OVERDUE)

        assertTransition(CommitmentState.REMINDED, CommitmentEvent.FollowUp, CommitmentState.FOLLOWED_UP)
        assertTransition(CommitmentState.REMINDED, CommitmentEvent.Complete, CommitmentState.COMPLETED)
        assertTransition(CommitmentState.REMINDED, CommitmentEvent.Cancel, CommitmentState.CANCELLED)
        assertTransition(CommitmentState.REMINDED, CommitmentEvent.MarkOverdue, CommitmentState.OVERDUE)

        assertTransition(CommitmentState.FOLLOWED_UP, CommitmentEvent.Complete, CommitmentState.COMPLETED)
        assertTransition(CommitmentState.FOLLOWED_UP, CommitmentEvent.Cancel, CommitmentState.CANCELLED)
        assertTransition(CommitmentState.FOLLOWED_UP, CommitmentEvent.MarkOverdue, CommitmentState.OVERDUE)

        assertTransition(CommitmentState.OVERDUE, CommitmentEvent.Complete, CommitmentState.COMPLETED)
        assertTransition(CommitmentState.OVERDUE, CommitmentEvent.Cancel, CommitmentState.CANCELLED)
    }

    @Test
    fun `CMT state machine rejects re-remind after already reminded`() {
        assertIllegalTransition(CommitmentState.REMINDED, CommitmentEvent.Remind)
    }

    @Test
    fun `CMT state machine rejects remind and follow-up from overdue`() {
        assertIllegalTransition(CommitmentState.OVERDUE, CommitmentEvent.Remind)
        assertIllegalTransition(CommitmentState.OVERDUE, CommitmentEvent.FollowUp)
        assertIllegalTransition(CommitmentState.OVERDUE, CommitmentEvent.MarkOverdue)
    }

    @Test
    fun `CMT completed is terminal for all user and system events`() {
        assertIllegalTransition(CommitmentState.COMPLETED, CommitmentEvent.Remind)
        assertIllegalTransition(CommitmentState.COMPLETED, CommitmentEvent.FollowUp)
        assertIllegalTransition(CommitmentState.COMPLETED, CommitmentEvent.Complete)
        assertIllegalTransition(CommitmentState.COMPLETED, CommitmentEvent.Cancel)
        assertIllegalTransition(CommitmentState.COMPLETED, CommitmentEvent.MarkOverdue)
    }

    @Test
    fun `CMT cancelled is terminal for all user and system events`() {
        assertIllegalTransition(CommitmentState.CANCELLED, CommitmentEvent.Remind)
        assertIllegalTransition(CommitmentState.CANCELLED, CommitmentEvent.FollowUp)
        assertIllegalTransition(CommitmentState.CANCELLED, CommitmentEvent.Complete)
        assertIllegalTransition(CommitmentState.CANCELLED, CommitmentEvent.Cancel)
        assertIllegalTransition(CommitmentState.CANCELLED, CommitmentEvent.MarkOverdue)
    }

    @Test
    fun `AUTH style forward compatibility fallback maps unknown wire states to pending`() {
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire("future_state"))
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire("FOLLOWED_UP"))
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire(""))
    }

    @Test
    fun `known wire values map exactly`() {
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire("pending"))
        assertEquals(CommitmentState.REMINDED, CommitmentState.fromWire("reminded"))
        assertEquals(CommitmentState.FOLLOWED_UP, CommitmentState.fromWire("followed_up"))
        assertEquals(CommitmentState.COMPLETED, CommitmentState.fromWire("completed"))
        assertEquals(CommitmentState.OVERDUE, CommitmentState.fromWire("overdue"))
        assertEquals(CommitmentState.CANCELLED, CommitmentState.fromWire("cancelled"))
    }

    private fun assertTransition(
        from: CommitmentState,
        event: CommitmentEvent,
        expected: CommitmentState,
    ) {
        assertEquals(TransitionResult.Ok(expected), CommitmentStateMachine.transition(from, event))
    }

    private fun assertIllegalTransition(from: CommitmentState, event: CommitmentEvent) {
        assertEquals(
            TransitionResult.Err(TransitionError.IllegalTransition(from, event)),
            CommitmentStateMachine.transition(from, event),
        )
    }
}
