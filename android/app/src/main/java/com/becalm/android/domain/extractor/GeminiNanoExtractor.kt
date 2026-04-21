package com.becalm.android.domain.extractor

import android.content.Context
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device commitment extractor backed by Google AI Edge AICore (Gemini Nano).
 *
 * Replaces the MVP stub: the class is now a real bridge between
 * [com.becalm.android.worker.extraction.CommitmentExtractionWorker] and the AICore
 * [GenerativeModel] SDK. The worker builds the system and user prompts via
 * [com.becalm.android.domain.email.EmailPromptBuilder]; this class's only job is to run the
 * combined prompt through the on-device model and parse the model's JSON output into
 * [CommitmentDraftDto] records that the worker writes to Room.
 *
 * ## Why the signature changed from `extract(entity)` → `extract(systemContext, userContext)`
 * Prompt construction is a per-source concern (voice vs email vs future adapters each render
 * different preamble), so it belongs in the caller. This class knows only how to turn a
 * string pair into a JSON list. That keeps the extractor reusable across sources without
 * adding source-specific branches here.
 *
 * ## Failure mapping ([BecalmError.ExtractorUnavailable])
 * | Cause                                      | `reason`                 | Worker action           |
 * |--------------------------------------------|--------------------------|-------------------------|
 * | Device not supported by AICore             | `AICORE_NOT_AVAILABLE`   | `Result.success()` — skip |
 * | Model returned non-JSON or wrong schema    | `LLM_JSON_PARSE_FAILED`  | quarantine (no retry)   |
 * | Any other SDK exception (IO / crash / etc) | `AICORE_ERROR`           | `Result.retry()`        |
 *
 * The call-site matches on [BecalmError.ExtractorUnavailable.reason] to decide which of the
 * three WorkManager outcomes to return — see `CommitmentExtractionWorker.doWork` for the
 * lookup table.
 *
 * Spec refs: EMAIL-001, EMAIL-008, KTR-GEMINI-NANO.
 */
@Singleton
public class GeminiNanoExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi,
) {

    /**
     * Moshi adapter for `List<CommitmentDraftDto>`. Built once so each extraction call skips
     * the reflective type-resolution work.
     */
    private val draftListAdapter by lazy {
        val listType = Types.newParameterizedType(List::class.java, CommitmentDraftDto::class.java)
        moshi.adapter<List<CommitmentDraftDto>>(listType)
    }

    /**
     * Runs [systemContext] + [userContext] through Gemini Nano and returns the parsed list of
     * commitment drafts.
     *
     * The prompt is the concatenation `systemContext + "\n\n" + userContext` so the system
     * prompt always appears at the top of the combined input — AICore does not yet expose a
     * first-class system-role channel on the experimental SDK, so the caller emulates the
     * two-part shape with an explicit join.
     *
     * @return
     * - [BecalmResult.Success] with the parsed list (possibly empty when the model detected
     *   no commitments).
     * - [BecalmResult.Failure] wrapping a [BecalmError.ExtractorUnavailable] whose [reason]
     *   distinguishes device-unsupported, schema-violation, and generic SDK errors.
     */
    public suspend fun extract(
        systemContext: String,
        userContext: String,
    ): BecalmResult<List<CommitmentDraftDto>> {
        val model = try {
            GenerativeModel(
                generationConfig = generationConfig {
                    context = this@GeminiNanoExtractor.context
                    temperature = GENERATION_TEMPERATURE
                    topK = GENERATION_TOP_K
                    maxOutputTokens = GENERATION_MAX_OUTPUT_TOKENS
                },
            )
        } catch (t: Throwable) {
            return BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(
                    reason = REASON_AICORE_NOT_AVAILABLE,
                    cause = t,
                ),
            )
        }

        val combinedPrompt = buildString {
            append(systemContext).append("\n\n").append(userContext)
        }

        val rawText = try {
            val response = model.generateContent(combinedPrompt)
            response.text ?: ""
        } catch (t: Throwable) {
            return BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(
                    reason = REASON_AICORE_ERROR,
                    cause = t,
                ),
            )
        } finally {
            runCatching { model.close() }
        }

        val jsonArray = extractJsonArray(rawText)
            ?: return BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(reason = REASON_LLM_JSON_PARSE_FAILED),
            )

        val parsed = try {
            draftListAdapter.fromJson(jsonArray)
        } catch (t: Throwable) {
            return BecalmResult.Failure(
                BecalmError.ExtractorUnavailable(
                    reason = REASON_LLM_JSON_PARSE_FAILED,
                    cause = t,
                ),
            )
        }

        return BecalmResult.Success(parsed ?: emptyList())
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Returns the `[ ... ]` substring of [rawText], or null when no JSON array shape is
     * detected. LLMs frequently add conversational prefix/suffix text around structured
     * output even when the prompt asks for pure JSON, so isolating the array before feeding
     * Moshi avoids wasted [com.squareup.moshi.JsonDataException] noise on otherwise-valid
     * payloads.
     */
    private fun extractJsonArray(rawText: String): String? {
        val start = rawText.indexOf('[')
        val end = rawText.lastIndexOf(']')
        if (start < 0 || end < 0 || end <= start) return null
        return rawText.substring(start, end + 1)
    }

    private companion object {
        /** Lower temperature → more deterministic commitment extraction output. */
        private const val GENERATION_TEMPERATURE: Float = 0.2f

        /** Constrain top-K to cut hallucinations on the JSON structure. */
        private const val GENERATION_TOP_K: Int = 16

        /**
         * Budget tokens generously so long emails (multiple commitments) still fit; the
         * on-device Nano model's ceiling is small enough that we cannot afford a truncated
         * JSON tail.
         */
        private const val GENERATION_MAX_OUTPUT_TOKENS: Int = 1024

        /** AICore SDK reports the device does not support Gemini Nano. */
        private const val REASON_AICORE_NOT_AVAILABLE: String = "AICORE_NOT_AVAILABLE"

        /** Model output was not parseable as the expected `CommitmentDraftDto[]` schema. */
        private const val REASON_LLM_JSON_PARSE_FAILED: String = "LLM_JSON_PARSE_FAILED"

        /** Any other SDK failure — transient, caller should retry. */
        private const val REASON_AICORE_ERROR: String = "AICORE_ERROR"
    }
}
