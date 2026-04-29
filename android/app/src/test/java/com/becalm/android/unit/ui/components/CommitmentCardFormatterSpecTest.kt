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
        assertEquals("D-0", formatDayBadgeLabel(days = 0, approximate = false))
        assertEquals("D~0", formatDayBadgeLabel(days = 0, approximate = true))
        assertEquals("D-3", formatDayBadgeLabel(days = 3, approximate = false))
        assertEquals("D~3", formatDayBadgeLabel(days = 3, approximate = true))
        assertEquals("D+2", formatDayBadgeLabel(days = -2, approximate = false))
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
