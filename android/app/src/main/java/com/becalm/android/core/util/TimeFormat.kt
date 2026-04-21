package com.becalm.android.core.util

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Korean-locale time formatting utilities consumed by UI layers and notification builders.
 *
 * All functions use [kotlinx.datetime] exclusively — no `java.time` imports. Korean strings
 * ("오늘", "어제", "일 전", "오전", "오후") are hardcoded; no Android resource lookups are
 * performed so these formatters are safe to use outside an Android [Context].
 */

private fun Int.pad2(): String = toString().padStart(2, '0')
private fun Int.pad4(): String = toString().padStart(4, '0')
private fun Int.pad3(): String = toString().padStart(3, '0')

/** Formats the hour/minute portion of a LocalDateTime as `"오전 2:30"` or `"오후 2:30"`. */
private fun formatKoreanTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "오전" else "오후"
    val h = hour % 12
    return "$amPm ${if (h == 0) 12 else h}:${minute.pad2()}"
}

/**
 * Formats [instant] as `"2026-04-16 오후 2:30"` suitable for timeline section headers.
 *
 * The 24-hour clock is converted to 12-hour with 오전/오후 prefix manually.
 */
public fun formatKoreanDateTime(
    instant: Instant,
    tz: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val ldt = instant.toLocalDateTime(tz)
    val date = "${ldt.year}-${ldt.monthNumber.pad2()}-${ldt.dayOfMonth.pad2()}"
    return "$date ${formatKoreanTime(ldt.hour, ldt.minute)}"
}

/**
 * Formats [instant] relative to [now] using Korean conventions.
 *
 * Boundary rules (evaluated in the device's local date, not UTC):
 * - Same local date as [now] → `"오늘 · 오후 2:30"`
 * - One calendar day behind [now] → `"어제 · 오후 2:30"`
 * - Two or more calendar days behind [now] → `"3일 전 · 오후 2:30"` (days are the difference in local dates)
 */
public fun formatRelativeKorean(
    instant: Instant,
    now: Instant,
    tz: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val ldt = instant.toLocalDateTime(tz)
    val dayDiff = ldt.date.daysUntil(now.toLocalDateTime(tz).date)
    val prefix = when (dayDiff) {
        0 -> "오늘"
        1 -> "어제"
        else -> "${dayDiff}일 전"
    }
    return "$prefix · ${formatKoreanTime(ldt.hour, ldt.minute)}"
}

/**
 * Formats the gap between [today] and [due] as a D-day badge string.
 *
 * - `"D-3"` — due date is 3 days in the future.
 * - `"D-0"` — due date is today.
 * - `"D+2"` — due date was 2 days ago (overdue).
 */
public fun formatDueDayBadge(today: LocalDate, due: LocalDate): String {
    val diff = due.daysUntil(today) // positive = overdue
    return when {
        diff < 0 -> "D${diff}" // e.g. diff=-3 → "D-3"
        diff == 0 -> "D-0"
        else -> "D+$diff"
    }
}

/**
 * Formats [date] as `"YYYY-MM-DD"` ISO-8601 for Room persistence and network wire format.
 */
public fun formatIsoDate(date: LocalDate): String = date.toString()

/**
 * Formats [instant] as an RFC 3339 timestamp with millisecond precision for API requests,
 * e.g. `"2026-04-16T05:30:00.000Z"`.
 */
public fun formatRfc3339(instant: Instant): String {
    val ldt = instant.toLocalDateTime(TimeZone.UTC)
    val millis = Math.floorMod(instant.toEpochMilliseconds(), 1_000L).toInt()
    return "${ldt.year.pad4()}-${ldt.monthNumber.pad2()}-${ldt.dayOfMonth.pad2()}" +
        "T${ldt.hour.pad2()}:${ldt.minute.pad2()}:${ldt.second.pad2()}.${millis.pad3()}Z"
}
