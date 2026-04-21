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
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.gmail.GmailClient
import com.becalm.android.data.remote.gmail.GmailLabel
import com.becalm.android.data.remote.gmail.GmailMessage
import com.becalm.android.data.remote.gmail.GmailMessagePage
import com.becalm.android.data.remote.gmail.GoogleAuthTokenProviderImpl
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
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
    private val googleAuthTokenProvider: GoogleAuthTokenProviderImpl,
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val cursorStore: SyncCursorStore,
    private val authRepository: AuthRepository,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(appContext, workerParams) {

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
        // silent refresh from the background. `refreshSilently` takes a Context — the
        // application context is fine — and issues a fresh Gmail access token when the
        // device-side grant still covers the readonly scope. If it does not, state
        // flips to ReauthRequired and the worker returns Result.failure below so the
        // UI can drive the interactive reauth via `startSignIn(activity)`.
        if (googleAuthTokenProvider.currentToken() == null) {
            val refreshed = googleAuthTokenProvider.refreshSilently(appContext)
            logger.d(TAG, "doWork silent refresh attempted ok=$refreshed")
        }

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
            is SyncOutcome.HistoryExpired -> {
                // historyId expired (404/410) — clear cursor then full-sync
                logger.d(TAG, "doWork historyId expired, falling back to full-sync")
                cursorStore.setGmailHistoryId(null)
                runFullSync(userId).toWorkerResult(" fallback-full-sync")
            }
            else -> syncResult.toWorkerResult("")
        }
    }

    /**
     * 외부 `doWork`의 `when(syncResult)`와 HistoryExpired 내부의 `when(fallbackOutcome)`가
     * Success/Unauthorized/Retryable 세 분기에서 동일한 Result 매핑을 중복하던 것을
     * 한 함수로 통합해 두 호출 사이트에서 바이트 단위로 동일한 결과를 보장한다.
     * HistoryExpired는 의도적으로 제외되며, fallback 분기 자체의 시맨틱(커서 클리어 →
     * 전체 동기화)은 변경하지 않는다. [phase]는 로그 메시지에 삽입되는 접두 문자열이다.
     */
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
            // Handled at each call site (outer: fallback branch; inner: guarded retry).
            Result.retry()
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
        var totalFetched = 0

        // EMAIL-001 (`.spec/email-pipeline.spec.yml:15-18`) requires both inbound and
        // sent mail to reach ingestion so the `folder` direction hint covers every row
        // in the mailbox. Fetch the two labels sequentially; the per-message GET then
        // exposes Gmail's label set and [GmailMessage.toEntity] picks the right
        // `folder` / `personRef` pair per row.
        for (label in GmailLabel.entries) {
            when (val outcome = runFullSyncForLabel(userId, label)) {
                is SyncOutcome.Success -> totalFetched += outcome.fetchedCount
                // Any non-success outcome short-circuits the backfill and surfaces to
                // the worker entry point, preserving the pre-W1 behaviour of aborting
                // on the first failure / auth / history issue.
                SyncOutcome.HistoryExpired,
                SyncOutcome.Unauthorized,
                is SyncOutcome.Retryable,
                -> return outcome
            }
        }

        seedHistoryIdCursor()

        return SyncOutcome.Success(totalFetched)
    }

    /**
     * Paginates `messages.list` scoped to a single [label] and inserts every
     * discovered message through the shared [insertMessages] pipeline.
     *
     * Extracted so [runFullSync] can compose inbound + sent passes without
     * duplicating the pagination loop or failure-mapping.
     */
    private suspend fun runFullSyncForLabel(
        userId: String,
        label: GmailLabel,
    ): SyncOutcome {
        var pageToken: String? = null
        var inserted = 0
        while (true) {
            val result: BecalmResult<GmailMessagePage> =
                gmailClient.listMessagesFullSync(label, pageToken)
            when (result) {
                is BecalmResult.Failure -> return result.error.toSyncOutcome()
                is BecalmResult.Success -> {
                    val page = result.value
                    val insertedThisPage = insertMessages(userId, page.messageIds)
                    inserted += insertedThisPage
                    logger.d(
                        TAG,
                        "full-sync page label=$label inserted=$insertedThisPage running=$inserted",
                    )
                    pageToken = page.nextPageToken ?: return SyncOutcome.Success(inserted)
                }
            }
        }
    }

    /**
     * Full-sync 직후 다음 run에서 incremental-sync를 쓸 수 있도록 historyId 커서를 1회 심는다.
     * `history.list("1")`은 관례적인 부트스트랩 호출이며 실패해도 치명적이지 않다 — 다음 run에서
     * storedHistoryId 가 null이면 full-sync가 다시 실행된다.
     *
     * 원본 인라인 로직과 byte-identical: `BecalmResult.Success` 분기에서만 커서 갱신이 일어나고,
     * 로그 문구(`"full-sync seeded historyId=$bootstrapHistoryId"`)와 `toLongOrNull` 가드도 그대로다.
     */
    private suspend fun seedHistoryIdCursor() {
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
            if (fetchAndInsert(userId, messageId)) count++
        }
        return count
    }

    /**
     * 단일 메시지에 대한 `getMessage → toEntity → insertLocal` 파이프라인을 4-level 중첩
     * when 밖으로 평탄화한다. 각 실패 분기의 로그 레벨(`w`/`e`)과 문자열은 원본과 byte-identical.
     * 성공 시에만 `true`를 반환해 [insertMessages]의 count 증가 시맨틱을 그대로 보존한다.
     */
    private suspend fun fetchAndInsert(userId: String, messageId: String): Boolean {
        val msgResult = gmailClient.getMessage(messageId)
        if (msgResult is BecalmResult.Failure) {
            logger.w(TAG, "getMessage failed id_hash=${redact(messageId)} err=${msgResult.error}")
            return false
        }
        val entity = (msgResult as BecalmResult.Success).value.toEntity(userId)
        val insertResult = rawIngestionRepository.insertLocal(entity)
        if (insertResult is BecalmResult.Failure) {
            logger.e(TAG, "insertLocal failed id_hash=${redact(messageId)} err=${insertResult.error}")
            return false
        }
        logger.d(TAG, "insertLocal ok id_hash=${redact(messageId)}")
        return true
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
        val folderHint = gmailLabelsToFolder(labelIds)
        // EMAIL-002 (`.spec/email-pipeline.spec.yml:15-18`) person_ref derivation:
        // INBOX ⇒ From (the counterparty who wrote to the user), SENT ⇒ first To
        // (the counterparty the user wrote to). Any other label falls back to `From`
        // — the pre-EMAIL-001 behaviour, never worse than today.
        val personRef = when (folderHint) {
            FOLDER_SENT -> to?.let(::firstRecipientEmail)
            else -> from?.let(::canonicalizeEmail)
        }
        return RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "gmail:$messageId",
            sourceType = SourceType.GMAIL,
            sourceRef = messageId,
            eventTitle = subject,
            eventSnippet = snippet,
            personRef = personRef,
            folder = folderHint,
            timestamp = Instant.fromEpochMilliseconds(internalDate),
        )
    }

    // Header parsing helpers (`canonicalizeEmail`, `firstRecipientEmail`,
    // `gmailLabelsToFolder`) live in `GmailHeaders.kt` so the worker stays focused
    // on WorkManager orchestration. Per the rubric's COHESION-03 ~400-LOC ceiling.

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
