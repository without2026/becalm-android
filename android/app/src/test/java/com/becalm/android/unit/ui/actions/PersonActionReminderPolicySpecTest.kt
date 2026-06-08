package com.becalm.android.unit.ui.actions

import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.defaultReminderSnoozeUntil
import com.becalm.android.ui.actions.shouldOfferReminder
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonActionReminderPolicySpecTest {

    @Test
    fun `reminder is offered only for due take actions`() {
        assertTrue(
            action(
                dueAt = Instant.parse("2026-06-05T03:00:00Z"),
                reasonCodes = listOf("direction:take", "waiting_on"),
            ).shouldOfferReminder(),
        )
        assertFalse(
            action(
                dueAt = Instant.parse("2026-06-05T03:00:00Z"),
                reasonCodes = listOf("direction:give"),
            ).shouldOfferReminder(),
        )
        assertFalse(
            action(
                dueAt = null,
                reasonCodes = listOf("direction:take", "waiting_on"),
            ).shouldOfferReminder(),
        )
    }

    @Test
    fun `default snooze is one day before due or one hour from now when lead time passed`() {
        val now = Instant.parse("2026-06-03T03:00:00Z")

        assertEquals(
            Instant.parse("2026-06-04T03:00:00Z"),
            action(dueAt = Instant.parse("2026-06-05T03:00:00Z")).defaultReminderSnoozeUntil(now),
        )
        assertEquals(
            Instant.parse("2026-06-03T04:00:00Z"),
            action(dueAt = Instant.parse("2026-06-03T03:30:00Z")).defaultReminderSnoozeUntil(now),
        )
    }

    private fun action(
        dueAt: Instant?,
        reasonCodes: List<String> = listOf("direction:take", "waiting_on"),
    ): PersonActionItemUi = PersonActionItemUi(
        id = "pa-1",
        personId = "person-1",
        personDisplayName = "Jane Kim",
        actionKind = "follow_up",
        title = "Jane Kim follow-up",
        primaryVerb = "팔로업",
        shortReason = "waiting on reply",
        commitmentId = "commitment-1",
        calendarEventId = null,
        sourceEventId = "source-1",
        sourceType = "gmail",
        sourceRef = "gmail-1",
        dueAt = dueAt,
        dueHint = null,
        urgencyScore = 90.0,
        confidence = 0.9,
        reasonCodes = reasonCodes,
        evidence = null,
    )
}
