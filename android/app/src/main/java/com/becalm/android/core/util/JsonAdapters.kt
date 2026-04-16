package com.becalm.android.core.util

import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * Moshi adapter for [Instant]. Serializes as ISO-8601 with `Z` UTC offset (e.g. `"2026-04-16T05:30:00Z"`).
 */
public class InstantAdapter {
    /** Converts [Instant] to its ISO-8601 string representation. */
    @ToJson
    public fun toJson(value: Instant): String = value.toString()

    /** Parses an ISO-8601 string into an [Instant]. */
    @FromJson
    public fun fromJson(value: String): Instant = Instant.parse(value)
}

/**
 * Moshi adapter for [LocalDate]. Serializes as `"YYYY-MM-DD"` (e.g. `"2026-04-16"`).
 */
public class LocalDateAdapter {
    /** Converts [LocalDate] to its ISO-8601 date string. */
    @ToJson
    public fun toJson(value: LocalDate): String = value.toString()

    /** Parses a `"YYYY-MM-DD"` string into a [LocalDate]. */
    @FromJson
    public fun fromJson(value: String): LocalDate = LocalDate.parse(value)
}

/**
 * Moshi adapter for [LocalDateTime]. Serializes as ISO-8601 local datetime (e.g. `"2026-04-16T14:30:00"`).
 */
public class LocalDateTimeAdapter {
    /** Converts [LocalDateTime] to its ISO-8601 string representation. */
    @ToJson
    public fun toJson(value: LocalDateTime): String = value.toString()

    /** Parses an ISO-8601 local datetime string into a [LocalDateTime]. */
    @FromJson
    public fun fromJson(value: String): LocalDateTime = LocalDateTime.parse(value)
}

/**
 * Registers all BeCalm kotlinx-datetime adapters on this [Moshi.Builder] in a single call.
 *
 * SP-06 (AppModule) calls this when constructing the application-scoped [Moshi] instance.
 */
public fun Moshi.Builder.addBecalmAdapters(): Moshi.Builder =
    this
        .add(InstantAdapter())
        .add(LocalDateAdapter())
        .add(LocalDateTimeAdapter())
