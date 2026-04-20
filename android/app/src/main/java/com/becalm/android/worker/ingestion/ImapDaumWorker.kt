package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.becalm.android.core.di.UserPrefs
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.datastore.ImapCursorState
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapFetchResult
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.hasExceededMaxRetries
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Periodic [CoroutineWorker] that syncs Daum Mail via IMAPS and stores new messages
 * as [RawIngestionEventEntity] rows in Room.
 *
 * ## ING-008 / ING-012 / ING-013 — Daum IMAP sync
 * Mirrors [ImapNaverWorker] byte-for-byte — only the host, port, mailbox identifier,
 * and `source_type` differ. The same app-password auth path (CRIT-01) is used; Daum
 * does not expose a public OAuth flow for IMAP access, so the credential shape is
 * identical to Naver's. The credential store is provisioned by the onboarding flow
 * (SP-53); the shared [ImapCredentialStore] file holds exactly one IMAP credential
 * tuple at a time (`host` column distinguishes provider) — callers must re-save the
 * correct credential before enqueuing this worker.
 *
 * Connects to `imap.daum.net:993` using the app-password credential retrieved from
 * [ImapCredentialStore.getCredentials]. Incremental sync is driven by the
 * UIDVALIDITY + UIDNEXT cursor pair persisted in [SyncCursorStore] under mailbox
 * [MAILBOX_DAUM]. A UIDVALIDITY mismatch causes a full resync (handled inside
 * [ImapClient.fetchSince]: it fetches from UID 1 and caps the result at
 * [MAX_MESSAGES_PER_RUN]). The worker persists the server's fresh UIDVALIDITY +
 * UIDNEXT after the write completes — no explicit [SyncCursorStore.clearCursor]
 * call is required because the follow-up [SyncCursorStore.setImapState] write
 * supersedes the stale pair atomically.
 *
 * ## Cursor persistence
 * Cursors are advanced **after** a durable Room write to guarantee at-least-once
 * delivery. If the worker is killed between the Room insert and the cursor advance,
 * the same messages may be re-fetched on the next run. The deterministic
 * `clientEventId` format (`"daum:<uid>:<uidValidity>"`) combined with the
 * `(user_id, client_event_id)` UNIQUE constraint at the Railway/Supabase tier
 * (ING-015) ensures idempotent deduplication.
 *
 * ## PII policy
 * Email addresses, subjects, and body text are never written to logcat.
 * Only message counts and 8-char hex hashes derived from client event IDs appear
 * in logs (matches [ImapNaverWorker]).
 *
 * ## Result mapping
 * | Outcome                  | WorkManager result        |
 * |--------------------------|---------------------------|
 * | Fetch + insert OK        | [Result.success]          |
 * | Network / transient error| [Result.retry] (backoff)  |
 * | Missing credentials      | [Result.failure]          |
 * | Auth error (401)         | [Result.failure]          |
 *
 * ## Scheduled by
 * SP-32 WorkScheduler registers this class as a periodic job (15-minute interval)
 * once the Round 6A.5 wire-up lands.
 */
