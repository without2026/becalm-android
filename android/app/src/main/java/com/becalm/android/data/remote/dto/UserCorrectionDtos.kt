package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class UserCorrectionRequestDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "domain") val domain: String,
    @field:Json(name = "action") val action: String,
    @field:Json(name = "target_type") val targetType: String,
    @field:Json(name = "target_id") val targetId: String,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "commitment_id") val commitmentId: String? = null,
    @field:Json(name = "from_person_id") val fromPersonId: String? = null,
    @field:Json(name = "to_person_id") val toPersonId: String? = null,
    @field:Json(name = "conflict_key") val conflictKey: String,
    @field:Json(name = "idempotency_key") val idempotencyKey: String,
    @field:Json(name = "target_fingerprint") val targetFingerprint: String? = null,
    @field:Json(name = "payload") val payload: Map<String, Any?> = emptyMap(),
    @field:Json(name = "client_created_at") val clientCreatedAt: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class UserCorrectionBatchRequestDto(
    @field:Json(name = "items") val items: List<UserCorrectionRequestDto>,
)

@JsonClass(generateAdapter = true)
public data class UserCorrectionBatchResponseDto(
    @field:Json(name = "acknowledged") val acknowledged: Int,
    @field:Json(name = "failed") val failed: List<UserCorrectionFailedDto> = emptyList(),
    @field:Json(name = "data") val data: List<UserCorrectionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
public data class UserCorrectionFailedDto(
    @field:Json(name = "id") val id: String? = null,
    @field:Json(name = "idempotency_key") val idempotencyKey: String? = null,
    @field:Json(name = "error") val error: String,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "retryable") val retryable: Boolean = false,
)

@JsonClass(generateAdapter = true)
public data class UserCorrectionDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "user_id") val userId: String? = null,
    @field:Json(name = "domain") val domain: String,
    @field:Json(name = "action") val action: String,
    @field:Json(name = "target_type") val targetType: String,
    @field:Json(name = "target_id") val targetId: String,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "commitment_id") val commitmentId: String? = null,
    @field:Json(name = "from_person_id") val fromPersonId: String? = null,
    @field:Json(name = "to_person_id") val toPersonId: String? = null,
    @field:Json(name = "conflict_key") val conflictKey: String,
    @field:Json(name = "idempotency_key") val idempotencyKey: String,
    @field:Json(name = "target_fingerprint") val targetFingerprint: String? = null,
    @field:Json(name = "payload") val payload: Map<String, Any?> = emptyMap(),
    @field:Json(name = "status") val status: String,
    @field:Json(name = "failure_reason") val failureReason: String? = null,
    @field:Json(name = "client_created_at") val clientCreatedAt: Instant? = null,
    @field:Json(name = "applied_at") val appliedAt: Instant? = null,
    @field:Json(name = "created_at") val createdAt: Instant,
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class UserCorrectionsResponseDto(
    @field:Json(name = "data") val data: List<UserCorrectionDto>,
    @field:Json(name = "cursor") val cursor: String,
    @field:Json(name = "has_more") val hasMore: Boolean,
)
