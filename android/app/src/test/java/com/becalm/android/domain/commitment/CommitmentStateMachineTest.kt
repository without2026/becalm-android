package com.becalm.android.domain.commitment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage for the spec-aligned [CommitmentStateMachine].
 *
 * Tests are organised as:
 *  1. Every legal edge listed in the state-machine KDoc / spec table
 *     (commitment-management.spec.yml CMT-005 / 006 / 007 / 011 / 012).
 *  2. Rejection of representative illegal transitions from terminal states and from
 *     non-terminal states that do not accept a given event.
 *  3. [CommitmentEvent.MarkOverdue] is the internal system-only event — it is legal
 *     from non-terminal non-overdue states and illegal from OVERDUE itself and from
 *     both terminal states.
 */
class CommitmentStateMachineTest {

    private fun assertOk(
        expected: CommitmentState,
        result: TransitionResult,
    ) {
        assertTrue("expected Ok but was $result", result is TransitionResult.Ok)
        assertEquals(expected, (result as TransitionResult.Ok).state)
    }

    private fun assertIllegal(
        from: CommitmentState,
        event: CommitmentEvent,
        result: TransitionResult,
    ) {
        assertTrue(
            "Expected Err(IllegalTransition) for $from + $event, was $result",
            result is TransitionResult.Err,
        )
        val error = (result as TransitionResult.Err).error
        assertTrue(
            "Expected IllegalTransition for $from + $event, was $error",
            error is TransitionError.IllegalTransition,
        )
        val illegal = error as TransitionError.IllegalTransition
        assertEquals(from, illegal.from)
        assertEquals(event, illegal.event)
    }

    // ── PENDING: every user + system event legal ─────────────────────────────

    @Test
    fun `PENDING + Remind transitions to REMINDED`() {
        assertOk(
            CommitmentState.REMINDED,
            CommitmentStateMachine.transition(CommitmentState.PENDING, CommitmentEvent.Remind),
        )
    }

    @Test
    fun `PENDING + FollowUp transitions to FOLLOWED_UP`() {
        assertOk(
            CommitmentState.FOLLOWED_UP,
            CommitmentStateMachine.transition(CommitmentState.PENDING, CommitmentEvent.FollowUp),
        )
    }

    @Test
    fun `PENDING + Complete transitions to COMPLETED`() {
        assertOk(
            CommitmentState.COMPLETED,
            CommitmentStateMachine.transition(CommitmentState.PENDING, CommitmentEvent.Complete),
        )
    }

    @Test
    fun `PENDING + Cancel transitions to CANCELLED`() {
        assertOk(
            CommitmentState.CANCELLED,
            CommitmentStateMachine.transition(CommitmentState.PENDING, CommitmentEvent.Cancel),
        )
    }

    @Test
    fun `PENDING + MarkOverdue transitions to OVERDUE`() {
        assertOk(
            CommitmentState.OVERDUE,
            CommitmentStateMachine.transition(CommitmentState.PENDING, CommitmentEvent.MarkOverdue),
        )
    }

    // ── REMINDED: FollowUp / Complete / Cancel / MarkOverdue legal ───────────

    @Test
    fun `REMINDED + FollowUp transitions to FOLLOWED_UP`() {
        assertOk(
            CommitmentState.FOLLOWED_UP,
            CommitmentStateMachine.transition(CommitmentState.REMINDED, CommitmentEvent.FollowUp),
        )
    }

    @Test
    fun `REMINDED + Complete transitions to COMPLETED`() {
        assertOk(
            CommitmentState.COMPLETED,
            CommitmentStateMachine.transition(CommitmentState.REMINDED, CommitmentEvent.Complete),
        )
    }

    @Test
    fun `REMINDED + Cancel transitions to CANCELLED`() {
        assertOk(
            CommitmentState.CANCELLED,
            CommitmentStateMachine.transition(CommitmentState.REMINDED, CommitmentEvent.Cancel),
        )
    }

    @Test
    fun `REMINDED + MarkOverdue transitions to OVERDUE`() {
        assertOk(
            CommitmentState.OVERDUE,
            CommitmentStateMachine.transition(CommitmentState.REMINDED, CommitmentEvent.MarkOverdue),
        )
    }

    @Test
    fun `REMINDED + Remind is illegal (no self-loop)`() {
        assertIllegal(
            CommitmentState.REMINDED,
            CommitmentEvent.Remind,
            CommitmentStateMachine.transition(CommitmentState.REMINDED, CommitmentEvent.Remind),
        )
    }

    // ── FOLLOWED_UP: Complete / Cancel / MarkOverdue legal ───────────────────

    @Test
    fun `FOLLOWED_UP + Complete transitions to COMPLETED`() {
        assertOk(
            CommitmentState.COMPLETED,
            CommitmentStateMachine.transition(CommitmentState.FOLLOWED_UP, CommitmentEvent.Complete),
        )
    }

    @Test
    fun `FOLLOWED_UP + Cancel transitions to CANCELLED`() {
        assertOk(
            CommitmentState.CANCELLED,
            CommitmentStateMachine.transition(CommitmentState.FOLLOWED_UP, CommitmentEvent.Cancel),
        )
    }

    @Test
    fun `FOLLOWED_UP + MarkOverdue transitions to OVERDUE`() {
        assertOk(
            CommitmentState.OVERDUE,
            CommitmentStateMachine.transition(CommitmentState.FOLLOWED_UP, CommitmentEvent.MarkOverdue),
        )
    }

    @Test
    fun `FOLLOWED_UP + Remind is illegal`() {
        assertIllegal(
            CommitmentState.FOLLOWED_UP,
            CommitmentEvent.Remind,
            CommitmentStateMachine.transition(CommitmentState.FOLLOWED_UP, CommitmentEvent.Remind),
        )
    }

