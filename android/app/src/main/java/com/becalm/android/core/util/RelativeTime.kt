package com.becalm.android.core.util

import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

/**
 * Pure computation of "how long ago" an [Instant] happened relative to another
 * [Instant], quantized to the buckets rendered by the Today timeline.
 *
 * This file intentionally contains no Android or Compose imports so the logic
 * can be exercised by plain JVM unit tests. Callers on the UI side are
 * expected to map the returned [RelativeSince] to a string resource.
 *
 * All "today / hours / minutes" bucket boundaries are evaluated in
 * [KST][KST] so a user whose device is in a different timezone still sees
 * labels consistent with the KST-only "today" window.
 */
public sealed interface RelativeSince {
    public data object JustNow : RelativeSince
    public data class Minutes(val n: Int) : RelativeSince
    public data class Hours(val n: Int) : RelativeSince
    public data class TodayAt(val hhmm: String) : RelativeSince
}

/**
 * Bucket boundary between "just now" and "N minutes ago" (1 minute — anything
 * below rounds down to "just now").
 */
private val JUST_NOW_THRESHOLD = 1.minutes

/**
 * Returns the coarse bucket describing how long ago [past] happened relative
 * to [now]. [past] is allowed to be equal to or slightly after [now] (clock
 * skew / test fixtures) — both collapse to [RelativeSince.JustNow].
 */
public fun relativeSinceKst(now: Instant, past: Instant): RelativeSince {
    val elapsed = now - past
    if (elapsed < JUST_NOW_THRESHOLD) return RelativeSince.JustNow
    val minutes = elapsed.inWholeMinutes
    if (minutes < 60L) return RelativeSince.Minutes(n = minutes.toInt())
    if (minutes < 24L * 60L) return RelativeSince.Hours(n = (minutes / 60L).toInt())

    val ldt = past.toLocalDateTime(KST)
    val hh = ldt.hour.toString().padStart(2, '0')
    val mm = ldt.minute.toString().padStart(2, '0')
    return RelativeSince.TodayAt(hhmm = "$hh:$mm")
}
