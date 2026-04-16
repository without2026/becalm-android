package com.becalm.android.domain.extractor

/**
 * Represents the directionality of a commitment extracted from business text.
 *
 * - [GIVE] — the authenticated user is the party making the commitment (e.g. "I will send the report").
 * - [TAKE] — another party made a commitment toward the authenticated user (e.g. "Kim will prepare the slides").
 */
public enum class Direction {
    GIVE,
    TAKE,
}

/**
 * A single commitment extracted from a raw ingestion event before it is persisted to Room.
 *
 * [CommitmentDraft] is the intermediate representation produced by [CommitmentExtractor] and
 * consumed by the ingestion pipeline. It becomes a
 * [com.becalm.android.data.local.db.entity.CommitmentEntity] once written to Room.
 *
 * @param text      The verbatim or paraphrased commitment text as extracted from the source.
 * @param direction Whether the commitment is [Direction.GIVE] (user owes) or [Direction.TAKE]
 *                  (counterparty owes).
 * @param personRef Canonicalized counterparty identifier (E.164 phone, lowercase email, or
 *                  normalized display name). Null when no specific person can be identified.
 * @param dueAt     Optional ISO-8601 instant parsed from the source text if a due date is
 *                  mentioned; null when no deadline is detected.
 */
public data class CommitmentDraft(
    val text: String,
    val direction: Direction,
    val personRef: String?,
    val dueAt: kotlinx.datetime.Instant?,
)
