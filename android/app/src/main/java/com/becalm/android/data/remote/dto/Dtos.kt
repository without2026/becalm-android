package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// spec: api-contract.yml — request/response DTOs for Railway API

// ---- Batch Upload (ING-004, ING-014, ING-015) ----

@JsonClass(generateAdapter = true)
data class BatchUploadRequest(
    val events: List<RawIngestionEventDto>
)

@JsonClass(generateAdapter = true)
data class RawIngestionEventDto(
    @Json(name = "client_event_id") val clientEventId: String,
    @Json(name = "source_type") val sourceType: String,
    @Json(name = "source_ref") val sourceRef: String? = null,
    @Json(name = "person_ref") val personRef: String? = null,
    @Json(name = "event_title") val eventTitle: String? = null,
    @Json(name = "event_snippet") val eventSnippet: String? = null,
    @Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @Json(name = "location") val location: String? = null,
    @Json(name = "commitments_extracted_count") val commitmentsExtractedCount: Int = 0,
    @Json(name = "timestamp") val timestamp: String // ISO 8601
)

@JsonClass(generateAdapter = true)
data class BatchUploadResponse(
    val acknowledged: Int,
    val failed: List<FailedEventDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class FailedEventDto(
    @Json(name = "client_event_id") val clientEventId: String,
    val reason: String,
    val message: String,
    val retryable: Boolean
)

// ---- Commitments (CMT-003, CMT-010) ----

@JsonClass(generateAdapter = true)
data class CommitmentsResponse(
    val data: List<CommitmentDto>,
    val cursor: String?,
    @Json(name = "has_more") val hasMore: Boolean
)

@JsonClass(generateAdapter = true)
data class CommitmentDto(
    val id: String,
    @Json(name = "user_id") val userId: String,
    val direction: String,
    @Json(name = "counterparty_raw") val counterpartyRaw: String?,
    @Json(name = "person_ref") val personRef: String?,
    val title: String,
    val description: String?,
    val quote: String,
    @Json(name = "source_event_title") val sourceEventTitle: String?,
    @Json(name = "source_event_occurred_at") val sourceEventOccurredAt: String,
    @Json(name = "due_date") val dueDate: String?,
    @Json(name = "action_state") val actionState: String,
    @Json(name = "source_type") val sourceType: String,
    @Json(name = "source_ref") val sourceRef: String?,
    val confidence: Float,
    @Json(name = "sync_status") val syncStatus: String,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String
)

// spec: CMT-005..CMT-007
@JsonClass(generateAdapter = true)
data class PatchActionStateRequest(
    @Json(name = "action_state") val actionState: String
)
