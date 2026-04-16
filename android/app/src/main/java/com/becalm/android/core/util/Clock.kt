package com.becalm.android.core.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Abstraction over the system clock.
 *
 * Inject [Clock] rather than calling `kotlinx.datetime.Clock.System` directly so that tests can
 * supply a deterministic [FakeClock] without patching global state or using reflection.
 */
public interface Clock {

    /** Returns the current moment as an [Instant]. */
    public fun nowInstant(): Instant

    /** Returns the current moment as a [LocalDateTime] in the device's default time zone. */
    public fun nowLocalDateTime(): LocalDateTime

    /** Returns today's [LocalDate] in the device's default time zone. */
    public fun nowLocalDate(): LocalDate

    /** Returns the current time as milliseconds since the Unix epoch. */
    public fun nowEpochMillis(): Long
}

/**
 * Production [Clock] implementation backed by [kotlinx.datetime.Clock.System].
 */
public object SystemClock : Clock {

    override fun nowInstant(): Instant = kotlinx.datetime.Clock.System.now()

    override fun nowLocalDateTime(): LocalDateTime =
        nowInstant().toLocalDateTime(TimeZone.currentSystemDefault())

    override fun nowLocalDate(): LocalDate = nowLocalDateTime().date

    override fun nowEpochMillis(): Long = nowInstant().toEpochMilliseconds()
}
