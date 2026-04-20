package com.becalm.android.core.util

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the [FakeClock] test double.
 *
 * Spec ref: docs/round6-plan.md § 6A.2 — deterministic Clock fake.
 */
class FakeClockTest {

    @Test
    fun `nowInstant returns the configured instant`() {
        val t0 = Instant.parse("2026-04-18T09:00:00Z")
        val clock = FakeClock(t0)
        assertEquals(t0, clock.nowInstant())
    }

    @Test
    fun `today in UTC derives from nowInstant`() {
        val clock = FakeClock(Instant.parse("2026-04-18T09:00:00Z"))
        assertEquals("2026-04-18", clock.today(TimeZone.UTC).toString())
    }

    @Test
    fun `advancing nowInstant is observable`() {
        val clock = FakeClock(Instant.parse("2026-04-18T00:00:00Z"))
        assertEquals("2026-04-18", clock.today(TimeZone.UTC).toString())

        clock.nowInstant = Instant.parse("2026-04-19T00:00:00Z")
        assertEquals("2026-04-19", clock.today(TimeZone.UTC).toString())
    }

    @Test
    fun `today respects the supplied timezone boundary`() {
        // 23:00 UTC on 2026-04-18 is already 2026-04-19 in Asia/Seoul (UTC+9).
        val clock = FakeClock(Instant.parse("2026-04-18T23:00:00Z"))
        assertEquals("2026-04-18", clock.today(TimeZone.UTC).toString())
        assertEquals("2026-04-19", clock.today(TimeZone.of("Asia/Seoul")).toString())
    }
}
