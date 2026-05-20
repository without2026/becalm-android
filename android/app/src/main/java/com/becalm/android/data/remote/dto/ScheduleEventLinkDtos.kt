package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class ScheduleEventLinkDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "user_id") val userId: String,
    @field:Json(name = "calendar_event_id") val calendarEventId: String? = null,
    @field:Json(name = "calendar_source_type") val calendarSourceType: String? = null,
    @field:Json(name = "calendar_source_ref") val calendarSourceRef: String? = null,
    @field:Json(name = "source_type") val sourceType: String,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "raw_event_id") val rawEventId: String? = null,
    @field:Json(name = "commitment_id") val commitmentId: String? = null,
    @field:Json(name = "relation_type") val relationType: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "confidence") val confidence: Double,
    @field:Json(name = "proposed_start_at") val proposedStartAt: Instant? = null,
    @field:Json(name = "proposed_end_at") val proposedEndAt: Instant? = null,
    @field:Json(name = "proposed_title") val proposedTitle: String? = null,
    @field:Json(name = "evidence") val evidence: String? = null,
    @field:Json(name = "conflict_fields") val conflictFields: List<String> = emptyList(),
    @field:Json(name = "calendar_snapshot") val calendarSnapshot: Map<String, Any?>? = null,
    @field:Json(name = "source_snapshot") val sourceSnapshot: Map<String, Any?>? = null,
    @field:Json(name = "resolution_choice") val resolutionChoice: String? = null,
    @field:Json(name = "resolved_by") val resolvedBy: String? = null,
    @field:Json(name = "resolved_at") val resolvedAt: Instant? = null,
    @field:Json(name = "reopen_reason") val reopenReason: String? = null,
    @field:Json(name = "created_at") val createdAt: Instant,
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class ScheduleEventLinksResponse(
    @field:Json(name = "data") val data: List<ScheduleEventLinkDto>,
    @field:Json(name = "cursor") val cursor: String,
    @field:Json(name = "has_more") val hasMore: Boolean,
)

@JsonClass(generateAdapter = true)
public data class ScheduleEventLinkPatchDto(
    @field:Json(name = "status") val status: String? = null,
    @field:Json(name = "resolution_choice") val resolutionChoice: String? = null,
)

@JsonClass(generateAdapter = true)
public data class SingleScheduleEventLinkResponse(
    @field:Json(name = "data") val data: ScheduleEventLinkDto,
)
