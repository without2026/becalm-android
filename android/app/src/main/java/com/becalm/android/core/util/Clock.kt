package com.becalm.android.core.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Abstraction over the system clock.
 *
 * Inject [Clock] rather than calling `kotlinx.datetime.Clock.System` directly so that tests can
 * supply a deterministic fake implementation without patching global state or using reflection.
 *
 * Downstream consumers must depend on this interface rather than
 * - `kotlinx.datetime.Clock.System.now()`, or
 * - `java.time.LocalDate.now(ZoneOffset.UTC)`
 *
 * so that ViewModels and repositories remain deterministic under test via `FakeClock`.
 */
public interface Clock {

    /** Returns the current moment as an [Instant]. */
    public fun nowInstant(): Instant

    /**
     * Returns today's date in the supplied [zone]. Defaults to [TimeZone.currentSystemDefault],
     * which matches the system-local calendar day; call sites that need UTC must pass
     * [TimeZone.UTC] explicitly rather than relying on `ZoneOffset.UTC` on `java.time.LocalDate`.
     */
    public fun today(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
        nowInstant().toLocalDateTime(zone).date
}

/**
 * Production [Clock] implementation backed by [kotlinx.datetime.Clock.System].
 */
public object SystemClock : Clock {

    override fun nowInstant(): Instant = kotlinx.datetime.Clock.System.now()
}
