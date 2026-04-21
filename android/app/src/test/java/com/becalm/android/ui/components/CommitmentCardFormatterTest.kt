package com.becalm.android.ui.components

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the D-N badge label grammar defined in
 * commitment-management.spec.yml:39-43. The formatter is the only user-visible
 * carrier of that spec; any drift must be caught here before reaching the UI.
 *
 * Exact grammar:
 *  - `D-0`   same-day exact
 *  - `D-N`   future in N days (N > 0) exact
 *  - `D+N`   overdue by N days (N > 0)
 *  - `D~N`   approximate variant — tilde between `D` and count
 */
class CommitmentCardFormatterTest {

    @Test
    fun `today exact renders D-0`() {
        assertEquals("D-0", formatDayBadgeLabel(days = 0, approximate = false))
    }

    @Test
    fun `today approximate renders D~0`() {
        assertEquals("D~0", formatDayBadgeLabel(days = 0, approximate = true))
    }

    @Test
    fun `future three days exact renders D-3`() {
        assertEquals("D-3", formatDayBadgeLabel(days = 3, approximate = false))
    }

    @Test
    fun `future three days approximate renders D~3`() {
        assertEquals("D~3", formatDayBadgeLabel(days = 3, approximate = true))
    }

    @Test
    fun `overdue two days renders D+2`() {
        assertEquals("D+2", formatDayBadgeLabel(days = -2, approximate = false))
    }

    @Test
    fun `overdue + approximate falls back to exact overdue form`() {
        // Spec §39-43 does not enumerate an "approximate-overdue" shape; the date
        // has already passed, so the exact overdue form is the only correct output.
        assertEquals("D+2", formatDayBadgeLabel(days = -2, approximate = true))
    }

    // ─── shouldShowDueHint ────────────────────────────────────────────────────
    // commitment-management.spec.yml:9,13 — auxiliary due-hint display is tied
    // to `due_is_approximate=true` only. Exact deadlines must NOT render the
    // raw hint even once the backend populates `due_hint` consistently.

    @Test
    fun `hint renders when approximate and hint is non-blank`() {
        assertTrue(shouldShowDueHint(dueIsApproximate = true, dueHint = "월말"))
    }

    @Test
    fun `hint hidden when deadline is exact even if hint is non-null`() {
        assertFalse(shouldShowDueHint(dueIsApproximate = false, dueHint = "월말"))
    }

    @Test
    fun `hint hidden when hint is null`() {
        assertFalse(shouldShowDueHint(dueIsApproximate = true, dueHint = null))
        assertFalse(shouldShowDueHint(dueIsApproximate = false, dueHint = null))
    }

    @Test
    fun `hint hidden when hint is blank`() {
        // Blank strings would render as an empty line; treat as absent.
        assertFalse(shouldShowDueHint(dueIsApproximate = true, dueHint = ""))
        assertFalse(shouldShowDueHint(dueIsApproximate = true, dueHint = "   "))
    }

    // ─── daysUntilInKst — KST midnight boundary ────────────────────────────────
    // Pins commitment-management.spec.yml:40 "KST local date" contract. The
    // historical regression (CommitmentCard.kt had `JLocalDate.now(ZoneOffset.UTC)`)
    // would misreport one day for any dueAt crossed during KST 00:00–09:00,
    // because that window is still the prior calendar date in UTC.

    private val kst = TimeZone.of("Asia/Seoul")

    @Test
    fun `daysUntilInKst returns null when dueAt is null`() {
        assertNull(daysUntilInKst(dueAt = null, now = Instant.parse("2026-04-18T00:00:00+09:00"), zone = kst))
    }

    @Test
    fun `daysUntilInKst reports D-1 just before KST midnight for next-day due`() {
        // Now: KST 2026-04-17 23:30 (= UTC 2026-04-17 14:30). Due: KST 2026-04-18 00:00.
        // KST boundary → tomorrow → 1. Under UTC this would incorrectly return 0.
        val now = Instant.parse("2026-04-17T23:30:00+09:00")
        val due = Instant.parse("2026-04-18T00:00:00+09:00")
        assertEquals(1, daysUntilInKst(dueAt = due, now = now, zone = kst))
    }

    @Test
    fun `daysUntilInKst reports D-0 just after KST midnight for same-day due`() {
        // Now: KST 2026-04-18 00:30. Due: KST 2026-04-18 10:00. Same KST date → 0.
        // Under UTC (where "today" is still 2026-04-17) this would return 1.
        val now = Instant.parse("2026-04-18T00:30:00+09:00")
        val due = Instant.parse("2026-04-18T10:00:00+09:00")
        assertEquals(0, daysUntilInKst(dueAt = due, now = now, zone = kst))
    }

    @Test
    fun `daysUntilInKst reports D+N for overdue`() {
        val now = Instant.parse("2026-04-20T09:00:00+09:00")
        val due = Instant.parse("2026-04-18T15:00:00+09:00")
        assertEquals(-2, daysUntilInKst(dueAt = due, now = now, zone = kst))
    }
}
