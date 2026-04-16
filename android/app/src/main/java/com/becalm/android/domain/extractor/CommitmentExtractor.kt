package com.becalm.android.domain.extractor

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity

/**
 * Extracts give/take commitment drafts from a raw ingestion event.
 *
 * Implementations analyse the text content of [RawIngestionEventEntity] (primarily
 * [RawIngestionEventEntity.eventTitle] and [RawIngestionEventEntity.eventSnippet]) and return a
 * list of [CommitmentDraft] values representing detected business commitments.
 *
 * An empty list is a valid successful result — it means no commitments were detected.
 *
 * ## Implementations
 * - [GeminiNanoExtractor] — on-device inference via Gemini Nano / Android AICore (SP-33).
 *   Currently a stub pending AICore API stabilisation (KTR-GEMINI-NANO).
 *
 * ## Error contract
 * [BecalmResult.Failure] is returned only for unrecoverable infrastructure errors
 * (e.g. model not available, serialization failure). Extraction producing zero results
 * is always [BecalmResult.Success] with an empty list.
 */
public interface CommitmentExtractor {

    /**
     * Analyses [input] and returns a list of detected [CommitmentDraft] values.
     *
     * @param input Raw ingestion event to analyse. The extractor reads
     *   [RawIngestionEventEntity.eventTitle], [RawIngestionEventEntity.eventSnippet],
     *   and [RawIngestionEventEntity.sourceType] to determine how to interpret the content.
     * @return [BecalmResult.Success] with zero or more drafts, or [BecalmResult.Failure] on
     *   infrastructure error.
     */
    public suspend fun extract(input: RawIngestionEventEntity): BecalmResult<List<CommitmentDraft>>
}
