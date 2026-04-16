package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.msgraph.CalendarViewDeltaPage
import com.becalm.android.data.remote.msgraph.GraphCalendarEvent
import com.becalm.android.data.remote.msgraph.MsGraphClient
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Periodic [CoroutineWorker] that syncs Outlook / Office 365 calendar events via the
 * Microsoft Graph `/me/calendarView/delta` endpoint (spec ING-007b / SP-27b).
 *
 * ## Sync protocol (delta tokens)
 * MS Graph delta endpoints return an opaque full URL in either `@odata.nextLink` (more pages
 * in this batch) or `@odata.deltaLink` (end of batch; cursor for next sync). This worker:
 * 1. Reads the persisted cursor from [SyncCursorStore] using key [CURSOR_KEY].
 * 2. Calls [MsGraphClient.calendarViewDelta] with the cursor (or `null` on first run).
 * 3. Persists a `nextLink` mid-batch so a crash can resume from the last consumed page.
 * 4. Upserts each page's events into Room via [CalendarEventRepository] using
 *    [CalendarEventEntity] mapped from [GraphCalendarEvent].
 * 5. When a `deltaLink` is received, persists it as the cursor and exits the loop.
 *
 * ## Crash resilience
 * After each successful page insert the `nextLink` is stored as the cursor. On the next
 * WorkManager retry the worker resumes from the last successfully consumed page rather than
 * re-fetching from scratch or from the original `deltaLink`.
 *
 * ## PII
 * Event subject, attendee list, and location are never logged.
 * Only the Graph event `id` (8-char hex hash) and page / total counts appear in logcat.
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
 * SP-32 WorkScheduler registers this class as a periodic job (ING-007b cadence).
 */
@HiltWorker
public class OutlookCalendarWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val msGraphClient: MsGraphClient,
    private val calendarEventRepository: CalendarEventRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val syncCursorStore: SyncCursorStore,
    private val authRepository: AuthRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = Clock.System.now()
        logger.d(TAG, "doWork started runAttemptCount=$runAttemptCount")

        // Every ingested row must be tagged with the authenticated user's UUID so that
        // CalendarEventEntity queries can be scoped to the correct user.
        // Fail closed if no session — no anonymous calendar data must be stored.
        val userId = authRepository.currentSession()?.userId
        if (userId.isNullOrBlank()) {
            logger.e(TAG, "doWork no authenticated session — refusing to ingest")
            sourceStatusRepository.recordSyncError(
                SourceType.OUTLOOK_CALENDAR,
                "unauthorized",
                now,
            )
            return@withContext Result.failure()
        }

        sourceStatusRepository.recordSyncStart(SourceType.OUTLOOK_CALENDAR)

        var cursor = syncCursorStore.observeCursor(CURSOR_KEY).first()
        var totalFetched = 0

        // ── Pagination loop ───────────────────────────────────────────────────
        while (true) {
            val result = msGraphClient.calendarViewDelta(cursor)

            when (result) {
                is BecalmResult.Failure -> {
                    val error = result.error
                    logger.w(TAG, "calendarViewDelta failed error=${error::class.simpleName}")
                    sourceStatusRepository.recordSyncError(
                        SourceType.OUTLOOK_CALENDAR,
                        error::class.simpleName ?: "unknown",
                        now,
                    )
                    return@withContext mapErrorToResult(error, cursor)
                }

                is BecalmResult.Success -> {
                    val page: CalendarViewDeltaPage = result.value
                    val events = page.value

                    // Upsert this page into Room before updating the cursor.
                    if (events.isNotEmpty()) {
                        val entities = events.map { it.toEntity(userId) }
                        val insertResult = calendarEventRepository.insertLocalBatch(entities)
                        if (insertResult is BecalmResult.Failure) {
                            logger.e(TAG, "insertLocalBatch failed — retrying")
                            return@withContext Result.retry()
                        }
                        totalFetched += events.size
                        logger.d(TAG, "page inserted count=${events.size} total=$totalFetched")
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

        sourceStatusRepository.recordSyncSuccess(SourceType.OUTLOOK_CALENDAR, now)
        logger.d(TAG, "doWork complete totalFetched=$totalFetched")
        return@withContext Result.success()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Converts a [GraphCalendarEvent] into a [CalendarEventEntity] ready for Room insertion.
     *
     * The `id` is a deterministic UUIDv3 derived from `"outlookcal:<userId>:<graphEventId>"`.
     * Deterministic PKs mean a re-sync replaces existing rows via the REPLACE conflict strategy
     * on the primary key, preventing duplicate accumulation. The `sourceRef` is the raw Graph
     * event `id`, which is also used for upsert dedup via the (user_id, source_type, source_ref)
     * unique index.
     *
     * PII note: `subject` (event title) and `attendeesRaw` are stored locally and are
     * considered PII. They are never logged; only the 8-char hash prefix of the Graph id
     * appears in logcat.
     */
    private fun GraphCalendarEvent.toEntity(userId: String): CalendarEventEntity =
        CalendarEventEntity(
            id = UUID.nameUUIDFromBytes("outlookcal:$userId:$id".toByteArray(Charsets.UTF_8)).toString(),
            userId = userId,
            sourceType = SourceType.OUTLOOK_CALENDAR,
            sourceRef = id,
            title = subject ?: "",
            startAt = start,
            endAt = end,
            attendeesRaw = attendeesRaw,
            syncStatus = "pending",
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
            logger.w(TAG, "transient error — retrying cursor_present=${currentCursor != null}")
            Result.retry()
        }
    }

    public companion object {
        private const val TAG = "OutlookCalendarWorker"

        /**
         * Key used with [SyncCursorStore.observeCursor] / [SyncCursorStore.setCursor] to
         * store the MS Graph calendarView delta token (opaque full URL).
         */
        public const val CURSOR_KEY: String = "outlook_calendar_delta"
    }
}
