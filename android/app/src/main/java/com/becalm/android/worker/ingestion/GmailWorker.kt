package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.email.SourceRefEnvelope
import com.becalm.android.data.remote.gmail.GmailAttachmentMeta
import com.becalm.android.data.remote.gmail.GmailClient
import com.becalm.android.data.remote.gmail.GmailLabelScope
import com.becalm.android.data.remote.gmail.GmailMessage
import com.becalm.android.data.remote.gmail.GmailMessagePage
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.email.EmailPersonRef
import com.becalm.android.domain.email.EmailSnippetBuilder
import com.becalm.android.domain.email.SourceKind
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * WorkManager [CoroutineWorker] that syncs Gmail messages into Room as
 * [RawIngestionEventEntity] (`source_type = "gmail"`) plus a companion
 * [EmailBodyEntity] carrying `body_plain` / `body_html` / `attachments_meta` /
 * `raw_headers` for on-device commitment extraction.
 *
 * ## Sync strategy (ING-006, ING-012, ING-013, EMAIL-001)
 *
 * **Full-sync (cold start)** — two sequential passes via
 * [GmailClient.listMessagesFullSyncForLabel]: [GmailLabelScope.INBOX] first
 * (marketing categories excluded) then [GmailLabelScope.SENT] (drafts/trash
 * excluded). Both pages are paginated via `nextPageToken`. The historyId cursor
 * is seeded via the conventional `listHistory("1")` bootstrap only after both
 * passes complete so partial backfills can resume cleanly.
 *
 * **Incremental** — when [SyncCursorStore.observeGmailHistoryId] returns a
 * non-null value, [GmailClient.listHistory] is called with that historyId.
 * Each newly-added message is routed by inspecting its `labelIds`: `INBOX`
 * present and none of CATEGORY_PROMOTIONS/SOCIAL/UPDATES/FORUMS →
 * `folder=INBOX`; `SENT` present → `folder=SENT`; otherwise skipped with a log.
 *
 * **Deduplication** — Room's UNIQUE index on `(user_id, client_event_id)` is
 * the source of truth; `insertLocal`'s read-before-write guard dedupes replays
 * (ING-013 / ING-015).
 *
 * ## EmailBody insert handoff (EMAIL-006)
 * After each successful raw event insert the worker also insert-or-replaces an
 * [EmailBodyEntity] through [EmailBodyRepository.insert]. The repository
 * never crosses the device boundary — body text is room-only per
 * `.spec/email-pipeline.spec.yml:58-64`.
 *
 * ## Commitment extraction handoff (EMAIL-008)
 * After the body insert, a one-shot
 * [com.becalm.android.worker.extraction.CommitmentExtractionWorker] is enqueued
 * via [WorkScheduler.enqueueCommitmentExtraction] **unless** the snippet source
 * is [SourceKind.SUBJECT_FALLBACK] (subject-only emails cannot drive a
 * meaningful LLM extraction — we instead bump the metrics counter).
 *
 * ## PII / logging
 * Subject, snippet, and email addresses are NEVER written to logcat. Only
 * message counts, hashed `sourceRef` surrogates, and cursor values are logged.
 *
 * ## Return semantics
 * - [Result.success] — happy path; all pages stored.
 * - [Result.retry] — transient network/rate-limit error; WorkManager applies backoff.
 * - [Result.failure] — user not authenticated; re-auth required (SP-38).
 */
