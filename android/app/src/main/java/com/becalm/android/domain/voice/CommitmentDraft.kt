package com.becalm.android.domain.voice

import kotlinx.datetime.Instant

/**
 * Represents the directionality of a commitment extracted from business audio.
 *
 * - [GIVE] — the authenticated user is the party making the commitment (e.g. "I will send the report").
 * - [TAKE] — another party made a commitment toward the authenticated user (e.g. "Kim will prepare the slides").
 */
public enum class Direction {
    GIVE,
    TAKE,
}

/**
 * A single action-commitment projection extracted from a voice recording via Railway
 * `POST /v1/voice/transcribe_extract`.
 *
 * The voice endpoint's source-of-truth contract is now `items[]` with `type=action|schedule|decision`.
 * [CommitmentDraft] remains the intermediate domain representation for the current action-only
 * compatibility layer: Android projects `type=action` items into
 * [com.becalm.android.data.remote.dto.CommitmentDraftDto], then consumes them in
 * [com.becalm.android.worker.VoiceUploadWorker] to build
 * [com.becalm.android.data.local.db.entity.CommitmentEntity] rows.
 *
 * @param direction Whether the commitment is [Direction.GIVE] (user owes) or [Direction.TAKE]
 *                  (counterparty owes).
 * @param text      Short summary text of the commitment (1–2 sentences as extracted by the LLM).
 * @param quote     Verbatim audio fragment from which this commitment was extracted.
 *                  Legally sensitive — treated as an evidentiary record. Never edited by the app.
 *                  Matches `CommitmentDraft.quote` in api-contract.yml.
 * @param counterpartyRef Canonicalized counterparty identifier (E.164 phone, lowercase email, or
 *                  normalized display name). Null when no specific person can be identified.
 * @param dueAt     Optional ISO-8601 instant parsed from the source audio if a due date is
 *                  mentioned; null when no deadline is detected.
 * @param dueHint   Optional verbatim due-date expression as spoken (e.g. "다음주", "월말").
 *                  Preserved regardless of whether [dueAt] could be resolved to a concrete
 *                  instant so that downstream UI can render the original hint alongside the
 *                  computed deadline. See `.spec/contracts/data-model.yml:132-144` (three-column
 *                  commitments shape) and `.spec/voice-pipeline.spec.yml` VOI-003 (structured
 *                  LLM output including `due_hint`). Null when the LLM did not surface a hint.
 * @param dueIsApproximate
 *                  True when [dueAt] was inferred from a fuzzy hint (e.g. "next week") rather
 *                  than an explicit calendar reference. The UI renders a `~` prefix on the
 *                  D-N badge in this case. Default: false.
 *                  See `.spec/contracts/data-model.yml:132-144` and VOI-003.
 * @param confidence LLM extraction confidence in [0.0, 1.0]. Higher values indicate greater
 *                  certainty. Matches `CommitmentDraft.confidence` in api-contract.yml.
 *
 * Spec refs: VOI-001, VOI-003.
 */
public data class CommitmentDraft(
    val direction: Direction,
    val text: String,
    val quote: String,
    val counterpartyRef: String?,
    val dueAt: Instant?,
    val dueHint: String? = null,
    val dueIsApproximate: Boolean = false,
    val confidence: Float,
)
