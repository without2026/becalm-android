package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class ManualMemoryCreateRequestDto(
    @field:Json(name = "client_memory_id") val clientMemoryId: String,
    @field:Json(name = "person_id") val personId: String,
    @field:Json(name = "commitment_id") val commitmentId: String,
    @field:Json(name = "person_display_name") val personDisplayName: String,
    @field:Json(name = "origin_channel") val originChannel: String,
    @field:Json(name = "memory_kind") val memoryKind: String,
    @field:Json(name = "title") val title: String,
    @field:Json(name = "occurred_at") val occurredAt: Instant,
    @field:Json(name = "due_at") val dueAt: Instant? = null,
    @field:Json(name = "due_hint") val dueHint: String? = null,
)

@JsonClass(generateAdapter = true)
public data class ManualMemoryCreateResponseDto(
    @field:Json(name = "person_id") val personId: String,
    @field:Json(name = "commitment_id") val commitmentId: String,
    @field:Json(name = "source_ref") val sourceRef: String,
    @field:Json(name = "created") val created: Boolean,
)