@HiltWorker
public class GmailWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val gmailClient: GmailClient,
    private val googleAuthTokenProvider: GoogleAuthTokenProviderImpl,
    private val rawIngestionRepository: RawIngestionRepository,
    private val emailBodyRepository: EmailBodyRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val cursorStore: SyncCursorStore,
    private val metricsStore: MetricsStore,
    private val workScheduler: WorkScheduler,
    private val authRepository: AuthRepository,
    private val moshi: Moshi,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    private val sourceRefAdapter: JsonAdapter<SourceRefEnvelope> by lazy {
        // serializeNulls(false)-equivalent: serializeNulls default is false in Moshi,
        // so null in_reply_to / references are automatically omitted per EMAIL-005.
        moshi.adapter(SourceRefEnvelope::class.java)
    }
    private val attachmentListAdapter: JsonAdapter<List<GmailAttachmentMeta>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, GmailAttachmentMeta::class.java))
    }
    private val stringListAdapter: JsonAdapter<List<String>> by lazy {
        moshi.adapter(Types.newParameterizedType(List::class.java, String::class.java))
    }

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        logger.d(TAG, "doWork start")

        // Step 1. Load the persisted Gmail credential into the in-memory cache. The
        // Application.onCreate fire-and-forget warmUp is not a barrier — if WorkManager
        // fires this worker before that launch completes, every Gmail request would see
        // a cold cache. warmUp is idempotent (last-write-wins) so the steady-state cost
        // is one EncryptedSharedPreferences read.
        googleAuthTokenProvider.warmUp()

        // Step 2. If the cached token is absent or aged into the 60s safety window (or
        // was ever emitted into a null hole by a previous hot-path CAS), attempt a
        // silent refresh from the background.
        if (googleAuthTokenProvider.currentToken() == null) {
            val refreshed = googleAuthTokenProvider.refreshSilently(appContext)
            logger.d(TAG, "doWork silent refresh attempted ok=$refreshed")
        }

        // Every ingested row must be tagged with the authenticated user's UUID. Fail closed.
        val userId = authRepository.currentSession()?.userId
        if (userId.isNullOrBlank()) {
            logger.e(TAG, "doWork no authenticated session — refusing to ingest")
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                "unauthorized",
                Clock.System.now(),
            )
            return@withContext Result.failure()
        }

        val storedHistoryId: Long? = cursorStore.observeGmailHistoryId().first()

        val syncResult: SyncOutcome = if (storedHistoryId != null) {
            runIncrementalSync(userId, storedHistoryId.toString())
        } else {
            runFullSync(userId)
        }

        return@withContext when (syncResult) {
            is SyncOutcome.HistoryExpired -> {
                logger.d(TAG, "doWork historyId expired, falling back to full-sync")
                cursorStore.setGmailHistoryId(null)
                runFullSync(userId).toWorkerResult(" fallback-full-sync")
            }
            else -> syncResult.toWorkerResult("")
        }
    }

    private suspend fun SyncOutcome.toWorkerResult(phase: String): Result = when (this) {
        is SyncOutcome.Success -> {
            sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, Clock.System.now())
            logger.d(TAG, "doWork$phase complete fetched=$fetchedCount")
            Result.success()
        }
        is SyncOutcome.Unauthorized -> {
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                "unauthorized",
                Clock.System.now(),
            )
            logger.e(TAG, "doWork$phase unauthorized — user must re-auth")
            Result.failure()
        }
        is SyncOutcome.Retryable -> {
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                reason,
                Clock.System.now(),
            )
            logger.w(TAG, "doWork$phase retryable reason=$reason")
            Result.retry()
        }
        is SyncOutcome.HistoryExpired -> {
            Result.retry()
        }
    }

    // ── Incremental sync ──────────────────────────────────────────────────────

    /**
     * Fetches all pages of history since [startHistoryId] and routes each newly-
     * added message by its `labelIds`. EMAIL-001 discards messages that are
     * neither INBOX (non-category) nor SENT — drafts, spam, trash, and marketing
     * categories are skipped with a log but not treated as an error.
     */
    private suspend fun runIncrementalSync(userId: String, startHistoryId: String): SyncOutcome {
        val result = gmailClient.listHistory(startHistoryId)
        return when (result) {
            is BecalmResult.Failure -> result.error.toSyncOutcome()
            is BecalmResult.Success -> {
                val page = result.value
                val insertedCount = insertIncrementalMessages(userId, page.messageIds)

                val newHistoryId = page.historyId?.toLongOrNull()
                if (newHistoryId != null) {
                    cursorStore.setGmailHistoryId(newHistoryId)
                    logger.d(TAG, "incremental cursor advanced to=$newHistoryId page_msgs=$insertedCount")
                }

                SyncOutcome.Success(insertedCount)
            }
        }
    }

    /**
     * Incremental-sync per-message pipeline: fetch the full message, route it
     * based on `labelIds`, and insert under the resolved folder. Messages that
     * resolve to `null` folder (drafts / spam / marketing categories) are
     * skipped.
     */
    private suspend fun insertIncrementalMessages(userId: String, messageIds: List<String>): Int {
        var count = 0
        for (messageId in messageIds) {
            val msgResult = gmailClient.getMessage(messageId)
            if (msgResult is BecalmResult.Failure) {
                logger.w(TAG, "getMessage failed id_hash=${redact(messageId)} err=${msgResult.error}")
                continue
            }
            val msg = (msgResult as BecalmResult.Success).value
            val folder = routeByLabelIds(msg.labelIds)
            if (folder == null) {
                logger.d(
                    TAG,
                    "incremental skip id_hash=${redact(messageId)} labels=${msg.labelIds.size}",
                )
                continue
            }
            if (insertOne(userId, msg, folder)) count++
        }
        return count
    }

    /**
     * Maps a Gmail label set to the EMAIL-001 folder direction hint. Returns
     * null when the message belongs to neither pipeline (drafts / spam / pure
     * marketing categories). SENT takes precedence over INBOX because a thread-
     * shared message tagged with both is authoritatively the sender-side view
     * for EMAIL-002 person_ref derivation.
     */
    private fun routeByLabelIds(labelIds: List<String>): String? {
        if (labelIds.contains(LABEL_SENT)) return FOLDER_SENT
        if (!labelIds.contains(LABEL_INBOX)) return null
        if (labelIds.any { it in EXCLUDED_INBOX_LABELS }) return null
        return FOLDER_INBOX
    }

    // ── Full sync ─────────────────────────────────────────────────────────────

    private suspend fun runFullSync(userId: String): SyncOutcome {
        var totalFetched = 0

        // EMAIL-001: INBOX pass first then SENT so both direction hints reach
        // ingestion during cold start.
        for (label in GmailLabelScope.entries) {
            when (val outcome = runFullSyncForLabel(userId, label)) {
                is SyncOutcome.Success -> totalFetched += outcome.fetchedCount
                SyncOutcome.HistoryExpired,
                SyncOutcome.Unauthorized,
                is SyncOutcome.Retryable,
                -> return outcome
            }
        }

        seedHistoryIdCursor()

        return SyncOutcome.Success(totalFetched)
    }

    private suspend fun runFullSyncForLabel(
        userId: String,
        label: GmailLabelScope,
    ): SyncOutcome {
        var pageToken: String? = null
        var inserted = 0
        val folder = if (label == GmailLabelScope.SENT) FOLDER_SENT else FOLDER_INBOX
        while (true) {
            val result: BecalmResult<GmailMessagePage> =
                gmailClient.listMessagesFullSyncForLabel(label, pageToken)
            when (result) {
                is BecalmResult.Failure -> return result.error.toSyncOutcome()
                is BecalmResult.Success -> {
                    val page = result.value
                    val insertedThisPage = insertFullSyncPage(userId, page.messageIds, folder)
                    inserted += insertedThisPage
                    logger.d(
                        TAG,
                        "full-sync page folder=$folder inserted=$insertedThisPage running=$inserted",
                    )
                    pageToken = page.nextPageToken ?: return SyncOutcome.Success(inserted)
                }
            }
        }
    }

    /**
     * Full-sync 직후 다음 run에서 incremental-sync를 쓸 수 있도록 historyId 커서를 1회 심는다.
     */
    private suspend fun seedHistoryIdCursor() {
        val historyBootstrap = gmailClient.listHistory("1")
        if (historyBootstrap is BecalmResult.Success) {
            val bootstrapHistoryId = historyBootstrap.value.historyId?.toLongOrNull()
            if (bootstrapHistoryId != null) {
                cursorStore.setGmailHistoryId(bootstrapHistoryId)
                logger.d(TAG, "full-sync seeded historyId=$bootstrapHistoryId")
            }
        }
    }

    // ── Message insertion ─────────────────────────────────────────────────────

    private suspend fun insertFullSyncPage(
        userId: String,
        messageIds: List<String>,
        folder: String,
    ): Int {
        var count = 0
        for (messageId in messageIds) {
            val msgResult = gmailClient.getMessage(messageId)
            if (msgResult is BecalmResult.Failure) {
                logger.w(TAG, "getMessage failed id_hash=${redact(messageId)} err=${msgResult.error}")
                continue
            }
            val msg = (msgResult as BecalmResult.Success).value
            if (insertOne(userId, msg, folder)) count++
        }
        return count
    }

    /**
     * Executes the per-message `raw_event + email_body + extraction handoff`
     * pipeline. Returns true when the raw event was successfully inserted
     * (regardless of whether extraction was enqueued — subject-only emails are
     * a successful skip, not a failure).
     */
    private suspend fun insertOne(userId: String, msg: GmailMessage, folder: String): Boolean {
        val snippetResult = EmailSnippetBuilder.buildSnippet(
            bodyPlain = msg.bodyPlain,
            bodyHtml = msg.bodyHtml,
            subject = msg.subject,
        )

        val rawEntity = msg.toRawEntity(userId = userId, folder = folder, snippet = snippetResult.snippet)

        val insertResult = rawIngestionRepository.insertLocal(rawEntity)
        if (insertResult is BecalmResult.Failure) {
            logger.e(TAG, "insertLocal failed id_hash=${redact(msg.messageId)} err=${insertResult.error}")
            return false
        }
        val rawEventId = (insertResult as BecalmResult.Success).value

        val groupEmail = folder == FOLDER_SENT &&
            EmailPersonRef.isGroupEmail(msg.toAddresses.size)
        val body = buildEmailBody(
            rawEventId = rawEventId,
            msg = msg,
            folder = folder,
            groupEmail = groupEmail,
            parseFailed = snippetResult.parseFailed,
        )
        emailBodyRepository.insert(body)
        logger.d(TAG, "insertLocal ok id_hash=${redact(msg.messageId)} folder=$folder")

        if (snippetResult.parseFailed) {
            // EMAIL-007 graceful degrade: raw event still in timeline but body
            // text is unparseable. Log only (Sentry wiring is a separate PR).
            logger.w(
                TAG,
                "email_html_parse_failed id_hash=${redact(msg.messageId)} provider=gmail",
            )
        }

        // EMAIL-008: skip LLM handoff for subject-only emails — bump the metric
        // instead so the skip rate is visible in the debug screen.
        if (snippetResult.sourceKind == SourceKind.SUBJECT_FALLBACK) {
            metricsStore.incrementSubjectOnlySkipped()
            logger.d(TAG, "subject-only id_hash=${redact(msg.messageId)} — LLM handoff skipped")
        } else {
            workScheduler.enqueueCommitmentExtraction(rawEventId)
        }

        return true
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Builds the [RawIngestionEventEntity] for this Gmail message. [personRef]
     * is derived via the shared [EmailPersonRef] helper per EMAIL-002.
     * [sourceRef] is a Moshi-serialised [SourceRefEnvelope] with null fields
     * omitted.
     */
    private fun GmailMessage.toRawEntity(
        userId: String,
        folder: String,
        snippet: String,
    ): RawIngestionEventEntity {
        val personRef = when (folder) {
            FOLDER_SENT -> EmailPersonRef.forSent(toAddresses)
            else -> EmailPersonRef.forInbox(from)
        }
        val envelope = SourceRefEnvelope(
            messageId = messageIdHeader ?: messageId,
            inReplyTo = inReplyTo,
            references = references,
        )
        val sourceRefJson = sourceRefAdapter.toJson(envelope)

        return RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "gmail:$messageId",
            sourceType = SourceType.GMAIL,
            sourceRef = sourceRefJson,
            eventTitle = subject,
            eventSnippet = snippet,
            personRef = personRef,
            folder = folder,
            timestamp = Instant.fromEpochMilliseconds(internalDate),
        )
    }

    /**
     * Builds the [EmailBodyEntity] row for the just-inserted raw event. When
     * [parseFailed] is true the [EmailBodyEntity.bodyPlain] column is cleared
     * per EMAIL-007 so a partial parse cannot leak into extraction.
     */
    private fun buildEmailBody(
        rawEventId: String,
        msg: GmailMessage,
        folder: String,
        groupEmail: Boolean,
        parseFailed: Boolean,
    ): EmailBodyEntity {
        val toAddressesJson = stringListAdapter.toJson(msg.toAddresses)
        val attachmentsJson = if (msg.attachmentsMeta.isEmpty()) {
            null
        } else {
            attachmentListAdapter.toJson(msg.attachmentsMeta)
        }
        // EMAIL-007: parse_failed forces body_plain=null while keeping body_html
        // verbatim so a future extractor can retry on a different engine.
        val finalBodyPlain: String? = if (parseFailed) null else msg.bodyPlain

        return EmailBodyEntity(
            id = UUID.randomUUID().toString(),
            rawEventId = rawEventId,
            providerMessageId = msg.messageId,
            folder = folder,
            subject = msg.subject,
            fromAddress = msg.from?.let(::canonicalizeEmail),
            toAddresses = toAddressesJson,
            bodyPlain = finalBodyPlain,
            bodyHtml = msg.bodyHtml,
            attachmentsMeta = attachmentsJson,
            rawHeaders = msg.rawHeadersJson,
            parseFailed = parseFailed,
            groupEmail = groupEmail,
            receivedAt = Instant.fromEpochMilliseconds(msg.internalDate),
        )
    }

    public companion object {
        private const val TAG = "GmailWorker"

        // Gmail system labels used for folder routing per EMAIL-001.
        private const val LABEL_INBOX: String = "INBOX"
        private const val LABEL_SENT: String = "SENT"

        /**
         * Gmail labels that disqualify a message from the INBOX ingestion pipeline
         * per EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`): marketing categories
         * (Promotions / Social / Updates / Forums), spam, trash, and drafts. Hoisted
         * to a companion `val` so the set is constructed once per process rather
         * than per message.
         */
        private val EXCLUDED_INBOX_LABELS: Set<String> = setOf(
            "CATEGORY_PROMOTIONS",
            "CATEGORY_SOCIAL",
            "CATEGORY_UPDATES",
            "CATEGORY_FORUMS",
            "SPAM",
            "TRASH",
            "DRAFT",
        )
    }
}