@HiltWorker
public class ImapDaumWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @UserPrefs private val userPrefs: DataStore<Preferences>,
    private val imapCredentialStore: ImapCredentialStore,
    private val syncCursorStore: SyncCursorStore,
    private val imapClient: ImapClient,
    private val rawIngestionRepository: RawIngestionRepository,
    private val sourceStatusRepository: SourceStatusRepository,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    public override suspend fun doWork(): Result {
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return Result.failure()

        // ── 1. Read credentials from EncryptedSharedPreferences (CRIT-01) ────
        val credentials = loadCredentials() ?: return Result.failure()

        val imapEmail = credentials.username
        val imapPassword = credentials.appPassword

        // userId is stored separately from the IMAP email; it may differ (Supabase UUID).
        val userId = userPrefs.data
            .map { it[stringPreferencesKey(PREF_KEY_CURRENT_USER_ID)] }
            .first() ?: ""

        // ── 2. Read cursors ───────────────────────────────────────────────────
        val storedCursor: ImapCursorState? =
            syncCursorStore.observeImapState(MAILBOX_DAUM).first()

        val storedUidValidity: Long? = storedCursor?.uidValidity
        // SyncCursorStore.lastSeenUid is the highest UID successfully ingested.
        // Add 1 so the IMAP fetch starts at the first *unseen* UID.
        val fetchFromUid: Long? = storedCursor?.lastSeenUid?.let { it + 1L }

        logger.d(
            TAG,
            "starting sync uidValidity=$storedUidValidity fetchFromUid=$fetchFromUid",
        )

        // ── 3. Fetch from IMAP ────────────────────────────────────────────────
        val fetchResult = imapClient.fetchSince(
            host = DAUM_IMAP_HOST,
            port = DAUM_IMAP_PORT,
            user = imapEmail,
            password = imapPassword,
            uidValidity = storedUidValidity,
            uidNext = fetchFromUid,
            maxMessages = MAX_MESSAGES_PER_RUN,
        )

        if (fetchResult is BecalmResult.Failure) {
            return handleFetchFailure(fetchResult.error)
        }

        val fetched = (fetchResult as BecalmResult.Success).value
        return persistFetchedAndSucceed(fetched, userId)
    }

    /**
     * Reads IMAP credentials from [ImapCredentialStore]. Logs the provisioning hint when
     * absent and returns `null` so the caller can short-circuit to [Result.failure].
     */
    private suspend fun loadCredentials(): ImapCredentials? {
        val credentials = imapCredentialStore.getCredentials()
        if (credentials == null) {
            logger.w(TAG, "IMAP Daum credentials absent — provisioned by SP-53")
        }
        return credentials
    }

    /**
     * Logs + records the IMAP fetch error and maps it to the appropriate WorkManager [Result].
     * Auth failures terminate the worker; any other error retries with backoff.
     */
    private suspend fun handleFetchFailure(error: BecalmError): Result {
        logger.e(TAG, "IMAP fetch failed error=${error::class.simpleName}")
        sourceStatusRepository.recordSyncError(
            sourceType = SourceType.DAUM_IMAP,
            error = error::class.simpleName ?: "unknown",
            at = Clock.System.now(),
        )
        return when (error) {
            is BecalmError.Unauthorized -> Result.failure()
            else -> Result.retry()
        }
    }

    /**
     * Handles phases 4-7 of the sync: insert fetched messages, advance the IMAP cursor,
     * record success in [SourceStatusRepository], and emit the closing log before returning.
     *
     * Log strings are byte-identical with the corresponding inlined sequence in
     * [ImapNaverWorker.persistFetchedAndSucceed] — only the mailbox changes.
     */
    private suspend fun persistFetchedAndSucceed(
        fetched: ImapFetchResult,
        userId: String,
    ): Result {
        val serverUidValidity = fetched.newUidValidity
        val serverUidNext = fetched.newUidNext

        logger.d(
            TAG,
            "fetched count=${fetched.messages.size} " +
                "serverUidValidity=$serverUidValidity serverUidNext=$serverUidNext",
        )

        // ── 4. Convert + insert ───────────────────────────────────────────────
        if (fetched.messages.isNotEmpty()) {
            val entities = fetched.messages.map { msg -> msg.toEntity(userId) }

            val insertResult = rawIngestionRepository.insertLocalBatch(entities)
            if (insertResult is BecalmResult.Failure) {
                logger.e(
                    TAG,
                    "insertLocalBatch failed error=${insertResult.error::class.simpleName}",
                )
                return Result.retry()
            }

            logger.d(
                TAG,
                "inserted count=${entities.size} " +
                    "uidHashes=${entities.map { redact(it.clientEventId) }}",
            )
        }

        // ── 5. Advance cursors ────────────────────────────────────────────────
        // Persisted after durable write. lastSeenUid = serverUidNext - 1 so the next call
        // fetches from serverUidNext (the first UID the server has not yet sent us).
        val newLastSeenUid = maxOf(serverUidNext - 1L, 0L)
        syncCursorStore.setImapState(
            mailbox = MAILBOX_DAUM,
            state = ImapCursorState(
                uidValidity = serverUidValidity,
                lastSeenUid = newLastSeenUid,
            ),
        )

        logger.d(
            TAG,
            "cursor advanced uidValidity=$serverUidValidity lastSeenUid=$newLastSeenUid",
        )

        // ── 6. Record success ─────────────────────────────────────────────────
        sourceStatusRepository.recordSyncSuccess(
            sourceType = SourceType.DAUM_IMAP,
            at = Clock.System.now(),
        )

        // ── 7. Done ───────────────────────────────────────────────────────────
        logger.d(TAG, "doWork complete fetched=${fetched.messages.size}")
        return Result.success()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Maps an [ImapMessage] to a [RawIngestionEventEntity] ready for Room insertion.
     *
     * `clientEventId` is deterministic (`"daum:<uid>:<uidValidity>"`) so that
     * at-least-once re-delivery of the same message is idempotent at the Railway/Supabase
     * UNIQUE (user_id, client_event_id) constraint (ING-015).
     *
     * PII note: [ImapMessage.subject] and [ImapMessage.bodyPreview] are stored in Room
     * but never written to logcat.
     */
    private fun ImapMessage.toEntity(userId: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "daum:$uid:$uidValidity",
            sourceType = SourceType.DAUM_IMAP,
            sourceRef = messageId,
            personRef = fromEmail,
            eventTitle = subject,
            eventSnippet = bodyPreview,
            timestamp = sentAt,
        )

    // ─── Constants ────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "ImapDaumWorker"

        /** Maximum number of WorkManager retry attempts before failing permanently. */
        public const val MAX_RETRIES: Int = 5

        /** IMAPS server hostname for Daum Mail. */
        public const val DAUM_IMAP_HOST: String = "imap.daum.net"

        /** IMAPS port for Daum Mail (SSL on port 993). */
        public const val DAUM_IMAP_PORT: Int = 993

        /**
         * Mailbox identifier passed to [SyncCursorStore.observeImapState] / [SyncCursorStore.setImapState].
         * The underlying DataStore keys are `imap_daum_uidvalidity` and `imap_daum_uid`.
         */
        public const val MAILBOX_DAUM: String = "daum"

        /**
         * Upper bound on messages fetched per worker run.
         * Limits memory pressure on large initial syncs; excess messages are picked up in
         * subsequent runs via cursor advancement. Also serves as the bound on the full resync
         * triggered by UIDVALIDITY change (ING-013): [ImapClient.fetchSince] caps the result
         * at this value when `uidValidity` disagrees with the server.
         */
        public const val MAX_MESSAGES_PER_RUN: Int = 100

        /**
         * `@UserPrefs` DataStore key for the current user's Supabase UUID.
         * Mirrors the key used in [com.becalm.android.data.local.datastore.UserPrefsStoreImpl].
         * IMAP credentials (username + app-password) are stored in [ImapCredentialStore] (CRIT-01).
         */
        private const val PREF_KEY_CURRENT_USER_ID: String = "current_user_id"
    }
}
