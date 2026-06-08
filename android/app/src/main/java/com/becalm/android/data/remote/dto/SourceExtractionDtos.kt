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

    /** Optional recovery action supplied by newer Railway error envelopes. */
    @field:Json(name = "client_action") val clientAction: String? = null,
) {
    public companion object {
        /** Deterministic failure: LLM output was truncated — non-retryable. */
        public const val OUTPUT_TRUNCATED: String = "output_truncated"

        /** Deterministic failure: LLM output violated the response schema — non-retryable. */
        public const val SCHEMA_VIOLATION: String = "schema_violation"

        /** Transient failure: upstream Vertex AI error — retryable with backoff. */
        public const val VERTEX_UPSTREAM_ERROR: String = "vertex_upstream_error"

        /** Recoverable backend-state miss: the transient speaker-preview transcript expired. */
        public const val SPEAKER_PREVIEW_UNAVAILABLE: String = "speaker_preview_unavailable"

        /** Client action for [SPEAKER_PREVIEW_UNAVAILABLE]. */
        public const val RESTART_MEETING_SPEAKER_PREVIEW: String = "restart_meeting_speaker_preview"
    }
}

@JsonClass(generateAdapter = true)
public data class ExtractionUploadPrepareRequest(
    @field:Json(name = "input_modality") val inputModality: String,
    @field:Json(name = "source_type") val sourceType: String,
    @field:Json(name = "raw_event_id") val rawEventId: String,
    @field:Json(name = "content_type") val contentType: String,
    @field:Json(name = "content_length") val contentLength: Long? = null,
)

@JsonClass(generateAdapter = true)
public data class ExtractionStorageRefDto(
    @field:Json(name = "bucket") val bucket: String,
    @field:Json(name = "path") val path: String,
    @field:Json(name = "content_type") val contentType: String,
    @field:Json(name = "raw_event_id") val rawEventId: String,
    @field:Json(name = "media_kind") val mediaKind: String? = null,
)

@JsonClass(generateAdapter = true)
public data class ExtractionUploadPrepareResponse(
    @field:Json(name = "raw_event_id") val rawEventId: String,
    @field:Json(name = "job_id") val jobId: String,
    @field:Json(name = "bucket") val bucket: String,
    @field:Json(name = "path") val path: String,
    @field:Json(name = "content_type") val contentType: String,
    @field:Json(name = "media_kind") val mediaKind: String,
    @field:Json(name = "signed_upload_url") val signedUploadUrl: String,
    @field:Json(name = "upload_token") val uploadToken: String,
    @field:Json(name = "upload_content_type") val uploadContentType: String,
    @field:Json(name = "storage_ref") val storageRef: ExtractionStorageRefDto,
)

@JsonClass(generateAdapter = true)
public data class CommitmentExtractionJobCreateRequest(
    @field:Json(name = "input_modality") val inputModality: String,
    @field:Json(name = "source_type") val sourceType: String,
    @field:Json(name = "client_event_id") val clientEventId: String,
    @field:Json(name = "raw_event_id") val rawEventId: String,
    @field:Json(name = "timestamp") val timestamp: String,
    @field:Json(name = "storage_ref") val storageRef: ExtractionStorageRefDto,
    @field:Json(name = "duration_seconds") val durationSeconds: Int? = null,
    @field:Json(name = "counterparty_ref") val counterpartyRef: String? = null,
    @field:Json(name = "event_title") val eventTitle: String? = null,
    @field:Json(name = "folder") val folder: String? = null,
    @field:Json(name = "conversation_ref") val conversationRef: String? = null,
    @field:Json(name = "previous_thread_context") val previousThreadContext: String? = null,
    @field:Json(name = "self_speaker_id") val selfSpeakerId: String? = null,
    @field:Json(name = "processing_confirmed") val processingConfirmed: Boolean = false,
)

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

    /** Completion evidence for previously extracted action commitments. Not a new item. */
    @field:Json(name = "completion_signals")
    val completionSignals: List<SourceCompletionSignalDto> = emptyList(),

    /** Canonical source-level participant signals emitted by Vertex Gemini for person matching. */
    @field:Json(name = "source_event_participants")
    val sourceEventParticipants: List<SourceExtractedParticipantDto> = emptyList(),

    /** Model identifier used for extraction, e.g. "gemini-2.5-flash". */
    @field:Json(name = "model") val model: String,

    /** Vertex AI region, e.g. "us-central1". */
    @field:Json(name = "region") val region: String,

    /** Raw model JSON text for diagnostics. Optional because older backends omit it. */
    @field:Json(name = "raw_model_text") val rawModelText: String? = null,

    /** Server-side async extraction job id. Present for HTTP 202 and polling responses. */
    @field:Json(name = "job_id") val jobId: String? = null,

    /** Async extraction job status: pending, processing, succeeded, or failed. */
    @field:Json(name = "status") val status: String? = null,

    /** Polling hint in seconds for async extraction jobs. */
    @field:Json(name = "retry_after_seconds") val retryAfterSeconds: Long? = null,

    /** Machine-readable async job failure code when status is failed. */
    @field:Json(name = "error") val error: String? = null,

    /** Human-readable async job failure message when status is failed. */
    @field:Json(name = "message") val message: String? = null,

    /** Whether a failed async job can be recovered by re-uploading or polling later. */
    @field:Json(name = "retryable") val retryable: Boolean? = null,
)

@JsonClass(generateAdapter = true)
public data class MeetingSpeakerPreviewResponse(
    @field:Json(name = "raw_event_id") val rawEventId: String,
    @field:Json(name = "speaker_preview_id") val speakerPreviewId: String,
    @field:Json(name = "speakers") val speakers: List<MeetingSpeakerPreviewDto>,
    @field:Json(name = "model") val model: String,
    @field:Json(name = "billable_seconds") val billableSeconds: Int,
    @field:Json(name = "transcript_segments") val transcriptSegments: List<MeetingTranscriptSegmentDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
public data class MeetingSpeakerPreviewDto(
    @field:Json(name = "speaker_id") val speakerId: String,
    @field:Json(name = "sample_texts") val sampleTexts: List<String> = emptyList(),
    @field:Json(name = "first_start") val firstStart: Double = 0.0,
    @field:Json(name = "total_seconds") val totalSeconds: Double = 0.0,
)

@JsonClass(generateAdapter = true)
public data class MeetingTranscriptSegmentDto(
    @field:Json(name = "speaker_id") val speakerId: String,
    @field:Json(name = "start_seconds") val startSeconds: Double = 0.0,
    @field:Json(name = "end_seconds") val endSeconds: Double = 0.0,
    @field:Json(name = "text") val text: String,
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

@JsonClass(generateAdapter = true)
public data class SourceCompletionSignalDto(
    @field:Json(name = "event_type") val eventType: String = "completed",
    @field:Json(name = "person_ref") val personRef: String? = null,
    @field:Json(name = "evidence_quote") val evidenceQuote: String,
    @field:Json(name = "confidence") val confidence: Double = 0.0,
    @field:Json(name = "reason") val reason: String? = null,
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
