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
 * A single commitment extracted from a voice recording via Railway `POST /v1/voice/transcribe_extract`.
 *
 * [CommitmentDraft] is the intermediate domain representation produced by parsing
 * [com.becalm.android.data.remote.dto.CommitmentDraftDto] and consumed by
 * [com.becalm.android.worker.VoiceUploadWorker] to build
 * [com.becalm.android.data.local.db.entity.CommitmentEntity] rows.
 *
 * @param direction Whether the commitment is [Direction.GIVE] (user owes) or [Direction.TAKE]
 *                  (counterparty owes).
 * @param text      Short summary text of the commitment (1–2 sentences as extracted by the LLM).
 * @param quote     Verbatim audio fragment from which this commitment was extracted.
 *                  Legally sensitive — treated as an evidentiary record. Never edited by the app.
 *                  Matches `CommitmentDraft.quote` in api-contract.yml.
 * @param personRef Canonicalized counterparty identifier (E.164 phone, lowercase email, or
 *                  normalized display name). Null when no specific person can be identified.
 * @param dueAt     Optional ISO-8601 instant parsed from the source audio if a due date is
 *                  mentioned; null when no deadline is detected.
 * @param confidence LLM extraction confidence in [0.0, 1.0]. Higher values indicate greater
 *                  certainty. Matches `CommitmentDraft.confidence` in api-contract.yml.
 *
 * Spec refs: VOI-001, VOI-003.
 */
public data class CommitmentDraft(
    val direction: Direction,
    val text: String,
    val quote: String,
    val personRef: String?,
    val dueAt: Instant?,
    val confidence: Float,
)