// ─── Sync outcome ADT ────────────────────────────────────────────────────────

/**
 * Internal discriminated union representing the outcome of one sync phase.
 *
 * Kept private to this file; [GmailWorker.doWork] maps these to [CoroutineWorker.Result].
 */
private sealed interface SyncOutcome {
    /** Sync completed; [fetchedCount] messages were processed. */
    data class Success(val fetchedCount: Int) : SyncOutcome

    /** historyId has expired (HTTP 404/410); caller must fall back to full-sync. */
    data object HistoryExpired : SyncOutcome

    /** Google API returned 401; user must re-authenticate. */
    data object Unauthorized : SyncOutcome

    /** Transient failure; WorkManager should retry with backoff. */
    data class Retryable(val reason: String) : SyncOutcome
}

/** Maps a [BecalmError] to the appropriate [SyncOutcome]. */
private fun BecalmError.toSyncOutcome(): SyncOutcome = when (this) {
    is BecalmError.Unauthorized -> SyncOutcome.Unauthorized
    is BecalmError.NotFound -> SyncOutcome.HistoryExpired
    is BecalmError.RateLimited -> SyncOutcome.Retryable("rate_limited retryAfter=${retryAfterSeconds}s")
    is BecalmError.Network -> SyncOutcome.Retryable("network code=$code msg=$message")
    is BecalmError.ServerError -> SyncOutcome.Retryable("server_error code=$code")
    else -> SyncOutcome.Retryable("unknown error=${this::class.simpleName}")
}
