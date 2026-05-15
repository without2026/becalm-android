package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class ProductEventDto(
    @field:Json(name = "event_id") val eventId: String,
    @field:Json(name = "event_name") val eventName: String,
    @field:Json(name = "occurred_at") val occurredAt: Instant,
    @field:Json(name = "session_id") val sessionId: String? = null,
    @field:Json(name = "source") val source: String = "android",
    @field:Json(name = "properties") val properties: Map<String, Any> = emptyMap(),
)

@JsonClass(generateAdapter = true)
public data class ProductEventsBatchRequest(
    @field:Json(name = "events") val events: List<ProductEventDto>,
)

@JsonClass(generateAdapter = true)
public data class ProductEventsBatchResponse(
    @field:Json(name = "acknowledged") val acknowledged: Int,
)
