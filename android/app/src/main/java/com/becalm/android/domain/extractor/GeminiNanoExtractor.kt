package com.becalm.android.domain.extractor

import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini Nano on-device implementation of [CommitmentExtractor] (SP-33, KTR-GEMINI-NANO).
 *
 * ## Status: stub — concrete AICore call deferred
 * The Android AICore API surface (`com.google.android.aicore.*`) is pre-release as of the
 * time this file was written (April 2026). The public `InferenceSession` / `TextInference`
 * interfaces are subject to breaking changes before stable SDK release. This stub keeps the
 * full ingestion pipeline compilable and testable without coupling to an unstable API.
 *
 * When the AICore API stabilises (tracked as KTR-GEMINI-NANO), replace the body of [extract]
 * with:
 * 1. Acquire an `InferenceSession` from the `InferenceSessionOptions` for the `GEMINI_NANO`
 *    base model.
 * 2. Build the structured prompt below and call `session.generateResponse(prompt)`.
 * 3. Parse the JSON response into a `List<CommitmentDraft>`.
 * 4. Release the session in a `finally` block.
 *
 * ## Prompt specification
 * The extraction prompt for Korean business text is defined as follows:
 * ```
 * System: You are a commitment extraction assistant for Korean business communication.
 * Extract all "give" commitments (things the writer promised to do) and "take" commitments
 * (things another party promised the writer). Return a JSON array:
 * [{ "text": "...", "direction": "GIVE"|"TAKE", "personRef": "...|null", "dueAt": "ISO-8601|null" }]
 * Return an empty array [] when no commitments are found. Output JSON only; no prose.
 *
 * User: {eventTitle}\n{eventSnippet}
 * ```
 *
 * ## Thread safety
 * This class is `@Singleton`; [extract] is a suspend function and safe to call concurrently
 * from multiple coroutines. The stub implementation is trivially thread-safe because it
 * performs no I/O.
 */
@Singleton
public class GeminiNanoExtractor @Inject constructor() : CommitmentExtractor {

    /**
     * Returns an empty list until the AICore API surface is stable (KTR-GEMINI-NANO).
     *
     * This guarantees that:
     * - The ingestion pipeline compiles and produces correct Room rows with
     *   `commitments_extracted_count = 0`.
     * - Unit tests can assert the [BecalmResult.Success] + empty-list contract.
     * - No dependency on pre-release AICore libraries is introduced in the build graph.
     *
     * @param input Raw ingestion event; currently unused by the stub.
     * @return [BecalmResult.Success] wrapping an empty list.
     */
    override suspend fun extract(input: RawIngestionEventEntity): BecalmResult<List<CommitmentDraft>> {
        // TODO(KTR-GEMINI-NANO): Replace with real Gemini Nano AICore inference once the
        //  Android AICore API surface is stable. See class-level KDoc for the prompt spec
        //  and integration steps.
        return BecalmResult.Success(emptyList())
    }
}
