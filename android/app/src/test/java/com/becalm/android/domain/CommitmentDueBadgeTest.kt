package com.becalm.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// spec: CMT-004 — D-N badge calculation: D-0(today), D-N(future), D+N(overdue, red)

/**
 * Pure function under test: calculateDueBadge(dueDateEpochDay, todayEpochDay) → String
 * The spec requires:
 *   - due in 2 days → "D-2"
 *   - due today → "D-0"
 *   - 6 days overdue → "D+6"
 */
object DueBadgeCalculator {
    // spec: CMT-004 — badge label from signed day delta
    fun calculate(dueDateEpochDay: Long, todayEpochDay: Long): String {
        val delta = dueDateEpochDay - todayEpochDay
        return when {
            delta > 0 -> "D-$delta"
            delta == 0L -> "D-0"
            else -> "D+${-delta}"
        }
    }

    // spec: CMT-004 — negative delta (overdue) means red badge
    fun isOverdue(dueDateEpochDay: Long, todayEpochDay: Long): Boolean =
        dueDateEpochDay < todayEpochDay
}

class CommitmentDueBadgeTest {

    // spec: CMT-004 — D-2 for due date 2 days in future
    @Test
    fun `badge is D-2 when due in 2 days`() {
        // today = epoch day 100, due = epoch day 102
        val badge = DueBadgeCalculator.calculate(dueDateEpochDay = 102L, todayEpochDay = 100L)
        // spec: CMT-004 — precondition: due 2026-04-18, today 2026-04-16 → "D-2"
        assertEquals("D-2", badge)
    }

    // spec: CMT-004 — D-0 when due today
    @Test
    fun `badge is D-0 when due today`() {
        val badge = DueBadgeCalculator.calculate(dueDateEpochDay = 100L, todayEpochDay = 100L)
        assertEquals("D-0", badge)
    }

    // spec: CMT-004 — D+6 (overdue, red) when 6 days past due
    @Test
    fun `badge is D+6 when 6 days overdue`() {
        // today = epoch day 106, due = epoch day 100 → delta = -6 → "D+6"
        val badge = DueBadgeCalculator.calculate(dueDateEpochDay = 100L, todayEpochDay = 106L)
        assertEquals("D+6", badge)
    }

    // spec: CMT-004 — overdue commitments flag isOverdue=true (for red colour)
    @Test
    fun `isOverdue returns true when past due date`() {
        assertTrue(DueBadgeCalculator.isOverdue(dueDateEpochDay = 94L, todayEpochDay = 100L))
    }

    // spec: CMT-004 — today is not overdue
    @Test
    fun `isOverdue returns false when due today`() {
        val result = DueBadgeCalculator.isOverdue(dueDateEpochDay = 100L, todayEpochDay = 100L)
        assertEquals(false, result)
    }
}
