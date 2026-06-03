package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class ScheduleRowTombstoneRequestDto(
    @field:Json(name = "row_type") val rowType: String,
    @field:Json(name = "source_event_id") val sourceEventId: String,
    @field:Json(name = "source_type") val sourceType: String? = null,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "deleted_at") val deletedAt: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class ScheduleRowTombstoneResponseDto(
    @field:Json(name = "data") val data: ScheduleRowTombstoneDto,
)

@JsonClass(generateAdapter = true)
public data class ScheduleRowTombstoneDto(
    @field:Json(name = "id") val id: String? = null,
    @field:Json(name = "user_id") val userId: String? = null,
    @field:Json(name = "row_type") val rowType: String? = null,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "source_type") val sourceType: String? = null,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "deleted_at") val deletedAt: Instant? = null,
    @field:Json(name = "created_at") val createdAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant? = null,
)
