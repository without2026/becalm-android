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
public data class PersonActionProviderWriteConnectionDto(
    @field:Json(name = "provider") val provider: String,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String,
    @field:Json(name = "account_identifier") val accountIdentifier: String? = null,
    @field:Json(name = "account_display_name") val accountDisplayName: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionProviderWriteDto(
    @field:Json(name = "kind") val kind: String? = null,
    @field:Json(name = "state") val state: String,
    @field:Json(name = "job_id") val jobId: String? = null,
    @field:Json(name = "accepted") val accepted: Boolean? = null,
    @field:Json(name = "provider") val provider: String? = null,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "schedule_event_link_id") val scheduleEventLinkId: String? = null,
    @field:Json(name = "calendar_event_id") val calendarEventId: String? = null,
    @field:Json(name = "available_connections") val availableConnections: List<PersonActionProviderWriteConnectionDto> = emptyList(),
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Int? = null,
    @field:Json(name = "error_code") val errorCode: String? = null,
    @field:Json(name = "error_message") val errorMessage: String? = null,
    @field:Json(name = "recovery_actions") val recoveryActions: List<PersonActionRecoveryActionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
public data class PersonActionProviderWritePatchDto(
    @field:Json(name = "kind") val kind: String = "add_to_calendar",
    @field:Json(name = "provider") val provider: String? = null,
    @field:Json(name = "source_connection_id") val sourceConnectionId: String? = null,
    @field:Json(name = "schedule_event_link_id") val scheduleEventLinkId: String? = null,
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
    @field:Json(name = "provider_write") val providerWrite: PersonActionProviderWriteDto? = null,
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
    @field:Json(name = "server_timing_ms") val serverTimingMs: Map<String, Double> = emptyMap(),
)

@JsonClass(generateAdapter = true)
public data class PersonActionStatePatchDto(
    @field:Json(name = "client_mutation_id") val clientMutationId: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "snoozed_until") val snoozedUntil: Instant? = null,
    @field:Json(name = "reason") val reason: String? = null,
    @field:Json(name = "expected_updated_at") val expectedUpdatedAt: Instant? = null,
    @field:Json(name = "provider_write") val providerWrite: PersonActionProviderWritePatchDto? = null,
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

@JsonClass(generateAdapter = true)
public data class PersonActionEvidenceOriginalResponseDto(
    @field:Json(name = "data") val data: PersonActionEvidenceOriginalDto,
)

@JsonClass(generateAdapter = true)
public data class PersonActionDraftEvidenceRefDto(
    @field:Json(name = "kind") val kind: String,
    @field:Json(name = "evidence_id") val evidenceId: String,
)

@JsonClass(generateAdapter = true)
public data class PersonActionDraftRequestDto(
    @field:Json(name = "client_request_id") val clientRequestId: String,
    @field:Json(name = "draft_kind") val draftKind: String,
    @field:Json(name = "channel") val channel: String = "email",
    @field:Json(name = "locale") val locale: String = "ko-KR",
    @field:Json(name = "tone") val tone: String = "warm_brief",
    @field:Json(name = "max_chars") val maxChars: Int = 900,
    @field:Json(name = "evidence_refs") val evidenceRefs: List<PersonActionDraftEvidenceRefDto> = emptyList(),
    @field:Json(name = "user_instruction") val userInstruction: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionDraftResponseDto(
    @field:Json(name = "data") val data: PersonActionDraftDto,
)

@JsonClass(generateAdapter = true)
public data class PersonActionDraftDto(
    @field:Json(name = "draft_id") val draftId: String? = null,
    @field:Json(name = "action_item_id") val actionItemId: String,
    @field:Json(name = "draft_kind") val draftKind: String,
    @field:Json(name = "channel") val channel: String,
    @field:Json(name = "status") val status: String,
    @field:Json(name = "subject") val subject: String? = null,
    @field:Json(name = "body") val body: String,
    @field:Json(name = "provenance") val provenance: List<PersonActionDraftProvenanceDto> = emptyList(),
    @field:Json(name = "safety") val safety: PersonActionDraftSafetyDto? = null,
    @field:Json(name = "expires_at") val expiresAt: Instant? = null,
    @field:Json(name = "generated_at") val generatedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class PersonActionDraftProvenanceDto(
    @field:Json(name = "kind") val kind: String,
    @field:Json(name = "evidence_id") val evidenceId: String,
    @field:Json(name = "label") val label: String? = null,
)

@JsonClass(generateAdapter = true)
public data class PersonActionDraftSafetyDto(
    @field:Json(name = "contains_source_quote") val containsSourceQuote: Boolean = false,
    @field:Json(name = "requires_user_review") val requiresUserReview: Boolean = true,
)

@JsonClass(generateAdapter = true)
public data class PersonActionEvidenceOriginalDto(
    @field:Json(name = "action_item_id") val actionItemId: String,
    @field:Json(name = "action_status") val actionStatus: String? = null,
    @field:Json(name = "evidence") val evidence: PersonActionEvidenceRefDto,
    @field:Json(name = "original") val original: PersonActionEvidenceOriginalDetailDto,
    @field:Json(name = "resolved_at") val resolvedAt: Instant,
)

@JsonClass(generateAdapter = true)
public data class PersonActionEvidenceOriginalDetailDto(
    @field:Json(name = "kind") val kind: String,
    @field:Json(name = "id") val id: String? = null,
    @field:Json(name = "original_available") val originalAvailable: Boolean = false,
    @field:Json(name = "status") val status: String? = null,
    @field:Json(name = "client_action") val clientAction: String? = null,
    @field:Json(name = "item_type") val itemType: String? = null,
    @field:Json(name = "direction") val direction: String? = null,
    @field:Json(name = "schedule_status") val scheduleStatus: String? = null,
    @field:Json(name = "decision_status") val decisionStatus: String? = null,
    @field:Json(name = "source_type") val sourceType: String? = null,
    @field:Json(name = "event_kind") val eventKind: String? = null,
    @field:Json(name = "source_ref") val sourceRef: String? = null,
    @field:Json(name = "local_lookup_key") val localLookupKey: String? = null,
    @field:Json(name = "client_event_id") val clientEventId: String? = null,
    @field:Json(name = "provider_event_id") val providerEventId: String? = null,
    @field:Json(name = "conversation_ref") val conversationRef: String? = null,
    @field:Json(name = "metadata") val metadata: Map<String, String?> = emptyMap(),
    @field:Json(name = "folder") val folder: String? = null,
    @field:Json(name = "title") val title: String? = null,
    @field:Json(name = "description") val description: String? = null,
    @field:Json(name = "snippet") val snippet: String? = null,
    @field:Json(name = "quote") val quote: String? = null,
    @field:Json(name = "evidence") val evidenceText: String? = null,
    @field:Json(name = "occurred_at") val occurredAt: Instant? = null,
    @field:Json(name = "source_event_occurred_at") val sourceEventOccurredAt: Instant? = null,
    @field:Json(name = "due_at") val dueAt: Instant? = null,
    @field:Json(name = "due_hint") val dueHint: String? = null,
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean? = null,
    @field:Json(name = "action_state") val actionState: String? = null,
    @field:Json(name = "proposed_start_at") val proposedStartAt: Instant? = null,
    @field:Json(name = "proposed_end_at") val proposedEndAt: Instant? = null,
    @field:Json(name = "start_at") val startAt: Instant? = null,
    @field:Json(name = "end_at") val endAt: Instant? = null,
    @field:Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @field:Json(name = "location") val location: String? = null,
    @field:Json(name = "extraction_status") val extractionStatus: String? = null,
    @field:Json(name = "extracted_count") val extractedCount: Int? = null,
    @field:Json(name = "transcript_available") val transcriptAvailable: Boolean? = null,
    @field:Json(name = "transcript_status") val transcriptStatus: String? = null,
    @field:Json(name = "transcript_provider") val transcriptProvider: String? = null,
    @field:Json(name = "transcript_source_type") val transcriptSourceType: String? = null,
    @field:Json(name = "transcript_raw_event_id") val transcriptRawEventId: String? = null,
    @field:Json(name = "transcript_text") val transcriptText: String? = null,
    @field:Json(name = "transcript_segments") val transcriptSegments: List<PersonActionTranscriptSegmentDto> = emptyList(),
    @field:Json(name = "transcript_billable_seconds") val transcriptBillableSeconds: Int? = null,
    @field:Json(name = "transcript_updated_at") val transcriptUpdatedAt: Instant? = null,
    @field:Json(name = "provider") val provider: String? = null,
    @field:Json(name = "capability") val capability: String? = null,
    @field:Json(name = "account_display_name") val accountDisplayName: String? = null,
    @field:Json(name = "account_identifier_hint") val accountIdentifierHint: String? = null,
    @field:Json(name = "ownership") val ownership: String? = null,
    @field:Json(name = "last_sync_at") val lastSyncAt: Instant? = null,
    @field:Json(name = "last_error_present") val lastErrorPresent: Boolean? = null,
    @field:Json(name = "deleted_at") val deletedAt: Instant? = null,
    @field:Json(name = "source_event_id") val sourceEventId: String? = null,
    @field:Json(name = "confidence") val confidence: Double? = null,
    @field:Json(name = "updated_at") val updatedAt: Instant? = null,
    @field:Json(name = "raw_body_included") val rawBodyIncluded: Boolean? = null,
    @field:Json(name = "local_original_title") val localOriginalTitle: String? = null,
    @field:Json(name = "local_original_text") val localOriginalText: String? = null,
    @field:Json(name = "local_original_truncated") val localOriginalTruncated: Boolean = false,
)

@JsonClass(generateAdapter = true)
public data class PersonActionTranscriptSegmentDto(
    @field:Json(name = "text") val text: String,
    @field:Json(name = "speaker") val speaker: String? = null,
    @field:Json(name = "start") val start: Double? = null,
    @field:Json(name = "end") val end: Double? = null,
)
