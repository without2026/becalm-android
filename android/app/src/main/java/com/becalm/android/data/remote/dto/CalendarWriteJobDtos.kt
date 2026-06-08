package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class CalendarWriteJobResponseDto(
    @field:Json(name = "job_id") val jobId: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "accepted") val accepted: Boolean,
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Int? = null,
    @field:Json(name = "provider") val provider: String? = null,
    @field:Json(name = "write_kind") val writeKind: String? = null,
    @field:Json(name = "action_item_id") val actionItemId: String? = null,
    @field:Json(name = "schedule_event_link_id") val scheduleEventLinkId: String? = null,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "provider_event_id") val providerEventId: String? = null,
    @field:Json(name = "calendar_event_id") val calendarEventId: String? = null,
    @field:Json(name = "attempts") val attempts: Int = 0,
    @field:Json(name = "error_code") val errorCode: String? = null,
    @field:Json(name = "error_message") val errorMessage: String? = null,
    @field:Json(name = "next_attempt_at") val nextAttemptAt: Instant? = null,
    @field:Json(name = "client_action") val clientAction: String? = null,
)
