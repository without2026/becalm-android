package com.becalm.android.domain.commitment

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentStateMachineTest {

    private fun futureInstant(): Instant = Clock.System.now() + 1.hours

    // ── Happy paths ───────────────────────────────────────────────────────────

    @Test
    fun `DRAFT + Confirm transitions to CONFIRMED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DRAFT, CommitmentEvent.Confirm)
        assertEquals(TransitionResult.Ok(CommitmentState.CONFIRMED), result)
    }

    @Test
    fun `CONFIRMED + Schedule transitions to SCHEDULED`() {
        val result = CommitmentStateMachine.transition(
            CommitmentState.CONFIRMED,
            CommitmentEvent.Schedule(at = futureInstant()),
        )
        assertEquals(TransitionResult.Ok(CommitmentState.SCHEDULED), result)
    }

    @Test
    fun `SCHEDULED + MarkDone transitions to DONE`() {
        val result = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, CommitmentEvent.MarkDone)
        assertEquals(TransitionResult.Ok(CommitmentState.DONE), result)
    }

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

    @Test
    fun `DRAFT + Dismiss transitions to DISMISSED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DRAFT, CommitmentEvent.Dismiss)
        assertEquals(TransitionResult.Ok(CommitmentState.DISMISSED), result)
    }

    @Test
    fun `DONE + ReopenFromDone transitions to CONFIRMED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DONE, CommitmentEvent.ReopenFromDone)
        assertEquals(TransitionResult.Ok(CommitmentState.CONFIRMED), result)
    }

    @Test
    fun `CONFIRMED + MarkDone transitions directly to DONE`() {
        val result = CommitmentStateMachine.transition(CommitmentState.CONFIRMED, CommitmentEvent.MarkDone)
        assertEquals(TransitionResult.Ok(CommitmentState.DONE), result)
    }

    @Test
    fun `SCHEDULED + Dismiss transitions to DISMISSED`() {
        val result = CommitmentStateMachine.transition(CommitmentState.SCHEDULED, CommitmentEvent.Dismiss)
        assertEquals(TransitionResult.Ok(CommitmentState.DISMISSED), result)
    }

    // ── Illegal paths ─────────────────────────────────────────────────────────

    @Test
    fun `DISMISSED + Confirm returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DISMISSED, CommitmentEvent.Confirm)
        assertTrue("Expected Err with IllegalTransition", result is TransitionResult.Err)
        val error = (result as TransitionResult.Err).error
        assertTrue(error is TransitionError.IllegalTransition)
        assertEquals(CommitmentState.DISMISSED, (error as TransitionError.IllegalTransition).from)
    }

    @Test
    fun `DISMISSED + MarkDone returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DISMISSED, CommitmentEvent.MarkDone)
        assertTrue(result is TransitionResult.Err)
        assertTrue((result as TransitionResult.Err).error is TransitionError.IllegalTransition)
    }

    @Test
    fun `DRAFT + Schedule returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(
            CommitmentState.DRAFT,
            CommitmentEvent.Schedule(at = futureInstant()),
        )
        assertTrue("Expected Err with IllegalTransition for DRAFT + Schedule", result is TransitionResult.Err)
        val error = (result as TransitionResult.Err).error
        assertTrue(error is TransitionError.IllegalTransition)
        assertEquals(CommitmentState.DRAFT, (error as TransitionError.IllegalTransition).from)
    }

    @Test
    fun `DONE + Confirm returns IllegalTransition`() {
        val result = CommitmentStateMachine.transition(CommitmentState.DONE, CommitmentEvent.Confirm)
        assertTrue(result is TransitionResult.Err)
        val error = (result as TransitionResult.Err).error
        assertTrue(error is TransitionError.IllegalTransition)
        assertEquals(CommitmentState.DONE, (error as TransitionError.IllegalTransition).from)
    }

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
