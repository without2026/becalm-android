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
 * Periodic [CoroutineWorker] that syncs Naver Mail via IMAPS and stores new messages
 * as [RawIngestionEventEntity] rows in Room.
 *
 * ## ING-008 — Naver IMAP sync
 * Connects to `imap.naver.com:993` using the app-password credential stored in the
 * `@UserPrefs` DataStore under keys [PREF_KEY_EMAIL] / [PREF_KEY_APP_PASSWORD].
 * These keys are provisioned by the onboarding flow (SP-53).
 *
 * Incremental sync is driven by the UIDVALIDITY + UIDNEXT cursor pair persisted in
 * [SyncCursorStore] under mailbox [MAILBOX_NAVER]. A UIDVALIDITY mismatch causes a full
 * resync (all messages in INBOX up to [MAX_MESSAGES_PER_RUN]) and resets the cursor.
 *
 * ## Cursor persistence
 * Cursors are advanced **after** a durable Room write to guarantee at-least-once delivery.
 * If the worker is killed between the Room insert and the cursor advance, the same messages
 * may be re-fetched on the next run. The UNIQUE index on `(user_id, client_event_id)` in
 * [RawIngestionEventEntity] and the deterministic `clientEventId` format
 * (`"naver:<uid>:<uidValidity>"`) ensure idempotent deduplication at the DB layer.
 *
 * ## PII policy
 * Email addresses, subjects, and body text are never written to logcat.
 * Only message counts and 8-char hex hashes derived from client event IDs appear in logs.
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
 * SP-32 WorkScheduler registers this class as a periodic job (15-minute interval).
 */
@HiltWorker
public class ImapNaverWorker @AssistedInject constructor(
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
            syncCursorStore.observeImapState(MAILBOX_NAVER).first()

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
            host = NAVER_IMAP_HOST,
            port = NAVER_IMAP_PORT,
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
            logger.w(TAG, "IMAP Naver credentials absent — provisioned by SP-53")
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
            sourceType = SourceType.NAVER_IMAP,
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
     * Log strings are byte-identical with the former inlined sequence in [doWork].
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
            mailbox = MAILBOX_NAVER,
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
            sourceType = SourceType.NAVER_IMAP,
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
     * `clientEventId` is deterministic (`"naver:<uid>:<uidValidity>"`) so that at-least-once
     * re-delivery of the same message is idempotent at the DB unique constraint layer.
     *
     * PII note: [ImapMessage.subject] and [ImapMessage.bodyPreview] are stored in Room
     * but never written to logcat.
     */
    private fun ImapMessage.toEntity(userId: String): RawIngestionEventEntity =
        RawIngestionEventEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            clientEventId = "naver:$uid:$uidValidity",
            sourceType = SourceType.NAVER_IMAP,
            sourceRef = messageId,
            personRef = fromEmail,
            eventTitle = subject,
            eventSnippet = bodyPreview,
            timestamp = sentAt,
        )

    // ─── Constants ────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "ImapNaverWorker"

        /** Maximum number of WorkManager retry attempts before failing permanently. */
        public const val MAX_RETRIES: Int = 5

        /** IMAPS server hostname for Naver Mail. */
        public const val NAVER_IMAP_HOST: String = "imap.naver.com"

        /** IMAPS port for Naver Mail (SSL on port 993). */
        public const val NAVER_IMAP_PORT: Int = 993

        /**
         * Mailbox identifier passed to [SyncCursorStore.observeImapState] / [SyncCursorStore.setImapState].
         * The underlying DataStore keys are `imap_naver_uidvalidity` and `imap_naver_uid`.
         */
        public const val MAILBOX_NAVER: String = "naver"

        /**
         * Upper bound on messages fetched per worker run.
         * Limits memory pressure on large initial syncs; excess messages are picked up in
         * subsequent runs via cursor advancement.
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
