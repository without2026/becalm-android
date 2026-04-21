package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.OUTLOOK_MAIL_INBOX_CURSOR_KEY
import com.becalm.android.data.local.datastore.OUTLOOK_MAIL_SENT_CURSOR_KEY
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.email.SourceRefEnvelope
import com.becalm.android.data.remote.msgraph.GraphAttachmentMeta
import com.becalm.android.data.remote.msgraph.GraphMessage
import com.becalm.android.data.remote.msgraph.MsGraphClient
import com.becalm.android.data.remote.msgraph.OutlookMailFolder
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.email.EmailSnippetBuilder
import com.becalm.android.domain.email.SourceKind
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.hasExceededMaxRetries
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
 * Periodic [CoroutineWorker] that syncs Outlook / Office 365 mail via the Microsoft Graph
 * folder-scoped delta API (spec ING-007, EMAIL-001..007).
 *
 * ## Folder-scoped delta (Wave 3)
 * Indexing is scoped to two Outlook system folders only — `Inbox` and `Sent Items`
 * (ING-007 excludes `Drafts`, `Deleted Items`, `Junk Email`, `Archive`). Two independent
 * delta cursors are stored via [SyncCursorStore] under
 * [OUTLOOK_MAIL_INBOX_CURSOR_KEY] and [OUTLOOK_MAIL_SENT_CURSOR_KEY]; the worker runs
 * the two passes sequentially so a failure in one pass leaves the other's cursor
 * untouched.
 *
 * ## Body / headers / attachments (EMAIL-004..007)
 * Each page of messages is converted to both a `raw_ingestion_events` row and a
 * `email_body` row (Room-only, PIPA Article 29) holding body_plain/body_html,
 * attachments metadata, and raw `internetMessageHeaders` JSON. HTML parsing is delegated
 * to [EmailSnippetBuilder] which falls through to the subject line on Jsoup failure
 * (EMAIL-007 graceful degrade). On `SUBJECT_FALLBACK` the `CommitmentExtractionWorker`
 * enqueue is skipped per `.spec/email-pipeline.spec.yml:36 § EMAIL-003`.
 *
 * ## PII
 * Subject, body, sender / recipient addresses are never logged. Only the Graph message
 * `id` (and counts / folder names) appear in logcat.
 *
 * ## 410 recovery
 * [BecalmError.NotFound] on either pass clears *only* that pass's cursor so the next
 * retry performs a full re-sync of the affected folder while leaving the other folder's
 * progress intact.
 */
