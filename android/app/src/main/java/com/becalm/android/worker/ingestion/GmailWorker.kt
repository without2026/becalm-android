package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.gmail.GmailClient
import com.becalm.android.data.remote.gmail.GmailMessage
import com.becalm.android.data.remote.gmail.GmailMessagePage
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID

/**
 * WorkManager [CoroutineWorker] that incrementally syncs Gmail messages and stores
 * each one as a [RawIngestionEventEntity] (sourceType = "gmail").
 *
 * ## Sync strategy (ING-006, ING-012, ING-013)
 *
 * **Incremental** — when [SyncCursorStore.observeGmailHistoryId] returns a non-null value,
 * [GmailClient.listHistory] is called with that historyId as the starting point. Each page
 * of newly-added message IDs is fetched and converted. The cursor is advanced per page so
 * that a mid-sync crash resumes from the last durably written page rather than re-fetching
 * from scratch.
 *
 * **Full-sync fallback** — triggered on first run (null cursor) or when [GmailClient.listHistory]
 * returns [BecalmError.NotFound] (HTTP 404/410 = historyId expired). The cursor is cleared
 * before the full-sync so that a crash during full-sync re-enters this branch on the next run.
 *
 * **Deduplication** — Room's UNIQUE index on `(user_id, client_event_id)` is the source of
 * truth. If a message was already ingested the `insertLocal` read-before-write guard returns
 * the existing ID without a duplicate write (ING-015 idempotency).
 *
 * ## PII / logging
 * Subject, snippet, and email addresses are NEVER written to logcat. Only message counts,
 * hashed `sourceRef` surrogates, and cursor values are logged.
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
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val cursorStore: SyncCursorStore,
    private val authRepository: AuthRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        logger.d(TAG, "doWork start")

        // Every ingested row must be tagged with the authenticated user's UUID. Without a
        // session the row would become invisible to UploadWorker (its WHERE user_id=? query
        // would never match) and silently accumulate on-device. Fail closed.
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
            is SyncOutcome.Success -> {
                sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, Clock.System.now())
                logger.d(TAG, "doWork complete fetched=${syncResult.fetchedCount}")
                Result.success()
            }
            is SyncOutcome.HistoryExpired -> {
                // historyId expired (404/410) — clear cursor then full-sync
                logger.d(TAG, "doWork historyId expired, falling back to full-sync")
                cursorStore.setGmailHistoryId(null)
                val fallbackOutcome = runFullSync(userId)
                when (fallbackOutcome) {
                    is SyncOutcome.Success -> {
                        sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, Clock.System.now())
                        logger.d(TAG, "doWork fallback-full-sync complete fetched=${fallbackOutcome.fetchedCount}")
                        Result.success()
                    }
                    is SyncOutcome.Unauthorized -> {
                        sourceStatusRepository.recordSyncError(
                            SourceType.GMAIL,
                            "unauthorized",
                            Clock.System.now(),
                        )
                        logger.e(TAG, "doWork fallback-full-sync unauthorized — user must re-auth")
                        Result.failure()
                    }
                    is SyncOutcome.Retryable -> {
                        sourceStatusRepository.recordSyncError(
                            SourceType.GMAIL,
                            fallbackOutcome.reason,
                            Clock.System.now(),
                        )
                        logger.w(TAG, "doWork fallback-full-sync retryable reason=${fallbackOutcome.reason}")
                        Result.retry()
                    }
                    is SyncOutcome.HistoryExpired -> {
                        // Cannot happen during a full-sync; guard against it anyway.
                        Result.retry()
                    }
                }
            }
            is SyncOutcome.Unauthorized -> {
                sourceStatusRepository.recordSyncError(
                    SourceType.GMAIL,
                    "unauthorized",
                    Clock.System.now(),
                )
                logger.e(TAG, "doWork unauthorized — user must re-auth")
                Result.failure()
            }
            is SyncOutcome.Retryable -> {
                sourceStatusRepository.recordSyncError(
                    SourceType.GMAIL,
                    syncResult.reason,
                    Clock.System.now(),
                )
                logger.w(TAG, "doWork retryable reason=${syncResult.reason}")
                Result.retry()
            }
        }
    }

    // ── Incremental sync ──────────────────────────────────────────────────────

    /**
     * Fetches all pages of history since [startHistoryId], inserting each new message
     * into Room and advancing the cursor per page.
     *
     * Gmail's `history.list` paginates via `nextPageToken`. Because the public
     * [GmailClient.listHistory] interface only accepts `startHistoryId`, pagination is
     * handled here by stopping after the first page and relying on the per-page cursor
     * advance: the next WorkManager run resumes from the updated `historyId`, which
     * naturally returns the next slice. This keeps the [GmailClient] interface minimal.
     *
     * Returns [SyncOutcome.HistoryExpired] on HTTP 404/410 so the caller can
     * discard the stale cursor and fall back to a full-sync.
     */
    private suspend fun runIncrementalSync(userId: String, startHistoryId: String): SyncOutcome {
        val result = gmailClient.listHistory(startHistoryId)
        return when (result) {
            is BecalmResult.Failure -> result.error.toSyncOutcome()
            is BecalmResult.Success -> {
                val page = result.value
                val insertedCount = insertMessages(userId, page.messageIds)

                // Advance cursor per-page after durable Room write (ING-012 pattern).
                // If there are more pages, the updated historyId will be used on the
                // next WorkManager run to fetch the subsequent slice.
                val newHistoryId = page.historyId?.toLongOrNull()
                if (newHistoryId != null) {
                    cursorStore.setGmailHistoryId(newHistoryId)
                    logger.d(TAG, "incremental cursor advanced to=$newHistoryId page_msgs=$insertedCount")
                }

                SyncOutcome.Success(insertedCount)
            }
        }
    }

    // ── Full sync ─────────────────────────────────────────────────────────────

    /**
     * Fetches all inbox message IDs via [GmailClient.listMessagesFullSync], paginating
     * until there is no next page token. The historyId cursor is not available from
     * `messages.list`; it will be established on the first subsequent incremental sync
     * run after the user triggers a `history.list` response that includes a `historyId`.
     *
     * To obtain an initial historyId after a full sync, [GmailClient.listHistory] is called
     * with startHistoryId="1" — this is the conventional way to get the current historyId
     * without needing to know it in advance.
     */
    private suspend fun runFullSync(userId: String): SyncOutcome {
        var pageToken: String? = null
        var totalFetched = 0

        while (true) {
            val result: BecalmResult<GmailMessagePage> = gmailClient.listMessagesFullSync(pageToken)
            when (result) {
                is BecalmResult.Failure -> return result.error.toSyncOutcome()
                is BecalmResult.Success -> {
                    val page = result.value
                    val insertedCount = insertMessages(userId, page.messageIds)
                    totalFetched += insertedCount
                    logger.d(TAG, "full-sync page inserted=$insertedCount total=$totalFetched")

                    pageToken = page.nextPageToken ?: break
                }
            }
        }

        // Seed the historyId cursor by calling history.list with the earliest valid value.
        // Gmail requires a real historyId; we use "1" as the conventional bootstrap value
        // to retrieve the current mailbox historyId without processing history records.
        val historyBootstrap = gmailClient.listHistory("1")
        if (historyBootstrap is BecalmResult.Success) {
            val bootstrapHistoryId = historyBootstrap.value.historyId?.toLongOrNull()
            if (bootstrapHistoryId != null) {
                cursorStore.setGmailHistoryId(bootstrapHistoryId)
                logger.d(TAG, "full-sync seeded historyId=$bootstrapHistoryId")
            }
        }
        // A failure to seed the historyId is non-fatal: the next run will full-sync again.

        return SyncOutcome.Success(totalFetched)
    }

    // ── Message insertion ─────────────────────────────────────────────────────

    /**
     * Fetches each message in [messageIds], converts it to a [RawIngestionEventEntity],
     * and calls [RawIngestionRepository.insertLocal]. Failures on individual messages
     * are logged and skipped — one bad message must not abort the whole page.
     *
     * @return Number of messages successfully inserted (or deduped-skipped) in this batch.
     */
    private suspend fun insertMessages(userId: String, messageIds: List<String>): Int {
        var count = 0
        for (messageId in messageIds) {
            when (val msgResult = gmailClient.getMessage(messageId)) {
                is BecalmResult.Failure -> {
                    logger.w(TAG, "getMessage failed id_hash=${redact(messageId)} err=${msgResult.error}")
                }
                is BecalmResult.Success -> {
                    val entity = msgResult.value.toEntity(userId)
                    when (val insertResult = rawIngestionRepository.insertLocal(entity)) {
                        is BecalmResult.Failure -> {
                            logger.e(TAG, "insertLocal failed id_hash=${redact(messageId)} err=${insertResult.error}")
                        }
                        is BecalmResult.Success -> {
                            logger.d(TAG, "insertLocal ok id_hash=${redact(messageId)}")
                            count++
                        }
                    }
                }
            }
        }
        return count
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    /**
     * Maps a [GmailMessage] to a [RawIngestionEventEntity].
     *
     * Mapping rules:
     * - `userId`        — authenticated session userId threaded from [doWork]
     * - `sourceType`    — "gmail"
     * - `sourceRef`     — messageId
     * - `eventTitle`    — subject (null when absent)
     * - `eventSnippet`  — snippet (null when absent)
     * - `personRef`     — [canonicalizeEmail] applied to the `From` header value
     * - `timestamp`     — [Instant.fromEpochMilliseconds](internalDate)
     * - `id`            — fresh UUID v4 (Room primary key)
     * - `clientEventId` — deterministic `"gmail:$messageId"` so a full-sync retry after a
     *                     mid-run crash dedupes against the DB UNIQUE index on
     *                     `(user_id, client_event_id)` instead of inserting a duplicate row
     *                     under a new random UUID.
     */
    private fun GmailMessage.toEntity(userId: String): RawIngestionEventEntity {
        return RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "gmail:$messageId",
            sourceType = SourceType.GMAIL,
            sourceRef = messageId,
            eventTitle = subject,
            eventSnippet = snippet,
            personRef = from?.let { canonicalizeEmail(it) },
            timestamp = Instant.fromEpochMilliseconds(internalDate),
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts and canonicalizes the email address from an RFC 5322 `From` header value.
     *
     * Handles two common forms:
     * - `Display Name <address@example.com>` — extracts the part inside `< >`.
     * - `address@example.com` — uses the raw value after trimming whitespace.
     *
     * Normalizes by lowercasing the entire address. Uses [indexOf] rather than a regex
     * to eliminate any backtracking risk.
     *
     * @param fromHeader Raw `From` header value; may include display name.
     * @return Lowercased canonical email address, or null when the input is blank.
     */
    internal fun canonicalizeEmail(fromHeader: String): String? {
        if (fromHeader.isBlank()) return null
        val angleBracketStart = fromHeader.indexOf('<')
        val angleBracketEnd = fromHeader.indexOf('>')
        val raw = if (angleBracketStart >= 0 && angleBracketEnd > angleBracketStart) {
            fromHeader.substring(angleBracketStart + 1, angleBracketEnd).trim()
        } else {
            fromHeader.trim()
        }
        return raw.lowercase().ifBlank { null }
    }

    /**
     * Returns an 8-char hex surrogate for [value] to prevent PII from appearing in logcat.
     * Mirrors the pattern used in [MediaStoreWorker].
     */
    private fun redact(value: String): String = "%08x".format(value.hashCode())

    public companion object {
        private const val TAG = "GmailWorker"
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
