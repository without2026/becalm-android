package com.becalm.android.data.remote.dto

import com.becalm.android.core.util.KstInstant
import com.becalm.android.domain.voice.CommitmentDraft
import com.becalm.android.domain.voice.Direction
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.datetime.Instant

/**
 * Error envelope for a failed POST /v1/voice/transcribe_extract (HTTP 502).
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
 * Spec refs: VOI-006, api-contract.yml § /v1/voice/transcribe_extract 502 envelope.
 */
@JsonClass(generateAdapter = true)
public data class VoiceErrorEnvelope(
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
 * Response body for a successful POST /v1/voice/transcribe_extract (HTTP 200).
 *
 * Wire format (api-contract.yml):
 *   { raw_event_id, commitments: CommitmentDraft[], model, region }
 *
 * @property rawEventId Server-side UUID of the raw_ingestion_event row that was updated.
 * @property commitments List of extracted commitment drafts; may be empty when no
 *   commitments were detected in the audio.
 * @property model LLM model identifier used for extraction (e.g. "gemini-2.5-flash").
 * @property region Vertex AI region used for the call (e.g. "asia-northeast3").
 *
 * Spec refs: VOI-001, VOI-002, VOI-003.
 */
@JsonClass(generateAdapter = true)
public data class TranscribeExtractResponse(
    /** Server-assigned UUID of the updated raw_ingestion_event row. */
    @field:Json(name = "raw_event_id") val rawEventId: String,

    /** Commitments extracted from the audio by the LLM. Empty list when none detected. */
    @field:Json(name = "commitments") val commitments: List<CommitmentDraftDto>,

    /** Model identifier used for extraction, e.g. "gemini-2.5-flash". */
    @field:Json(name = "model") val model: String,

    /** Vertex AI region, e.g. "asia-northeast3". */
    @field:Json(name = "region") val region: String,
)

/**
 * Wire-level DTO for a single commitment extracted from audio.
 *
 * Mirrors the `CommitmentDraft` shared type in api-contract.yml.
 *
 * @property direction Commitment direction: "give" (user owes) or "take" (counterparty owes).
 * @property text Short summary of the commitment (1–2 sentences).
 * @property quote Verbatim audio fragment used as evidentiary source. Never edited.
 * @property personRef Canonicalized counterparty identifier or null.
 * @property dueAt ISO-8601 due date/time or null when not mentioned.
 * @property dueHint Verbatim due-date expression as surfaced by the LLM (e.g. "다음주",
 *   "월말"). Preserved even when [dueAt] is non-null. Null when the LLM did not output a
 *   hint. See data-model.yml:132-144 and VOI-003.
 * @property dueIsApproximate True when [dueAt] was inferred from a fuzzy hint rather than
 *   an explicit calendar reference. Defaults to false for backward compatibility with
 *   older Railway responses that omit the field.
 * @property confidence LLM confidence score in [0.0, 1.0].
 *
 * Spec refs: VOI-003.
 */
@JsonClass(generateAdapter = true)
public data class CommitmentDraftDto(
    /** "give" or "take". */
    @field:Json(name = "direction") val direction: String,

    /** Short commitment summary text. */
    @field:Json(name = "text") val text: String,

    /**
     * Verbatim audio fragment from which this commitment was extracted.
     * Legally sensitive — treated as evidentiary record. Never summarized or edited.
     */
    @field:Json(name = "quote") val quote: String,

    /**
     * Canonicalized counterparty identifier.
     * Precedence: E.164 phone > lowercase email > normalized display name.
     * Null when no identifiable counterparty.
     */
    @field:Json(name = "person_ref") val personRef: String?,

    /**
     * ISO-8601 due instant, or null when no deadline was mentioned.
     *
     * Inbound from Railway `/v1/voice/transcribe_extract`. Wire format per
     * api-contract.yml:32 is ISO-8601 with `+09:00` KST offset. The
     * [KstInstant] qualifier's parser is tolerant and accepts both `+09:00`
     * and `Z` forms, so historical responses that still emit UTC round-trip
     * cleanly. Storage remains UTC.
     */
    @field:KstInstant @field:Json(name = "due_at") val dueAt: Instant?,

    /**
     * Verbatim due-date expression as surfaced by the LLM (e.g. "다음주", "월말").
     * Preserved regardless of whether [dueAt] could be resolved. Default null for backward
     * compatibility with older Railway responses.
     */
    @field:Json(name = "due_hint") val dueHint: String? = null,

    /**
     * True when [dueAt] was inferred from a fuzzy hint rather than an explicit calendar
     * reference. Default false for backward compatibility.
     */
    @field:Json(name = "due_is_approximate") val dueIsApproximate: Boolean = false,

    /** LLM confidence score in [0.0, 1.0]. */
    @field:Json(name = "confidence") val confidence: Float,
) {
    /**
     * Maps this DTO to the domain [CommitmentDraft] type.
     *
     * [direction] is case-insensitively matched; unrecognised values default to [Direction.GIVE]
     * with a debug-visible name preserved in [CommitmentDraft.text] (the raw direction string is
     * not modified — Railway schema validation should prevent unknown values).
     */
    public fun toDomain(): CommitmentDraft = CommitmentDraft(
        direction = when (direction.lowercase()) {
            "take" -> Direction.TAKE
            else -> Direction.GIVE
        },
        text = text,
        quote = quote,
        personRef = personRef,
        dueAt = dueAt,
        dueHint = dueHint,
        dueIsApproximate = dueIsApproximate,
        confidence = confidence,
    )
}
