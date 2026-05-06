package com.becalm.android.data.remote.dto

import com.becalm.android.core.util.KstInstant
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

/**
 * Error envelope for a failed POST /v1/extractions/commitments (HTTP 502).
 *
 * Wire format (api-contract.yml):
 *   { error: "output_truncated" | "schema_violation" | "vertex_upstream_error", message: string }
 *
 * Only [VERTEX_UPSTREAM_ERROR] is retryable. [OUTPUT_TRUNCATED] and [SCHEMA_VIOLATION] indicate
 * deterministic server-side failures and must be quarantined immediately without further retries.
 *
 * @property error  Machine-readable error code from the server.
 * @property message Human-readable explanation; may be null if the server omits it.
 *
 * Spec refs: VOI-006, api-contract.yml § /v1/extractions/commitments 502 envelope.
 */
@JsonClass(generateAdapter = true)
public data class SourceExtractionErrorEnvelope(
    /** Machine-readable error code. See [OUTPUT_TRUNCATED], [SCHEMA_VIOLATION], [VERTEX_UPSTREAM_ERROR]. */
    @field:Json(name = "error") val error: String,

    /** Human-readable description of the error, or null when absent. */
    @field:Json(name = "message") val message: String? = null,
) {
    public companion object {
        /** Deterministic failure: LLM output was truncated — non-retryable. */
        public const val OUTPUT_TRUNCATED: String = "output_truncated"

        /** Deterministic failure: LLM output violated the response schema — non-retryable. */
        public const val SCHEMA_VIOLATION: String = "schema_violation"

        /** Transient failure: upstream Vertex AI error — retryable with backoff. */
        public const val VERTEX_UPSTREAM_ERROR: String = "vertex_upstream_error"
    }
}

/**
 * Response body for a successful POST /v1/extractions/commitments (HTTP 200).
 *
 * Wire format (api-contract.yml):
 *   { raw_event_id, items: SourceExtractedItem[], source_event_participants: SourceExtractedParticipant[], model, region, raw_model_text? }
 *
 * @property rawEventId Server-side UUID of the raw_ingestion_event row that was updated.
 * @property items List of extracted business items; may be empty when no
 *   trackable items were detected in the source.
 * @property model LLM model identifier used for extraction (e.g. "gemini-2.5-flash").
 * @property region Vertex AI region used for the call (e.g. "us-central1").
 * @property rawModelText Raw JSON text returned by the model for debug/inspection.
 *
 * Spec refs: VOI-001, VOI-002, VOI-003.
 */
@JsonClass(generateAdapter = true)
public data class SourceExtractionResponse(
    /** Server-assigned UUID of the updated raw_ingestion_event row. */
    @field:Json(name = "raw_event_id") val rawEventId: String,

    /** Extracted structured items from the source by the LLM. Empty list when none detected. */
    @field:Json(name = "items") val items: List<SourceExtractedItemDto>,

    /** Canonical source-level participant signals emitted by Vertex Gemini for person matching. */
    @field:Json(name = "source_event_participants")
    val sourceEventParticipants: List<SourceExtractedParticipantDto> = emptyList(),

    /** Model identifier used for extraction, e.g. "gemini-2.5-flash". */
    @field:Json(name = "model") val model: String,

    /** Vertex AI region, e.g. "us-central1". */
    @field:Json(name = "region") val region: String,

    /** Raw model JSON text for diagnostics. Optional because older backends omit it. */
    @field:Json(name = "raw_model_text") val rawModelText: String? = null,
)

@JsonClass(generateAdapter = true)
public data class SourceExtractedParticipantDto(
    @field:Json(name = "role") val role: String,
    @field:Json(name = "relation_to_user") val relationToUser: String = "unknown",
    @field:Json(name = "identity_type") val identityType: String? = null,
    @field:Json(name = "raw_value") val rawValue: String? = null,
    @field:Json(name = "normalized_value") val normalizedValue: String? = null,
    @field:Json(name = "display_name") val displayName: String? = null,
    @field:Json(name = "email") val email: String? = null,
    @field:Json(name = "phone") val phone: String? = null,
    @field:Json(name = "organization") val organization: String? = null,
    @field:Json(name = "title") val title: String? = null,
    @field:Json(name = "evidence") val evidence: String? = null,
    @field:Json(name = "evidence_source") val evidenceSource: String? = null,
    @field:Json(name = "confidence") val confidence: Double = 0.0,
)

public object SourceExtractedItemType {
    public const val ACTION: String = "action"
    public const val SCHEDULE: String = "schedule"
    public const val DECISION: String = "decision"
}

public object ScheduleStatus {
    public const val CONFIRMED: String = "confirmed"
    public const val CHANGED: String = "changed"
    public const val POSTPONED: String = "postponed"
    public const val CANCELLED: String = "cancelled"
    public const val FOLLOW_UP: String = "follow_up"
}

public object DecisionStatus {
    public const val APPROVED: String = "approved"
    public const val REJECTED: String = "rejected"
    public const val CHOSEN: String = "chosen"
    public const val DEFERRED: String = "deferred"
    public const val ONGOING: String = "ongoing"
}

/** Wire-level DTO for a single extracted business item. */
@JsonClass(generateAdapter = true)
public data class SourceExtractedItemDto(
    @field:Json(name = "type") val type: String,
    @field:Json(name = "text") val text: String,
    @field:Json(name = "quote") val quote: String,
    @field:Json(name = "person_ref") val counterpartyRef: String?,
    @field:KstInstant @field:Json(name = "due_at") val dueAt: Instant?,
    @field:Json(name = "due_hint") val dueHint: String? = null,
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean = false,
    @field:Json(name = "confidence") val confidence: Float,
    @field:Json(name = "direction") val direction: String? = null,
    @field:Json(name = "schedule_status") val scheduleStatus: String? = null,
    @field:Json(name = "decision_status") val decisionStatus: String? = null,
)
