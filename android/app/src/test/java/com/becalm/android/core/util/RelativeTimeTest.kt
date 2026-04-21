package com.becalm.android.core.util

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function bucket boundary tests for [relativeSinceKst].
 *
 * Covers each inflection of the bucket ladder: `JustNow → Minutes → Hours →
 * TodayAt` — plus two defensive cases (clock skew, exact-minute boundary).
 */
class RelativeTimeTest {

    private val now = Instant.parse("2026-04-22T09:00:00Z") // 18:00 KST

    @Test
    fun `exactly now buckets to JustNow`() {
        assertEquals(RelativeSince.JustNow, relativeSinceKst(now = now, past = now))
    }

    @Test
    fun `30 seconds ago still JustNow`() {
        assertEquals(RelativeSince.JustNow, relativeSinceKst(now = now, past = now - 30.seconds))
    }

    @Test
    fun `59 seconds ago still JustNow`() {
        assertEquals(RelativeSince.JustNow, relativeSinceKst(now = now, past = now - 59.seconds))
    }

    @Test
    fun `1 minute exactly tips into Minutes bucket`() {
        assertEquals(RelativeSince.Minutes(n = 1), relativeSinceKst(now = now, past = now - 1.minutes))
    }

    @Test
    fun `59 minutes stays in Minutes bucket`() {
        assertEquals(RelativeSince.Minutes(n = 59), relativeSinceKst(now = now, past = now - 59.minutes))
    }

    @Test
    fun `1 hour exactly tips into Hours bucket`() {
        assertEquals(RelativeSince.Hours(n = 1), relativeSinceKst(now = now, past = now - 1.hours))
    }

    @Test
    fun `23 hours stays in Hours bucket`() {
        assertEquals(RelativeSince.Hours(n = 23), relativeSinceKst(now = now, past = now - 23.hours))
    }

    @Test
    fun `24 hours promotes to TodayAt with HH mm`() {
        val bucket = relativeSinceKst(now = now, past = now - 24.hours)
        assertTrue("expected TodayAt, got $bucket", bucket is RelativeSince.TodayAt)
        val expected = Instant.parse("2026-04-21T09:00:00Z") // 2026-04-21 18:00 KST
        val want = expected.toHhMmKst()
        assertEquals(RelativeSince.TodayAt(hhmm = want), bucket)
    }

    @Test
    fun `future past instant collapses to JustNow`() {
        val future = now + 5.minutes
        assertEquals(RelativeSince.JustNow, relativeSinceKst(now = now, past = future))
    }
}

/** Test helper — format [this] as `HH:mm` in KST, zero-padded. */
private fun Instant.toHhMmKst(): String {
    val ldt = this.toLocalDateTime(KST)
    val hh = ldt.hour.toString().padStart(2, '0')
    val mm = ldt.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}
