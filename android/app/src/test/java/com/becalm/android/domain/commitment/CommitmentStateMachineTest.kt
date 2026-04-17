package com.becalm.android.domain.commitment

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage for [CommitmentStateMachine].
 *
 * Tests are organised as:
 *  1. Valid transitions from each non-terminal state.
 *  2. Rejection of every other event from each state (including both terminal states
 *     rejecting every event).
 *  3. Schedule guard — past/present instants surface [TransitionError.MissingSchedule].
 *
 * Spec alignment: commitment-management.spec.yml CMT-005/6/7 and R5-01
 * (DONE is terminal; no DONE → CONFIRMED edge).
 */
class CommitmentStateMachineTest {

    private fun futureInstant(): Instant = Clock.System.now() + 1.hours

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
        assertEquals(from, (error as TransitionError.IllegalTransition).from)
    }

    // ── Valid transitions from DRAFT ─────────────────────────────────────────

    @Test
    fun `DRAFT + Confirm transitions to CONFIRMED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DRAFT, CommitmentEvent.Confirm)
        assertEquals(TransitionResult.Ok(CommitmentState.CONFIRMED), result)
    }

    @Test
    fun `DRAFT + Dismiss transitions to DISMISSED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DRAFT, CommitmentEvent.Dismiss)
        assertEquals(TransitionResult.Ok(CommitmentState.DISMISSED), result)
    }

    // ── Valid transitions from CONFIRMED ─────────────────────────────────────

    @Test
    fun `CONFIRMED + Schedule transitions to SCHEDULED`() {
        val result = CommitmentStateMachine.transition(
            CommitmentState.CONFIRMED,
            CommitmentEvent.Schedule(at = futureInstant()),
        )
        assertEquals(TransitionResult.Ok(CommitmentState.SCHEDULED), result)
    }

    @Test
    fun `CONFIRMED + MarkDone transitions directly to DONE`() {
        val result = CommitmentStateMachine.transition(CommitmentState.CONFIRMED, CommitmentEvent.MarkDone)
        assertEquals(TransitionResult.Ok(CommitmentState.DONE), result)
    }

    @Test
    fun `CONFIRMED + Dismiss transitions to DISMISSED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.CONFIRMED, CommitmentEvent.Dismiss)
        assertEquals(TransitionResult.Ok(CommitmentState.DISMISSED), result)
    }

    // ── Valid transitions from SCHEDULED ─────────────────────────────────────

    @Test
    fun `SCHEDULED + MarkDone transitions to DONE`() {
        val result = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, CommitmentEvent.MarkDone)
        assertEquals(TransitionResult.Ok(CommitmentState.DONE), result)
    }

    @Test
    fun `SCHEDULED + Dismiss transitions to DISMISSED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, CommitmentEvent.Dismiss)
        assertEquals(TransitionResult.Ok(CommitmentState.DISMISSED), result)
    }

    // ── Full happy path ──────────────────────────────────────────────────────

    @Test
    fun `full DRAFT to DONE happy path via CONFIRMED and SCHEDULED`() {
        val s1 = CommitmentStateMachine.transition(CommitmentState.DRAFT, CommitmentEvent.Confirm)
        assertEquals(TransitionResult.Ok(CommitmentState.CONFIRMED), s1)

        val s2 = CommitmentStateMachine.transition(
            CommitmentState.CONFIRMED,
            CommitmentEvent.Schedule(at = futureInstant()),
        )
        assertEquals(TransitionResult.Ok(CommitmentState.SCHEDULED), s2)

        val s3 = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, CommitmentEvent.MarkDone)
        assertEquals(TransitionResult.Ok(CommitmentState.DONE), s3)
    }

    // ── Illegal transitions from DRAFT ───────────────────────────────────────

    @Test
    fun `DRAFT + Schedule returns IllegalTransition`() {
        val event = CommitmentEvent.Schedule(at = futureInstant())
        val result = CommitmentStateMachine.transition(CommitmentState.DRAFT, event)
        assertIllegal(CommitmentState.DRAFT, event, result)
    }

    @Test
    fun `DRAFT + MarkDone returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DRAFT, CommitmentEvent.MarkDone)
        assertIllegal(CommitmentState.DRAFT, CommitmentEvent.MarkDone, result)
    }

    // ── Illegal transitions from CONFIRMED ───────────────────────────────────

    @Test
    fun `CONFIRMED + Confirm returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.CONFIRMED, CommitmentEvent.Confirm)
        assertIllegal(CommitmentState.CONFIRMED, CommitmentEvent.Confirm, result)
    }

    // ── Illegal transitions from SCHEDULED ───────────────────────────────────

    @Test
    fun `SCHEDULED + Confirm returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, CommitmentEvent.Confirm)
        assertIllegal(CommitmentState.SCHEDULED, CommitmentEvent.Confirm, result)
    }

    @Test
    fun `SCHEDULED + Schedule returns IllegalTransition`() {
        val event = CommitmentEvent.Schedule(at = futureInstant())
        val result = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, event)
        assertIllegal(CommitmentState.SCHEDULED, event, result)
    }

    // ── DONE is terminal (R5-01 / spec CMT-007) ──────────────────────────────

    @Test
    fun `DONE + Confirm returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DONE, CommitmentEvent.Confirm)
        assertIllegal(CommitmentState.DONE, CommitmentEvent.Confirm, result)
    }

    @Test
    fun `DONE + Schedule returns IllegalTransition`() {
        val event = CommitmentEvent.Schedule(at = futureInstant())
        val result = CommitmentStateMachine.transition(CommitmentState.DONE, event)
        assertIllegal(CommitmentState.DONE, event, result)
    }

    @Test
    fun `DONE + MarkDone returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DONE, CommitmentEvent.MarkDone)
        assertIllegal(CommitmentState.DONE, CommitmentEvent.MarkDone, result)
    }

    @Test
    fun `DONE + Dismiss returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DONE, CommitmentEvent.Dismiss)
        assertIllegal(CommitmentState.DONE, CommitmentEvent.Dismiss, result)
    }

    // ── DISMISSED is terminal ────────────────────────────────────────────────

    @Test
    fun `DISMISSED + Confirm returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DISMISSED, CommitmentEvent.Confirm)
        assertIllegal(CommitmentState.DISMISSED, CommitmentEvent.Confirm, result)
    }

    @Test
    fun `DISMISSED + Schedule returns IllegalTransition`() {
        val event = CommitmentEvent.Schedule(at = futureInstant())
        val result = CommitmentStateMachine.transition(CommitmentState.DISMISSED, event)
        assertIllegal(CommitmentState.DISMISSED, event, result)
    }

    @Test
    fun `DISMISSED + MarkDone returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DISMISSED, CommitmentEvent.MarkDone)
        assertIllegal(CommitmentState.DISMISSED, CommitmentEvent.MarkDone, result)
    }

    @Test
    fun `DISMISSED + Dismiss returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DISMISSED, CommitmentEvent.Dismiss)
        assertIllegal(CommitmentState.DISMISSED, CommitmentEvent.Dismiss, result)
    }

    // ── Schedule guard ───────────────────────────────────────────────────────

    @Test
    fun `CONFIRMED + Schedule with past instant returns MissingSchedule`() {
        val pastInstant = Clock.System.now() - 1.seconds
        val result = CommitmentStateMachine.transition(
            CommitmentState.CONFIRMED,
            CommitmentEvent.Schedule(at = pastInstant),
        )
        assertTrue("Expected MissingSchedule for past instant", result is TransitionResult.Err)
        assertEquals(TransitionError.MissingSchedule, (result as TransitionResult.Err).error)
    }
}
