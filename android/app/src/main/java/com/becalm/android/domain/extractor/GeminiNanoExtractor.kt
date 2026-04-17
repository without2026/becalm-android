package com.becalm.android.domain.extractor

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.domain.voice.CommitmentDraft

/**
 * Extracts [CommitmentDraft]s from a [RawIngestionEventEntity] using Gemini Nano
 * (on-device AICore).
 *
 * In the current MVP, AICore integration is not yet linked, so [extract] always
 * returns an empty list. This stub exists so that the extraction pipeline and
 * tests can compile and verify the contract (SP-33).
 *
 * Spec: SP-33, KTR-GEMINI-NANO
 */
public class GeminiNanoExtractor {

    /**
     * Extracts commitment drafts from the given event entity.
     *
     * @return [BecalmResult.Success] with an empty list while AICore is unlinked.
     */
    public suspend fun extract(entity: RawIngestionEventEntity): BecalmResult<List<CommitmentDraft>> {
        return BecalmResult.Success(emptyList())
    }
}
