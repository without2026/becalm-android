package com.becalm.android.worker.extraction

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.CommitmentDraftDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.domain.email.EmailPromptBuilder
import com.becalm.android.domain.email.EmailSnippetBuilder
import com.becalm.android.domain.email.QuotedBlockSplitter
import com.becalm.android.domain.email.SourceKind
import com.becalm.android.domain.extractor.GeminiNanoExtractor
import com.becalm.android.worker.ProcessingPauseGate
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * WorkManager worker that extracts commitments from a freshly-ingested email via the
 * on-device Gemini Nano model (AICore) and writes the results to the local `commitments`
 * table.
 *
 * ## Contract (EMAIL-001 · EMAIL-005 · EMAIL-008)
 * Invoked by a local email ingestion adapter (today: `ImapNaverWorker` / `ImapDaumWorker`)
 * immediately after that adapter inserts both the [RawIngestionEventEntity] and its
 * matching [EmailBodyEntity]. The worker:
 *
 * 1. Loads the raw event and its email body (scoped by user_id for multi-account safety).
 * 2. Resolves the primary message body, splitting off any quoted reply block via
 *    [QuotedBlockSplitter].
 * 3. Builds the system + user prompts via [EmailPromptBuilder] (loaded from
 *    `res/raw/email_system_prompt.txt`).
 * 4. Calls [GeminiNanoExtractor.extract] to run Gemini Nano on-device.
 * 5. Maps each returned [CommitmentDraftDto] into a [CommitmentEntity] with a deterministic
 *    UUID v3 derived from `"commitment:<rawEventId>:<index>"`, mirroring the voice pipeline's
 *    idempotency strategy so replays upsert rather than duplicate.
 * 6. Updates the parent raw event's `commitmentsExtractedCount`.
 *
 * ## No-op paths (graceful exit as `Result.success`)
 * - [EmailBodyEntity] missing — non-email source types or bodies not yet persisted.
 * - Both [EmailBodyEntity.bodyPlain] and [EmailBodyEntity.bodyHtml] blank — subject-only
 *   emails cannot meaningfully drive an LLM extraction; we instead bump
 *   [MetricsStore.incrementSubjectOnlySkipped] for monitoring (EMAIL-003).
 * - [BecalmError.ExtractorUnavailable] with reason `AICORE_NOT_AVAILABLE` — device does not
 *   support Gemini Nano; no amount of retry will fix it.
 *
 * ## Failure paths
 * - Missing `rawEventId` input → `Result.failure()`; likely a test misconfiguration.
 * - Raw event not found (user signed out between enqueue and run) → `Result.failure()`.
 * - [BecalmError.ExtractorUnavailable] with reason `LLM_JSON_PARSE_FAILED` or
 *   `AICORE_ERROR` → `Result.retry()` so WorkManager applies exponential backoff.
 *   `AICORE_ERROR` is retry-capped because a device/runtime-level AICore failure can otherwise
 *   restore many persisted WorkSpecs into a foreground retry storm.
 * - Any other error bubble → `Result.retry()`.
 *
 * ## Manual commitments (MAN-003 invariant 4)
 * This worker only ever iterates `raw_ingestion_events` → writes new `commitments` rows
 * with a fresh deterministic UUID. Manual commitments (`source_type = 'manual'`) have no
 * backing raw event and are therefore naturally invisible to this worker: there is no
 * code path in this file that mutates existing commitment rows, so a user's manually-added
 * row cannot be touched here. See `.spec/manual-commitment.spec.yml` invariant 4.
 *
 * ## Out of scope
 * - INBOX=take / SENT=give default-direction HINT interpretation: we pass the [folder] value
 *   through [EmailPromptBuilder] which substitutes `default_direction` into the system
 *   prompt; the LLM itself decides based on body evidence, and its output drives the
 *   [CommitmentEntity.direction] field.
 * - person_ref canonicalisation (SENT = `To[0]`, > 10 recipients → null) — lives in
 *   `refactor/worker/email-person-ref`. This worker preserves whatever `personRef` the LLM
 *   surfaces, or whatever the raw event already carries.
 * - Full-body (non-200-char-capped) LLM input — EMAIL-003 caps the snippet at 200 chars and
 *   that is the LLM's current input; a future PR adds an uncapped HTML strip variant.
 *
 * Spec refs: EMAIL-001, EMAIL-003, EMAIL-005, EMAIL-007, EMAIL-008, ADAPT-EMAIL-010.
 */
