package com.becalm.android.ui.actions

import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus

public fun PersonActionItemUi.shouldOfferReminder(): Boolean {
    if (commitmentId.isNullOrBlank()) return false
    if (dueAt == null) return false
    return reasonCodes.any { code ->
        code.equals("waiting_on", ignoreCase = true) ||
            code.equals("direction:take", ignoreCase = true) ||
            code.equals("take", ignoreCase = true)
    }
}

public fun PersonActionItemUi.defaultReminderSnoozeUntil(now: Instant): Instant? {
    val deadline = dueAt ?: return null
    val oneDayBefore = deadline.minus(1, DateTimeUnit.DAY, TimeZone.UTC)
    return if (oneDayBefore > now) oneDayBefore else now.plus(1.hours)
}
