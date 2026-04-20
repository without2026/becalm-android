package com.becalm.android.core.util

import kotlinx.datetime.Instant

/**
 * Test double for [Clock] that returns a caller-controlled [Instant].
 *
 * Usage:
 * ```
 * val clock = FakeClock(Instant.parse("2026-04-18T09:00:00Z"))
 * val vm = CommitmentViewModel(clock = clock, ...)
 * clock.nowInstant = Instant.parse("2026-04-18T10:00:00Z") // advance time
 * ```
 *
 * [today] is inherited from [Clock] and is therefore derived from [nowInstant] + the caller's
 * requested [kotlinx.datetime.TimeZone], which matches production behaviour and lets tests
 * exercise timezone-boundary edge cases without touching global state.
 */
public class FakeClock(
    public var nowInstant: Instant = Instant.fromEpochMilliseconds(0L),
) : Clock {

    override fun nowInstant(): Instant = nowInstant
}
