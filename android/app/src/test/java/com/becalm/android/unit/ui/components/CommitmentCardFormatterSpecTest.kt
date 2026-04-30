package com.becalm.android.ui.components

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommitmentCardFormatterSpecTest {

    private val kst = TimeZone.of("Asia/Seoul")

    @Test
    fun `CMT-004 exact and approximate D-day labels follow grammar`() {
        // Exact future / today / overdue grammar unchanged.
        assertEquals("D-0", formatDayBadgeLabel(days = 0, approximate = false))
        assertEquals("D-3", formatDayBadgeLabel(days = 3, approximate = false))
        assertEquals("D+2", formatDayBadgeLabel(days = -2, approximate = false))
        // Approximate variant uses Korean "약" prefix (about) per impeccable
        // critique R4 — the original `D~N` tilde form was recall-heavy.
        assertEquals("약 D-0", formatDayBadgeLabel(days = 0, approximate = true))
        assertEquals("약 D-3", formatDayBadgeLabel(days = 3, approximate = true))
        // Overdue + approximate still collapses to exact overdue — date is known
        // to have passed regardless of how it was inferred.
        assertEquals("D+2", formatDayBadgeLabel(days = -2, approximate = true))
    }

    @Test
    fun `CMT-004 daysUntilInKst respects KST date boundaries`() {
        assertNull(
            daysUntilInKst(
                dueAt = null,
                now = Instant.parse("2026-04-18T00:00:00+09:00"),
                zone = kst,
            ),
        )
        assertEquals(
            1,
            daysUntilInKst(
                dueAt = Instant.parse("2026-04-18T00:00:00+09:00"),
                now = Instant.parse("2026-04-17T23:30:00+09:00"),
                zone = kst,
            ),
        )
        assertEquals(
            0,
            daysUntilInKst(
                dueAt = Instant.parse("2026-04-18T10:00:00+09:00"),
                now = Instant.parse("2026-04-18T00:30:00+09:00"),
                zone = kst,
            ),
        )
        assertEquals(
            -2,
            daysUntilInKst(
                dueAt = Instant.parse("2026-04-18T15:00:00+09:00"),
                now = Instant.parse("2026-04-20T09:00:00+09:00"),
                zone = kst,
            ),
        )
    }
}
