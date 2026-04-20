package com.becalm.android.core.util

import com.squareup.moshi.FromJson
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/** Moshi adapter for [Instant]. Serializes as ISO-8601 with `Z` UTC offset (e.g. `"2026-04-16T05:30:00Z"`). */
public class InstantAdapter {
    @ToJson public fun toJson(value: Instant): String = value.toString()
    @FromJson public fun fromJson(value: String): Instant = Instant.parse(value)
}

/** Moshi adapter for [LocalDate]. Serializes as `"YYYY-MM-DD"` (e.g. `"2026-04-16"`). */
public class LocalDateAdapter {
    @ToJson public fun toJson(value: LocalDate): String = value.toString()
    @FromJson public fun fromJson(value: String): LocalDate = LocalDate.parse(value)
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
