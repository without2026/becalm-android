package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class ProductAnalyticsEventDto(
    @field:Json(name = "event_id") val eventId: String,
    @field:Json(name = "event_name") val eventName: String,
    @field:Json(name = "occurred_at") val occurredAt: Instant,
    @field:Json(name = "session_id") val sessionId: String?,
    @field:Json(name = "source") val source: String = "android",
    @field:Json(name = "properties") val properties: Map<String, Any?>,
)

@JsonClass(generateAdapter = true)
public data class ProductAnalyticsBatchRequestDto(
    @field:Json(name = "events") val events: List<ProductAnalyticsEventDto>,
)

@JsonClass(generateAdapter = true)
public data class ProductAnalyticsBatchResponseDto(
    @field:Json(name = "acknowledged") val acknowledged: Int,
)

@JsonClass(generateAdapter = true)
public data class PmfSurveyRequestDto(
    @field:Json(name = "response_id") val responseId: String,
    @field:Json(name = "occurred_at") val occurredAt: Instant,
    @field:Json(name = "disappointment_response") val disappointmentResponse: String,
    @field:Json(name = "primary_benefit") val primaryBenefit: String,
    @field:Json(name = "audience") val audience: String,
    @field:Json(name = "improvement") val improvement: String,
    @field:Json(name = "properties") val properties: Map<String, Any?> = emptyMap(),
)

@JsonClass(generateAdapter = true)
public data class PmfSurveyResponseDto(
    @field:Json(name = "acknowledged") val acknowledged: Int,
)
