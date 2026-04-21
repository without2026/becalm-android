package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.CalendarEventRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Periodic [CoroutineWorker] that triggers a server-side Google Calendar sync and then
 * pulls the canonicalised rows into the local Room cache.
 *
 * ## ING-007 — Google Calendar incremental sync
 *
 * ### Production flow (Railway-mediated)
 * Android does not call the Google Calendar API directly. The flow is:
 * 1. [AuthRepository.currentSession] — verify a valid session exists; fail fast if absent.
 * 2. [CalendarEventRepository.triggerServerSync] — POST to Railway, which reads the user's
 *    server-stored OAuth token, calls `events.list` (with stored `syncToken` for incremental
 *    sync), and upserts the results into Supabase.
 * 3. [CalendarEventRepository.refreshSince] — pull the canonicalised rows from Railway into
 *    Room, resuming from the stored cursor (`since = null`).
 * 4. [SourceStatusRepository.recordSyncSuccess] / [SourceStatusRepository.recordSyncError] —
 *    persist sync health metadata observed by the dashboard UI.
 *
 * ### Retry policy
 * - No session → [Result.failure]: no point retrying until the user re-authenticates.
 * - Rate-limited (HTTP 429) → [Result.retry]: WorkManager exponential back-off will honour
 *   the `Retry-After` hint via its own retry interval.
 * - Unauthorized (HTTP 401) → [Result.failure]: session is invalid; worker cannot proceed.
 * - All other errors → [Result.failure]: logged and reported to [SourceStatusRepository].
 *
 * ### Scheduled by
 * SP-32 WorkScheduler registers this class as a periodic job (interval TBD per product spec).
 */
@HiltWorker
public class GoogleCalendarWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val calendarEventRepository: CalendarEventRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val clock: Clock,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result {
        logger.d(TAG, "doWork started runAttempt=${runAttemptCount}")

        // ── Step 1: resolve userId ────────────────────────────────────────────
        val userId = authRepository.currentSession()?.userId
        if (userId == null) {
            logger.w(TAG, "no active session — cannot sync calendar; failing without retry")
            return Result.failure()
        }

        val startedAt = clock.nowInstant()
        sourceStatusRepository.recordSyncStart(SourceType.GOOGLE_CALENDAR)

        // ── Step 2: trigger server-side calendar sync ─────────────────────────
        when (val syncResult = calendarEventRepository.triggerServerSync()) {
            is BecalmResult.Success -> {
                logger.d(TAG, "triggerServerSync succeeded synced=${syncResult.value.synced}")
            }
            is BecalmResult.Failure -> {
                val error = syncResult.error
                return when (error) {
                    is BecalmError.RateLimited -> {
                        logger.w(
                            TAG,
                            "triggerServerSync rate-limited retryAfter=${error.retryAfterSeconds}s — scheduling retry",
                        )
                        // Do not record an error: the source wasn't broken, just throttled.
                        Result.retry()
                    }
                    is BecalmError.Unauthorized -> {
                        logger.w(TAG, "triggerServerSync unauthorized — session invalid; failing")
                        sourceStatusRepository.recordSyncError(
                            SourceType.GOOGLE_CALENDAR,
                            "Unauthorized: session invalid",
                            clock.nowInstant(),
                        )
                        Result.failure()
                    }
                    is BecalmError.Network, is BecalmError.ServerError -> {
                        val msg = error.toMessage()
                        logger.w(TAG, "triggerServerSync transient error: $msg — scheduling retry")
                        // Transient network / 5xx failure — retry with exponential back-off.
                        Result.retry()
                    }
                    else -> {
                        val msg = error.toMessage()
                        logger.e(TAG, "triggerServerSync failed: $msg")
                        sourceStatusRepository.recordSyncError(
                            SourceType.GOOGLE_CALENDAR,
                            msg,
                            clock.nowInstant(),
                        )
                        Result.failure()
                    }
                }
            }
        }

        // ── Step 3: pull canonicalised rows into Room ─────────────────────────
        // `since = null` → resume from the persisted cursor stored by refreshSince itself.
        val refreshStats = when (val refreshResult = calendarEventRepository.refreshSince(userId, since = null)) {
            is BecalmResult.Success -> refreshResult.value
            is BecalmResult.Failure -> {
                val error = refreshResult.error
                val msg = error.toMessage()
                return when (error) {
                    is BecalmError.Network, is BecalmError.ServerError -> {
                        logger.w(TAG, "refreshSince transient error: $msg — scheduling retry")
                        Result.retry()
                    }
                    else -> {
                        logger.e(TAG, "refreshSince failed: $msg")
                        sourceStatusRepository.recordSyncError(
                            SourceType.GOOGLE_CALENDAR,
                            msg,
                            clock.nowInstant(),
                        )
                        Result.failure()
                    }
                }
            }
        }

        logger.d(
            TAG,
            "refreshSince complete fetched=${refreshStats.fetched} upserted=${refreshStats.upserted} hasMore=${refreshStats.hasMore}",
        )

        // ── Step 4: record success ────────────────────────────────────────────
        sourceStatusRepository.recordSyncSuccess(SourceType.GOOGLE_CALENDAR, clock.nowInstant())

        logger.d(
            TAG,
            "doWork success upserted=${refreshStats.upserted} elapsedMs=${clock.nowInstant().toEpochMilliseconds() - startedAt.toEpochMilliseconds()}",
        )
        return Result.success()
    }

    public companion object {
        private const val TAG = "GoogleCalendarWorker"
    }
}

// ─── Private helpers ──────────────────────────────────────────────────────────

/**
 * Returns a concise human-readable description of this [BecalmError] for use in
 * [SourceStatusRepository.recordSyncError] and log messages.
 *
 * Intentionally kept inside this file — it is not a general-purpose extension; other
 * callers that need error descriptions should define their own mapping.
 */
private fun BecalmError.toMessage(): String = when (this) {
    is BecalmError.Network -> "Network error HTTP $code: $message"
    is BecalmError.Unauthorized -> "Unauthorized"
    is BecalmError.RateLimited -> "Rate limited (retryAfter=${retryAfterSeconds}s)"
    is BecalmError.ServerError -> "Server error HTTP $code"
    is BecalmError.Validation -> "Validation error field=$field: $message"
    is BecalmError.Io -> "IO error: $message"
    is BecalmError.Permission -> "Permission denied: $permission"
    is BecalmError.NotFound -> "Not found: $resource"
    is BecalmError.Cancelled -> "Cancelled"
    // Calendar workers never invoke the on-device Gemini Nano extractor that produces this
    // error — EMAIL-001 / EMAIL-008 confines ExtractorUnavailable to email source types.
    // The branch exists for sealed-class exhaustiveness only; the log string is diagnostic.
    is BecalmError.ExtractorUnavailable -> "Extractor unavailable: reason=$reason"
    is BecalmError.Unknown -> "Unknown: ${throwable.message}"
}
