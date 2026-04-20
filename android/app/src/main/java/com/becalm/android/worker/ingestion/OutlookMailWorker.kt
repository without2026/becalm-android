package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.msgraph.GraphMessage
import com.becalm.android.data.remote.msgraph.MsGraphClient
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.hasExceededMaxRetries
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
 * `/me/messages/delta` API (spec ING-006).
 *
 * ## Sync protocol (delta tokens)
 * MS Graph delta endpoints return an opaque full URL in either `@odata.nextLink` (more pages
 * in this batch) or `@odata.deltaLink` (end of batch; cursor for next sync). The worker:
 * 1. Reads the persisted cursor from [SyncCursorStore] using key [CURSOR_KEY].
 * 2. Calls [MsGraphClient.messagesDelta] with the cursor (or `null` on first run).
 * 3. Persists a `nextLink` mid-batch so a crash can resume from the last consumed page.
 * 4. Inserts each page's messages into Room via [RawIngestionRepository.insertPending].
 * 5. When a `deltaLink` is received, persists it as the cursor and exits the loop.
 *
 * ## Crash resilience
 * After each successful page insert the `nextLink` is stored as the cursor. On the next
 * WorkManager retry the worker resumes from the last successfully consumed page rather than
 * re-fetching from scratch or from the original `deltaLink`.
 *
 * ## PII
 * Subject, body preview, sender email, and sender name are never logged.
 * Only the Graph message `id` (8-char hex hash) and page/total counts appear in logcat.
 *
 * ## Error handling
 * - [com.becalm.android.core.result.BecalmError.Unauthorized] → [Result.failure]
 *   (MSAL token invalid; re-auth required in UI)
 * - [com.becalm.android.core.result.BecalmError.RateLimited] → [Result.retry]
 *   (WorkManager back-off handles the delay)
 * - [com.becalm.android.core.result.BecalmError.NotFound] (HTTP 410) → cursor cleared,
 *   [Result.retry] to trigger a full re-sync on the next attempt
 * - All other failures → [Result.retry] up to WorkManager's default attempt limit
 *
 * ## Scheduled by
 * SP-32 WorkScheduler registers this class as a periodic job (ING-006 cadence).
 */
@HiltWorker
public class OutlookMailWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val msGraphClient: MsGraphClient,
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val syncCursorStore: SyncCursorStore,
    private val authRepository: AuthRepository,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(ioDispatcher) {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return@withContext Result.failure()

        val now = Clock.System.now()
        logger.d(TAG, "doWork started runAttemptCount=$runAttemptCount")

        // Every ingested row must be tagged with the authenticated user's UUID so that
        // UploadWorker (which filters by user_id) can see the row. Fail closed if no session.
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

        var cursor = syncCursorStore.observeCursor(CURSOR_KEY).first()
        var totalFetched = 0

        // ── Pagination loop ───────────────────────────────────────────────────
        while (true) {
            val result = msGraphClient.messagesDelta(cursor)

            when (result) {
                is BecalmResult.Failure -> {
                    val error = result.error
                    logger.w(TAG, "messagesDelta failed error=${error::class.simpleName}")
                    sourceStatusRepository.recordSyncError(
                        SourceType.OUTLOOK_MAIL,
                        error::class.simpleName ?: "unknown",
                        now,
                    )
                    return@withContext mapErrorToResult(error, cursor)
                }

                is BecalmResult.Success -> {
                    val page = result.value
                    val messages = page.value

                    // Insert this page into Room before updating the cursor.
                    if (messages.isNotEmpty()) {
                        val entities = messages.map { it.toEntity(userId, now) }
                        val insertResult = rawIngestionRepository.insertLocalBatch(entities)
                        if (insertResult is BecalmResult.Failure) {
                            logger.e(TAG, "insertLocalBatch failed — retrying")
                            return@withContext Result.retry()
                        }
                        totalFetched += messages.size
                        logger.d(TAG, "page inserted count=${messages.size} total=$totalFetched")
                    }

                    when {
                        page.nextLink != null -> {
                            // Persist nextLink so a crash mid-batch resumes from here.
                            syncCursorStore.setCursor(CURSOR_KEY, page.nextLink)
                            cursor = page.nextLink
                            logger.d(TAG, "page done, following nextLink")
                        }

                        page.deltaLink != null -> {
                            // Batch complete — persist the deltaLink as the cursor for next sync.
                            syncCursorStore.setCursor(CURSOR_KEY, page.deltaLink)
                            logger.d(TAG, "sync complete deltaLink stored totalFetched=$totalFetched")
                            break
                        }

                        else -> {
                            // Graph returned neither link — should not happen; treat as done.
                            logger.w(TAG, "no nextLink or deltaLink in response — treating as sync complete")
                            break
                        }
                    }
                }
            }
        }

        sourceStatusRepository.recordSyncSuccess(SourceType.OUTLOOK_MAIL, now)
        logger.d(TAG, "doWork complete totalFetched=$totalFetched")
        return@withContext Result.success()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts a [GraphMessage] into a [RawIngestionEventEntity] ready for Room insertion.
     *
     * The `clientEventId` is a deterministic `"outlook:<id>"` surrogate that ensures
     * idempotency: re-ingesting the same Graph message ID results in a dedup hit in
     * [RawIngestionRepository.insertLocalBatch] rather than a duplicate row.
     */
    private fun GraphMessage.toEntity(userId: String, now: Instant): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "outlook:$id",
            sourceType = SourceType.OUTLOOK_MAIL,
            sourceRef = id,
            personRef = fromEmail,   // canonical counterparty reference (email address)
            eventTitle = subject,
            eventSnippet = bodyPreview?.take(200),
            timestamp = receivedDateTime,
        )

    /**
     * Maps a [com.becalm.android.core.result.BecalmError] from the Graph call to the
     * appropriate [Result] for WorkManager.
     *
     * HTTP 410 clears the cursor so the next attempt performs a full re-sync.
     */
    private suspend fun mapErrorToResult(
        error: com.becalm.android.core.result.BecalmError,
        currentCursor: String?,
    ): Result = when (error) {
        is com.becalm.android.core.result.BecalmError.Unauthorized -> {
            logger.w(TAG, "401 Unauthorized — hard failure, re-auth required")
            Result.failure()
        }

        is com.becalm.android.core.result.BecalmError.NotFound -> {
            // HTTP 410: delta token expired. Clear the cursor so next run is a full sync.
            logger.w(TAG, "410 delta token expired — clearing cursor for full re-sync")
            syncCursorStore.clearCursor(CURSOR_KEY)
            Result.retry()
        }

        is com.becalm.android.core.result.BecalmError.RateLimited -> {
            logger.w(TAG, "429 rate limited retryAfter=${error.retryAfterSeconds}s")
            Result.retry()
        }

        else -> {
            logger.w(TAG, "transient error — retrying cursor=${currentCursor?.take(40)}")
            Result.retry()
        }
    }

    public companion object {
        private const val TAG = "OutlookMailWorker"

        /** Maximum number of WorkManager retry attempts before failing permanently. */
        public const val MAX_RETRIES: Int = 5

        /**
         * Key used with [SyncCursorStore.observeCursor] / [SyncCursorStore.setCursor] to
         * store the MS Graph messages delta token (opaque full URL).
         */
        public const val CURSOR_KEY: String = "outlook_mail_delta"
    }
}