@HiltWorker
public class CommitmentExtractionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val rawIngestionEventDao: RawIngestionEventDao,
    private val emailBodyDao: EmailBodyDao,
    private val commitmentDao: CommitmentDao,
    private val userPrefsStore: UserPrefsStore,
    private val metricsStore: MetricsStore,
    private val promptBuilder: EmailPromptBuilder,
    private val quotedBlockSplitter: QuotedBlockSplitter,
    private val geminiNanoExtractor: GeminiNanoExtractor,
    private val processingPauseGate: ProcessingPauseGate,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (processingPauseGate.shouldSkip(TAG)) {
            return Result.success()
        }
        val rawEventId = inputData.getString(KEY_RAW_EVENT_ID)
        if (rawEventId.isNullOrBlank()) {
            logger.e(TAG, "missing rawEventId input — failing")
            return Result.failure()
        }

        val userId = userPrefsStore.observeCurrentUserId().first()
        if (userId.isNullOrBlank()) {
            logger.e(TAG, "no active userId — failing id=${redact(rawEventId)}")
            return Result.failure()
        }

        val rawEvent = rawIngestionEventDao.findById(id = rawEventId, userId = userId)
        if (rawEvent == null) {
            logger.e(TAG, "raw event not found id=${redact(rawEventId)}")
            return Result.failure()
        }

        val emailBody = emailBodyDao.getByRawEventId(rawEventId)
        if (emailBody == null) {
            logger.d(
                TAG,
                "no email body for raw event id=${redact(rawEventId)} — non-email source, success no-op",
            )
            return Result.success()
        }

        val bodyForPrompt = resolveBodyForPrompt(emailBody)
        if (bodyForPrompt.isNullOrBlank()) {
            logger.d(
                TAG,
                "subject-only email id=${redact(rawEventId)} — skipping LLM, bumping metric",
            )
            metricsStore.incrementSubjectOnlySkipped()
            return Result.success()
        }

        val split = quotedBlockSplitter.split(bodyForPrompt)
        val folder = rawEvent.folder ?: FOLDER_INBOX_DEFAULT

        val systemContext = promptBuilder.buildSystemContext(
            folder = folder,
            phoneE164Self = null,
            displayNameOverride = null,
        )
        val userContext = promptBuilder.buildUserContext(
            subject = rawEvent.eventTitle,
            from = emailBody.fromAddress,
            to = emailBody.toAddresses,
            snippet = rawEvent.eventSnippet,
            commitmentText = split.commitment,
            quotedText = split.quoted,
        )

        return when (val extractResult = geminiNanoExtractor.extract(systemContext, userContext)) {
            is BecalmResult.Success -> handleDrafts(
                rawEvent = rawEvent,
                userId = userId,
                drafts = extractResult.value,
            )
            is BecalmResult.Failure -> handleFailure(rawEventId, extractResult.error)
        }
    }

    // ── Branching helpers ─────────────────────────────────────────────────────

    /**
     * Returns the plain-text body (or Jsoup-stripped HTML fallback) the LLM should see, or
     * null when neither source produced anything usable.
     *
     * Reuses [EmailSnippetBuilder.buildSnippet] for the HTML fallback path — this incurs the
     * 200-char cap the snippet builder enforces. See the plan's "pitfalls" section: a future
     * PR will add an uncapped HTML-strip variant so the LLM can see the whole body.
     */
    private fun resolveBodyForPrompt(emailBody: EmailBodyEntity): String? {
        if (!emailBody.bodyPlain.isNullOrBlank()) return emailBody.bodyPlain
        if (!emailBody.bodyHtml.isNullOrBlank()) {
            val snippet = EmailSnippetBuilder.buildSnippet(
                bodyPlain = null,
                bodyHtml = emailBody.bodyHtml,
                subject = null,
            )
            if (snippet.sourceKind == SourceKind.HTML_STRIPPED) return snippet.snippet
        }
        return null
    }

    private suspend fun handleDrafts(
        rawEvent: RawIngestionEventEntity,
        userId: String,
        drafts: List<CommitmentDraftDto>,
    ): Result {
        val now = Clock.System.now()
        if (drafts.isNotEmpty()) {
            val entities = drafts.mapIndexed { index, draft ->
                draft.toEmailCommitmentEntity(
                    rawEventId = rawEvent.id,
                    index = index,
                    userId = userId,
                    sourceType = rawEvent.sourceType,
                    sourceRef = rawEvent.sourceRef,
                    sourceEventTitle = rawEvent.eventTitle,
                    sourceEventOccurredAt = rawEvent.timestamp,
                    now = now,
                )
            }
            commitmentDao.insertAll(entities)
        }

        val updated = rawEvent.copy(commitmentsExtractedCount = drafts.size)
        rawIngestionEventDao.update(updated)

        logger.d(
            TAG,
            "extraction success id=${redact(rawEvent.id)} commitments=${drafts.size}",
        )
        return Result.success()
    }

    private fun handleFailure(rawEventId: String, error: BecalmError): Result {
        val reason = (error as? BecalmError.ExtractorUnavailable)?.reason
        return when (reason) {
            REASON_AICORE_NOT_AVAILABLE -> {
                logger.w(
                    TAG,
                    "AICore unavailable id=${redact(rawEventId)} — device unsupported, no retry",
                )
                Result.success()
            }
            REASON_AICORE_ERROR -> {
                if (shouldRetryExtractorFailure(reason, runAttemptCount)) {
                    logger.w(
                        TAG,
                        "extraction failure id=${redact(rawEventId)} reason=$reason attempt=$runAttemptCount — retrying",
                    )
                    Result.retry()
                } else {
                    logger.w(
                        TAG,
                        "extraction failure id=${redact(rawEventId)} reason=$reason attempt=$runAttemptCount — retry budget exhausted, no retry",
                    )
                    Result.success()
                }
            }
            else -> {
                logger.w(
                    TAG,
                    "extraction failure id=${redact(rawEventId)} reason=$reason — retrying",
                )
                Result.retry()
            }
        }
    }

    public companion object {
        private const val TAG: String = "CommitmentExtractionWorker"

        /**
         * MAN-006 test seam: extraction workers only accept sources that can appear on
         * raw_ingestion_events. `manual` has no backing raw row, so this returns false.
         */
        public fun supportsRawEventSource(sourceType: String): Boolean =
            SourceType.isRawIngestionSource(sourceType)

        public fun shouldRetryExtractorFailure(reason: String?, runAttemptCount: Int): Boolean =
            reason != REASON_AICORE_ERROR || runAttemptCount < MAX_AICORE_ERROR_RETRY_ATTEMPTS

        /** WorkManager input [androidx.work.Data] key — UUID of the raw ingestion event. */
        public const val KEY_RAW_EVENT_ID: String = "rawEventId"

        /** Default folder assumed when the raw event has no explicit folder column value. */
        private const val FOLDER_INBOX_DEFAULT: String = "INBOX"

        /**
         * Must match the `REASON_AICORE_NOT_AVAILABLE` constant in
         * [com.becalm.android.domain.extractor.GeminiNanoExtractor] — intentionally duplicated
         * to avoid exposing the extractor's private constants to the worker module.
         */
        private const val REASON_AICORE_NOT_AVAILABLE: String = "AICORE_NOT_AVAILABLE"
        private const val REASON_AICORE_ERROR: String = "AICORE_ERROR"
        private const val MAX_AICORE_ERROR_RETRY_ATTEMPTS: Int = 1
    }
}