@HiltWorker
public class OutlookMailWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val msGraphClient: MsGraphClient,
    private val rawIngestionRepository: RawIngestionRepository,
    private val emailBodyRepository: EmailBodyRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val syncCursorStore: SyncCursorStore,
    private val authRepository: AuthRepository,
    private val workScheduler: WorkScheduler,
    private val metricsStore: MetricsStore,
    private val moshi: Moshi,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    private val sourceRefAdapter by lazy { moshi.adapter(SourceRefEnvelope::class.java) }
    private val attachmentListAdapter by lazy {
        val listType = Types.newParameterizedType(List::class.java, GraphAttachmentMeta::class.java)
        moshi.adapter<List<GraphAttachmentMeta>>(listType)
    }
    private val stringListAdapter by lazy {
        val listType = Types.newParameterizedType(List::class.java, String::class.java)
        moshi.adapter<List<String>>(listType)
    }

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        val now = Clock.System.now()
        logger.d(TAG, "doWork started runAttemptCount=$runAttemptCount")

        val userId = authRepository.currentSession()?.userId
        if (userId.isNullOrBlank()) {
            logger.e(TAG, "doWork no authenticated session — refusing to ingest")
            sourceStatusRepository.recordSyncError(
                SourceType.OUTLOOK_MAIL,
                "unauthorized",
                now,
            )
            return@withContext Result.failure()
        }

        sourceStatusRepository.recordSyncStart(SourceType.OUTLOOK_MAIL)

        // ── Pass 1: INBOX ─────────────────────────────────────────────────────
        val inboxOutcome = runDeltaLoop(
            folder = OutlookMailFolder.INBOX,
            cursorKey = OUTLOOK_MAIL_INBOX_CURSOR_KEY,
            userId = userId,
            now = now,
        )
        if (inboxOutcome is SyncOutcome.Terminal) {
            return@withContext inboxOutcome.result
        }

        // ── Pass 2: SENT ──────────────────────────────────────────────────────
        val sentOutcome = runDeltaLoop(
            folder = OutlookMailFolder.SENT,
            cursorKey = OUTLOOK_MAIL_SENT_CURSOR_KEY,
            userId = userId,
            now = now,
        )
        if (sentOutcome is SyncOutcome.Terminal) {
            return@withContext sentOutcome.result
        }

        val totalFetched =
            (inboxOutcome as SyncOutcome.Success).fetchedCount +
                (sentOutcome as SyncOutcome.Success).fetchedCount
        sourceStatusRepository.recordSyncSuccess(SourceType.OUTLOOK_MAIL, now)
        logger.d(TAG, "doWork complete totalFetched=$totalFetched")
        return@withContext Result.success()
    }

    // ── Per-folder delta loop ─────────────────────────────────────────────────

    /**
     * Streams all pages from [folder] into Room + EmailBody, advancing [cursorKey] per page.
     *
     * Returns [SyncOutcome.Success] on clean completion, or [SyncOutcome.Terminal] with the
     * WorkManager [Result] to return when the pass hits a transport error / auth failure.
     */
    private suspend fun runDeltaLoop(
        folder: OutlookMailFolder,
        cursorKey: String,
        userId: String,
        now: Instant,
    ): SyncOutcome {
        var cursor = syncCursorStore.observeCursor(cursorKey).first()
        var totalFetched = 0

        while (true) {
            val result = msGraphClient.messagesDeltaForFolder(folder, cursor)

            when (result) {
                is BecalmResult.Failure -> {
                    val error = result.error
                    logger.w(TAG, "messagesDeltaForFolder failed folder=$folder error=${error::class.simpleName}")
                    sourceStatusRepository.recordSyncError(
                        SourceType.OUTLOOK_MAIL,
                        error::class.simpleName ?: "unknown",
                        now,
                    )
                    return SyncOutcome.Terminal(mapErrorToResult(error, cursor, cursorKey))
                }

                is BecalmResult.Success -> {
                    val page = result.value
                    val messages = page.value

                    if (messages.isNotEmpty()) {
                        val entities = messages.map { it.toEntity(userId, folder) }
                        val insertResult = rawIngestionRepository.insertLocalBatch(entities)
                        if (insertResult is BecalmResult.Failure) {
                            logger.e(TAG, "insertLocalBatch failed folder=$folder — retrying")
                            return SyncOutcome.Terminal(Result.retry())
                        }
                        val rawEventIds = (insertResult as BecalmResult.Success).value
                        // Persist EmailBody side-effects + optional CommitmentExtraction
                        // enqueue per message. rawEventIds is ordered the same as messages
                        // because insertLocalBatch maps 1:1 from the input list.
                        messages.forEachIndexed { index, message ->
                            val rawEventId = rawEventIds.getOrNull(index) ?: return@forEachIndexed
                            persistEmailBody(message, rawEventId, folder)
                        }
                        totalFetched += messages.size
                        logger.d(TAG, "page inserted folder=$folder count=${messages.size} total=$totalFetched")
                    }

                    when {
                        page.nextLink != null -> {
                            syncCursorStore.setCursor(cursorKey, page.nextLink)
                            cursor = page.nextLink
                            logger.d(TAG, "page done folder=$folder following nextLink")
                        }

                        page.deltaLink != null -> {
                            syncCursorStore.setCursor(cursorKey, page.deltaLink)
                            logger.d(TAG, "pass complete folder=$folder deltaLink stored totalFetched=$totalFetched")
                            return SyncOutcome.Success(totalFetched)
                        }

                        else -> {
                            logger.w(TAG, "no nextLink or deltaLink folder=$folder — treating as complete")
                            return SyncOutcome.Success(totalFetched)
                        }
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return SyncOutcome.Success(totalFetched)
    }

    // ── Entity mapping ────────────────────────────────────────────────────────

    /**
     * Converts a [GraphMessage] into a [RawIngestionEventEntity].
     *
     * Derives folder-aware [RawIngestionEventEntity.personRef] per EMAIL-002:
     *  - INBOX → `canonicalizeEmail(fromEmail)`
     *  - SENT & `toRecipients.size <= 10` → `canonicalizeEmail(toRecipients[0])`
     *  - SENT & `toRecipients.size > 10` → null (group email quarantine)
     *  - SENT & empty → null
     *
     * `sourceRef` is a JSON [SourceRefEnvelope] `{message_id, in_reply_to?, references?}`
     * per EMAIL-005. `internetMessageId` falls back to the Graph `id` when the RFC 2822
     * header is absent (e.g., drafts).
     * `eventSnippet` is built by [EmailSnippetBuilder] — the final value is not set here
     * and is instead filled in [persistEmailBody] after the insert so the snippet +
     * commitment-extraction enqueue stay co-located.
     */
    private fun GraphMessage.toEntity(
        userId: String,
        folder: OutlookMailFolder,
    ): RawIngestionEventEntity {
        val folderLabel = when (folder) {
            OutlookMailFolder.INBOX -> FOLDER_INBOX
            OutlookMailFolder.SENT -> FOLDER_SENT
        }
        val personRef = derivePersonRef(folder)
        // Default Moshi adapter omits null-valued fields — matches EMAIL-005's "omit
        // when null" note so the envelope never carries `"in_reply_to":null` noise.
        val sourceRefJson = sourceRefAdapter.toJson(
            SourceRefEnvelope(
                messageId = internetMessageId ?: id,
                inReplyTo = inReplyTo,
                references = references,
            ),
        )
        val snippetResult = EmailSnippetBuilder.buildSnippet(
            bodyPlain = bodyPlain,
            bodyHtml = bodyHtml,
            subject = subject,
        )
        return RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "outlook:$id",
            sourceType = SourceType.OUTLOOK_MAIL,
            sourceRef = sourceRefJson,
            personRef = personRef,
            eventTitle = subject,
            eventSnippet = snippetResult.snippet,
            folder = folderLabel,
            timestamp = receivedDateTime,
        )
    }

    /**
     * Returns the EMAIL-002 personRef for this message given the [folder] scope it was
     * fetched under. Lowercase canonicalisation is delegated to the shared
     * [canonicalizeEmail] helper (co-located with GmailWorker to keep email address
     * parsing in one place).
     */
    private fun GraphMessage.derivePersonRef(folder: OutlookMailFolder): String? = when (folder) {
        OutlookMailFolder.INBOX -> fromEmail?.let(::canonicalizeEmail)
        OutlookMailFolder.SENT -> when {
            toRecipients.isEmpty() -> null
            toRecipients.size > GROUP_EMAIL_RECIPIENT_THRESHOLD -> null
            else -> canonicalizeEmail(toRecipients.first())
        }
    }

    // ── EmailBody persistence ────────────────────────────────────────────────

    /**
     * Writes an [EmailBodyEntity] for [rawEventId] and, when the snippet was *not*
     * derived from subject-only fallback, enqueues the commitment extraction worker
     * for follow-up LLM processing.
     *
     * Attachments are fetched lazily — only when `hasAttachments` is true — and the
     * failure of the attachments call never blocks the body insert (EMAIL-004 is a
     * soft dependency; the row carries an empty `attachmentsMeta` JSON on fetch
     * failure).
     *
     * HTML parse failures surface via [com.becalm.android.domain.email.SnippetResult.parseFailed]
     * flipping the `parse_failed` column, per EMAIL-007 graceful degrade.
     */
    private suspend fun persistEmailBody(
        message: GraphMessage,
        rawEventId: String,
        folder: OutlookMailFolder,
    ) {
        val folderLabel = when (folder) {
            OutlookMailFolder.INBOX -> FOLDER_INBOX
            OutlookMailFolder.SENT -> FOLDER_SENT
        }
        val snippetResult = EmailSnippetBuilder.buildSnippet(
            bodyPlain = message.bodyPlain,
            bodyHtml = message.bodyHtml,
            subject = message.subject,
        )

        val attachmentsMeta: List<GraphAttachmentMeta> = if (message.hasAttachments) {
            when (val attResult = msGraphClient.messageAttachments(message.id)) {
                is BecalmResult.Success -> attResult.value
                is BecalmResult.Failure -> {
                    logger.w(
                        TAG,
                        "attachment fetch failed id=${message.id.take(8)} err=${attResult.error::class.simpleName}",
                    )
                    emptyList()
                }
            }
        } else {
            emptyList()
        }

        val isGroupEmail = folder == OutlookMailFolder.SENT &&
            message.toRecipients.size > GROUP_EMAIL_RECIPIENT_THRESHOLD

        val body = EmailBodyEntity(
            id = UUID.randomUUID().toString(),
            rawEventId = rawEventId,
            providerMessageId = message.id,
            folder = folderLabel,
            subject = message.subject,
            fromAddress = message.fromEmail?.let(::canonicalizeEmail),
            toAddresses = if (message.toRecipients.isEmpty()) {
                null
            } else {
                stringListAdapter.toJson(message.toRecipients.map { it.lowercase() })
            },
            bodyPlain = if (snippetResult.parseFailed) null else message.bodyPlain,
            bodyHtml = message.bodyHtml,
            attachmentsMeta = if (attachmentsMeta.isEmpty()) {
                null
            } else {
                attachmentListAdapter.toJson(attachmentsMeta)
            },
            rawHeaders = message.rawHeadersJson,
            parseFailed = snippetResult.parseFailed,
            groupEmail = isGroupEmail,
            receivedAt = message.receivedDateTime,
        )
        emailBodyRepository.insert(body)

        // EMAIL-003: subject-only snippets do not carry enough content for the LLM to
        // extract a meaningful commitment. Bump the metric and skip the enqueue; the
        // raw event + body are still retained so the UI can surface the row.
        if (snippetResult.sourceKind == SourceKind.SUBJECT_FALLBACK) {
            metricsStore.incrementSubjectOnlySkipped()
            return
        }
        workScheduler.enqueueCommitmentExtraction(rawEventId)
    }

    // ── Error mapping ────────────────────────────────────────────────────────

    /**
     * Maps a [BecalmError] from the Graph call to the appropriate [Result] for WorkManager.
     * HTTP 410 clears ONLY [cursorKey] so the sibling folder's cursor is not disturbed.
     */
    private suspend fun mapErrorToResult(
        error: BecalmError,
        currentCursor: String?,
        cursorKey: String,
    ): Result = when (error) {
        is BecalmError.Unauthorized -> {
            logger.w(TAG, "401 Unauthorized — hard failure, re-auth required")
            Result.failure()
        }

        is BecalmError.NotFound -> {
            logger.w(TAG, "410 delta token expired for cursor=$cursorKey — clearing")
            syncCursorStore.clearCursor(cursorKey)
            Result.retry()
        }

        is BecalmError.RateLimited -> {
            logger.w(TAG, "429 rate limited retryAfter=${error.retryAfterSeconds}s")
            Result.retry()
        }

        else -> {
            logger.w(TAG, "transient error — retrying cursor=${currentCursor?.take(40)}")
            Result.retry()
        }
    }

    // ── Sync outcome ADT (internal to a single doWork invocation) ────────────

    private sealed interface SyncOutcome {
        data class Success(val fetchedCount: Int) : SyncOutcome

        /** The pass exited with a terminal WorkManager [Result] (retry / failure). */
        data class Terminal(val result: Result) : SyncOutcome
    }

    public companion object {
        private const val TAG = "OutlookMailWorker"

        /** Maximum number of WorkManager retry attempts before failing permanently. */
        public const val MAX_RETRIES: Int = 5

        /**
         * `SENT` toRecipients.size > this value → personRef = null (group email quarantine)
         * per EMAIL-002 (`.spec/email-pipeline.spec.yml:22-27`).
         */
        internal const val GROUP_EMAIL_RECIPIENT_THRESHOLD: Int = 10
    }
}
