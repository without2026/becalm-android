package com.becalm.android.domain.schedule

/** Stable identity for one visible row in the Schedule/Today timeline. */
public sealed interface ScheduleRowRef {
    public data class Commitment(val id: String) : ScheduleRowRef

    public data class CalendarEvent(
        val id: String,
        val sourceType: String,
        val sourceRef: String?,
    ) : ScheduleRowRef

    public data class Meeting(
        val id: String,
        val sourceType: String,
        val sourceRef: String?,
    ) : ScheduleRowRef
}