// ── Mapper ────────────────────────────────────────────────────────────────────

/**
 * Maps an email-source [CommitmentDraftDto] returned by on-device Gemini Nano to a
 * [CommitmentEntity] ready for Room insertion.
 *
 * The primary-key UUID is computed as UUID v3 (name-based, MD5) over
 * `"commitment:<rawEventId>:<index>"` so a replayed successful extraction upserts onto the
 * same row rather than inserting a duplicate — [CommitmentDao.insertAll] uses
 * [androidx.room.OnConflictStrategy.REPLACE]. Mirrors the voice pipeline's strategy
 * ([com.becalm.android.worker.toCommitmentEntity]) for consistency across sources.
 */
internal fun CommitmentDraftDto.toEmailCommitmentEntity(
    rawEventId: String,
    index: Int,
    userId: String,
    sourceType: String,
    sourceRef: String?,
    sourceEventTitle: String?,
    sourceEventOccurredAt: kotlinx.datetime.Instant,
    now: kotlinx.datetime.Instant,
): CommitmentEntity = CommitmentEntity(
    id = UUID.nameUUIDFromBytes(
        "commitment:$rawEventId:$index".toByteArray(Charsets.UTF_8),
    ).toString(),
    userId = userId,
    direction = direction.lowercase(),
    counterpartyRaw = null,
    personRef = personRef,
    title = text.take(500),
    description = null,
    quote = quote,
    sourceEventTitle = sourceEventTitle,
    sourceEventOccurredAt = sourceEventOccurredAt,
    dueAt = dueAt,
    dueHint = dueHint,
    dueIsApproximate = dueIsApproximate,
    actionState = "pending",
    sourceType = sourceType,
    sourceRef = sourceRef,
    confidence = confidence.toDouble(),
    commitmentState = CommitmentLifecycleLegacy.DRAFT,
    syncStatus = "pending",
    createdAt = now,
    updatedAt = now,
)
