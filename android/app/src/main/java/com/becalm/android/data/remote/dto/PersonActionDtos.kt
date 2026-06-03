package com.becalm.android.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

@JsonClass(generateAdapter = true)
public data class PersonActionEvidenceRefDto(
    @field:Json(name = "kind") val kind: String,
    @field:Json(name = "id") val id: String,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "occurred_at") val occurredAt: Instant? = null,
    @field:Json(name = "label") val label: String,
    @field:Json(name = "quote") val quote: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionInputWatermarkDto(
    @field:Json(name = "input_kind") val inputKind: String,
    @field:Json(name = "source_type") val sourceType: String? = null,
    @field:Json(name = "high_watermark") val highWatermark: Instant,
    @field:Json(name = "row_count") val rowCount: Int? = null,
    @field:Json(name = "digest") val digest: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionRecoveryActionDto(
    @field:Json(name = "kind") val kind: String,
    @field:Json(name = "label") val label: String,
    @field:Json(name = "source_id") val sourceId: String? = null,
    @field:Json(name = "action_item_id") val actionItemId: String? = null,
    @field:Json(name = "mutation_id") val mutationId: String? = null,
    @field:Json(name = "reason_code") val reasonCode: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionCapacityStateDto(
    @field:Json(name = "state") val state: String,
    @field:Json(name = "backlog_lag_seconds") val backlogLagSeconds: Int? = null,
    @field:Json(name = "last_caught_up_at") val lastCaughtUpAt: Instant? = null,
    @field:Json(name = "incident_id") val incidentId: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionEmptyStateDto(
    @field:Json(name = "state") val state: String,
    @field:Json(name = "title") val title: String? = null,
    @field:Json(name = "message") val message: String? = null,
    @field:Json(name = "source_id") val sourceId: String? = null,
    @field:Json(name = "reason_code") val reasonCode: String? = null,
    @field:Json(name = "recovery_actions") val recoveryActions: List<PersonActionRecoveryActionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
public data class PersonActionItemDto(
    @field:Json(name = "id") val id: String,
    @field:Json(name = "user_id") val userId: String,
    @field:Json(name = "person_id") val personId: String? = null,
    @field:Json(name = "person_display_name") val personDisplayName: String? = null,
    @field:Json(name = "person_sort_key") val personSortKey: String? = null,
    @field:Json(name = "surfaces") val surfaces: List<String> = emptyList(),
    @field:Json(name = "action_kind") val actionKind: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "title") val title: String,
    @field:Json(name = "primary_verb") val primaryVerb: String,
    @field:Json(name = "short_reason") val shortReason: String,
    @field:Json(name = "commitment_id") val commitmentId: String? = null,
    @field:Json(name = "calendar_event_id") val calendarEventId: String? = null,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "source_type") val sourceType: String? = null,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "due_at") val dueAt: Instant? = null,
    @field:Json(name = "due_hint") val dueHint: String? = null,
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean = false,
    @field:Json(name = "stale_after") val staleAfter: Instant? = null,
    @field:Json(name = "urgency_score") val urgencyScore: Double = 0.0,
    @field:Json(name = "importance_score") val importanceScore: Double = 0.0,
    @field:Json(name = "confidence") val confidence: Double = 0.0,
    @field:Json(name = "reason_codes") val reasonCodes: List<String> = emptyList(),
    @field:Json(name = "evidence_refs") val evidenceRefs: List<PersonActionEvidenceRefDto> = emptyList(),
    @field:Json(name = "input_watermark") val inputWatermark: Instant,
    @field:Json(name = "input_watermarks") val inputWatermarks: List<PersonActionInputWatermarkDto> = emptyList(),
    @field:Json(name = "computed_at") val computedAt: Instant,
    @field:Json(name = "updated_at") val updatedAt: Instant,
    @field:Json(name = "snoozed_until") val snoozedUntil: Instant? = null,
    @field:Json(name = "completed_at") val completedAt: Instant? = null,
    @field:Json(name = "dismissed_at") val dismissedAt: Instant? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionFeedResponseDto(
    @field:Json(name = "data") val data: List<PersonActionItemDto>,
    @field:Json(name = "deleted_ids") val deletedIds: List<String> = emptyList(),
    @field:Json(name = "cursor") val cursor: String = "",
    @field:Json(name = "has_more") val hasMore: Boolean = false,
    @field:Json(name = "snapshot_id") val snapshotId: String? = null,
    @field:Json(name = "snapshot_expires_at") val snapshotExpiresAt: Instant? = null,
    @field:Json(name = "watermark_scope") val watermarkScope: String? = null,
    @field:Json(name = "server_watermark") val serverWatermark: Instant,
    @field:Json(name = "input_watermarks") val inputWatermarks: List<PersonActionInputWatermarkDto> = emptyList(),
    @field:Json(name = "recompute_state") val recomputeState: String,
    @field:Json(name = "capacity_state") val capacityState: PersonActionCapacityStateDto? = null,
    @field:Json(name = "changed_since_min") val changedSinceMin: Instant? = null,
    @field:Json(name = "tombstone_window_days") val tombstoneWindowDays: Int? = null,
    @field:Json(name = "recovery_actions") val recoveryActions: List<PersonActionRecoveryActionDto> = emptyList(),
    @field:Json(name = "empty_state") val emptyState: PersonActionEmptyStateDto? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionStatePatchDto(
    @field:Json(name = "client_mutation_id") val clientMutationId: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "snoozed_until") val snoozedUntil: Instant? = null,
    @field:Json(name = "reason") val reason: String? = null,
    @field:Json(name = "expected_updated_at") val expectedUpdatedAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class PersonActionFeedbackDto(
    @field:Json(name = "client_mutation_id") val clientMutationId: String,
    @field:Json(name = "feedback_type") val feedbackType: String,
    @field:Json(name = "reason") val reason: String? = null,
    @field:Json(name = "corrected_person_id") val correctedPersonId: String? = null,
    @field:Json(name = "corrected_due_at") val correctedDueAt: Instant? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class SinglePersonActionItemResponseDto(
    @field:Json(name = "data") val data: PersonActionItemDto,
)