    @Test
    fun `FOLLOWED_UP + FollowUp is illegal (no self-loop)`() {
        assertIllegal(
            CommitmentState.FOLLOWED_UP,
            CommitmentEvent.FollowUp,
            CommitmentStateMachine.transition(CommitmentState.FOLLOWED_UP, CommitmentEvent.FollowUp),
        )
    }

    // ── OVERDUE: Complete / Cancel legal; other events illegal ───────────────

    @Test
    fun `OVERDUE + Complete transitions to COMPLETED`() {
        assertOk(
            CommitmentState.COMPLETED,
            CommitmentStateMachine.transition(CommitmentState.OVERDUE, CommitmentEvent.Complete),
        )
    }

    @Test
    fun `OVERDUE + Cancel transitions to CANCELLED`() {
        assertOk(
            CommitmentState.CANCELLED,
            CommitmentStateMachine.transition(CommitmentState.OVERDUE, CommitmentEvent.Cancel),
        )
    }

    @Test
    fun `OVERDUE + Remind is illegal`() {
        assertIllegal(
            CommitmentState.OVERDUE,
            CommitmentEvent.Remind,
            CommitmentStateMachine.transition(CommitmentState.OVERDUE, CommitmentEvent.Remind),
        )
    }

    @Test
    fun `OVERDUE + FollowUp is illegal`() {
        assertIllegal(
            CommitmentState.OVERDUE,
            CommitmentEvent.FollowUp,
            CommitmentStateMachine.transition(CommitmentState.OVERDUE, CommitmentEvent.FollowUp),
        )
    }

    @Test
    fun `OVERDUE + MarkOverdue is illegal (no self-loop)`() {
        assertIllegal(
            CommitmentState.OVERDUE,
            CommitmentEvent.MarkOverdue,
            CommitmentStateMachine.transition(CommitmentState.OVERDUE, CommitmentEvent.MarkOverdue),
        )
    }

    // ── COMPLETED: terminal — every event illegal ────────────────────────────

    @Test
    fun `COMPLETED + Remind is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.COMPLETED,
            CommitmentEvent.Remind,
            CommitmentStateMachine.transition(CommitmentState.COMPLETED, CommitmentEvent.Remind),
        )
    }

    @Test
    fun `COMPLETED + FollowUp is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.COMPLETED,
            CommitmentEvent.FollowUp,
            CommitmentStateMachine.transition(CommitmentState.COMPLETED, CommitmentEvent.FollowUp),
        )
    }

    @Test
    fun `COMPLETED + Complete is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.COMPLETED,
            CommitmentEvent.Complete,
            CommitmentStateMachine.transition(CommitmentState.COMPLETED, CommitmentEvent.Complete),
        )
    }

    @Test
    fun `COMPLETED + Cancel is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.COMPLETED,
            CommitmentEvent.Cancel,
            CommitmentStateMachine.transition(CommitmentState.COMPLETED, CommitmentEvent.Cancel),
        )
    }

    @Test
    fun `COMPLETED + MarkOverdue is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.COMPLETED,
            CommitmentEvent.MarkOverdue,
            CommitmentStateMachine.transition(CommitmentState.COMPLETED, CommitmentEvent.MarkOverdue),
        )
    }

    // ── CANCELLED: terminal — every event illegal ────────────────────────────

    @Test
    fun `CANCELLED + Remind is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.CANCELLED,
            CommitmentEvent.Remind,
            CommitmentStateMachine.transition(CommitmentState.CANCELLED, CommitmentEvent.Remind),
        )
    }

    @Test
    fun `CANCELLED + Complete is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.CANCELLED,
            CommitmentEvent.Complete,
            CommitmentStateMachine.transition(CommitmentState.CANCELLED, CommitmentEvent.Complete),
        )
    }

    @Test
    fun `CANCELLED + Cancel is illegal (terminal)`() {
        assertIllegal(
            CommitmentState.CANCELLED,
            CommitmentEvent.Cancel,
            CommitmentStateMachine.transition(CommitmentState.CANCELLED, CommitmentEvent.Cancel),
        )
    }

    // ── Round-trip happy path: PENDING → REMINDED → FOLLOWED_UP → COMPLETED ──

    @Test
    fun `full happy path PENDING REMINDED FOLLOWED_UP COMPLETED`() {
        val step1 = CommitmentStateMachine.transition(CommitmentState.PENDING, CommitmentEvent.Remind)
        assertOk(CommitmentState.REMINDED, step1)
        val step2 = CommitmentStateMachine.transition(CommitmentState.REMINDED, CommitmentEvent.FollowUp)
        assertOk(CommitmentState.FOLLOWED_UP, step2)
        val step3 = CommitmentStateMachine.transition(CommitmentState.FOLLOWED_UP, CommitmentEvent.Complete)
        assertOk(CommitmentState.COMPLETED, step3)
    }

    // ── CommitmentState.fromWire safety ──────────────────────────────────────

    @Test
    fun `fromWire maps known wire values to spec enum`() {
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire("pending"))
        assertEquals(CommitmentState.REMINDED, CommitmentState.fromWire("reminded"))
        assertEquals(CommitmentState.FOLLOWED_UP, CommitmentState.fromWire("followed_up"))
        assertEquals(CommitmentState.COMPLETED, CommitmentState.fromWire("completed"))
        assertEquals(CommitmentState.OVERDUE, CommitmentState.fromWire("overdue"))
        assertEquals(CommitmentState.CANCELLED, CommitmentState.fromWire("cancelled"))
    }

    @Test
    fun `fromWire falls back to PENDING for unknown values`() {
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire(""))
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire("confirmed"))
        assertEquals(CommitmentState.PENDING, CommitmentState.fromWire("SOMETHING_NEW"))
    }
}
