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
import com.becalm.android.data.local.datastore.ImapCursorState
import com.becalm.android.data.local.datastore.MetricsStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.secure.ImapCredentialStore
import com.becalm.android.data.local.secure.ImapCredentialStoreMigrator
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.email.SourceRefEnvelope
import com.becalm.android.data.remote.imap.ImapAttachmentMeta
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapFolder
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.remote.imap.ImapProviderDenylist
import com.becalm.android.data.remote.imap.ImapSpecialUse
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.ProcessingStatusRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.domain.email.EmailPersonRef
import com.becalm.android.domain.email.EmailSnippetBuilder
import com.becalm.android.domain.email.SourceKind
import com.becalm.android.worker.ColdSyncWorkInputs
import com.becalm.android.worker.ProcessingPauseGate
import com.becalm.android.worker.WorkScheduler
import com.becalm.android.worker.hasExceededMaxRetries
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import java.util.UUID

/**
 * Periodic [CoroutineWorker] that syncs Naver Mail via IMAPS and stores new messages
 * as [RawIngestionEventEntity] rows + 1:1 [EmailBodyEntity] rows in Room.
 *
 * ## ING-008 — Naver 2-pass INBOX + Sent scope
 * Connects to `imap.naver.com:993` using the app-password credential retrieved from
 * [ImapCredentialStore.load] under the [SourceType.NAVER_IMAP] namespace (CRIT-01).
 * One `LIST "" "*"` round-trip resolves:
 *  - INBOX via RFC 6154 `\Inbox` SPECIAL-USE flag (fallback mailbox name `"INBOX"`).
 *  - Sent  via RFC 6154 `\Sent`  SPECIAL-USE flag (fallback mailbox name `"보낸메일함"`).
 *
 * Any folder listed in [ImapProviderDenylist.NAVER] (`임시보관함`, `스팸메일함`,
 * `휴지통`, `전체메일`) is filtered out regardless of its special-use flag.
 *
 * ## Per-folder cursor pair (ING-008 + ING-013)
 * Two independent UIDVALIDITY + UIDNEXT cursors are persisted under mailbox identifiers
 * [MAILBOX_NAVER_INBOX] and [MAILBOX_NAVER_SENT]. Each pass advances its own cursor
 * after a durable Room write; a failure in one pass leaves the other's cursor untouched.
 * First-run / UIDVALIDITY-mismatch paths are bounded to the last 30 days by
 * [ImapClient.fetchSince]'s server-side `SEARCH SINCE` (ING-013).
 *
 * ## EmailBody + commitment extraction (EMAIL-001..007)
 * Each fetched message is stored as an [EmailBodyEntity] carrying decoded
 * `body_plain` / `body_html` / `attachments_meta` / `raw_headers`. The snippet for
 * the parent [RawIngestionEventEntity] is built by [EmailSnippetBuilder]; a
 * `SUBJECT_FALLBACK` outcome bumps [MetricsStore.incrementSubjectOnlySkipped] and
 * suppresses the backend upload hand-off per
 * `.spec/email-pipeline.spec.yml:32-37 § EMAIL-003`. Attachment metadata is derived
 * entirely from BODYSTRUCTURE — no bytes are ever downloaded (EMAIL-004).
 *
 * ## PII policy
 * Email addresses, subjects, and body text are never written to logcat. Only counts
 * and 8-char redacted client-event-id hashes appear in logs.
 *
 * ## Result mapping
 * | Outcome                   | WorkManager result        |
 * |---------------------------|---------------------------|
 * | Both passes complete OK   | [Result.success]          |
 * | Network / transient error | [Result.retry] (backoff)  |
 * | Missing credentials       | [Result.failure]          |
 * | Auth error (401)          | [Result.failure]          |
 */
@HiltWorker
public class ImapNaverWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    @UserPrefs private val userPrefs: DataStore<Preferences>,
    private val imapCredentialStore: ImapCredentialStore,
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator,
    private val syncCursorStore: SyncCursorStore,
    private val imapClient: ImapClient,
    private val rawIngestionRepositoryProvider: Provider<RawIngestionRepository>,
    private val emailBodyRepositoryProvider: Provider<EmailBodyRepository>,
    private val sourceStatusRepositoryProvider: Provider<SourceStatusRepository>,
    private val processingStatusRepository: ProcessingStatusRepository,
    private val workScheduler: WorkScheduler,
    private val metricsStore: MetricsStore,
    private val processingPauseGate: ProcessingPauseGate,
    private val moshi: Moshi,
    private val logger: Logger,
) : CoroutineWorker(appContext, workerParams) {

    private val sourceRefAdapter by lazy { moshi.adapter(SourceRefEnvelope::class.java) }
    private val attachmentListAdapter by lazy {
        val listType = Types.newParameterizedType(List::class.java, ImapAttachmentMeta::class.java)
        moshi.adapter<List<ImapAttachmentMeta>>(listType)
    }
    private val stringListAdapter by lazy {
        val listType = Types.newParameterizedType(List::class.java, String::class.java)
        moshi.adapter<List<String>>(listType)
    }
    private val rawIngestionRepository: RawIngestionRepository
        get() = rawIngestionRepositoryProvider.get()
    private val emailBodyRepository: EmailBodyRepository
        get() = emailBodyRepositoryProvider.get()
    private val sourceStatusRepository: SourceStatusRepository
        get() = sourceStatusRepositoryProvider.get()

    public override suspend fun doWork(): Result {
        if (processingPauseGate.shouldSkip(TAG)) {
            return Result.success()
        }
        if (hasExceededMaxRetries(logger, TAG, MAX_RETRIES)) return Result.failure()
        val lookbackDays = inputData.getInt(ColdSyncWorkInputs.KEY_LOOKBACK_DAYS, SINCE_DAYS)

        // ── 0. Ensure legacy-tuple migration has completed ───────────────────
        // The Application.onCreate fire-and-forget launch of [migrateIfNeeded] is not a
        // barrier: if WorkManager enqueues this worker before that launch completes, the
        // old-tuple credential would be invisible under the new `naver_imap_*` namespace
        // and [loadCredentials] would spuriously return null. Calling it synchronously
        // here — the migrator is idempotent (UserPrefsStore flag short-circuit).
        imapCredentialStoreMigrator.migrateIfNeeded()

        // ── 1. Read credentials from EncryptedSharedPreferences (CRIT-01) ────
        val credentials = loadCredentials() ?: run {
            processingStatusRepository.recordBlocked(SourceType.NAVER_IMAP, "IMAP credentials missing")
            return Result.success()
        }
        val imapEmail = credentials.username
        val imapPassword = credentials.appPassword

        // userId is stored separately from the IMAP email; it may differ (Supabase UUID).
        val userId = userPrefs.data
            .map { it[stringPreferencesKey(PREF_KEY_CURRENT_USER_ID)] }
            .first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "no active userId — draining stale work")
            return Result.success()
        }

        sourceStatusRepository.recordSyncStart(SourceType.NAVER_IMAP)
        processingStatusRepository.recordScanning(SourceType.NAVER_IMAP)
        var fetchedCount = 0

        // ── 2. Discover folders ──────────────────────────────────────────────
        val listResult = imapClient.listFolders(
            host = NAVER_IMAP_HOST,
            port = NAVER_IMAP_PORT,
            user = imapEmail,
            password = imapPassword,
        )
        if (listResult is BecalmResult.Failure) {
            return handleFetchFailure(listResult.error)
        }
        val folders = (listResult as BecalmResult.Success).value

        val inboxFolder = resolveFolder(
            folders = folders,
            specialUse = ImapSpecialUse.INBOX,
            fallbackName = FALLBACK_INBOX_NAME,
        )
        val sentFolder = resolveFolder(
            folders = folders,
            specialUse = ImapSpecialUse.SENT,
            fallbackName = FALLBACK_SENT_NAME,
        )

        // ── 3. Pass 1: INBOX ─────────────────────────────────────────────────
        if (inboxFolder == null) {
            logger.w(TAG, "INBOX folder not found — skipping inbox pass")
        } else {
            val outcome = runFolderPass(
                mailboxKey = MAILBOX_NAVER_INBOX,
                imapFolderName = inboxFolder,
                imapEmail = imapEmail,
                imapPassword = imapPassword,
                userId = userId,
                lookbackDays = lookbackDays,
            )
            when (outcome) {
                is PassOutcome.Success -> fetchedCount += outcome.fetchedCount
                is PassOutcome.Terminal -> return outcome.result
            }
        }

        // ── 4. Pass 2: SENT ──────────────────────────────────────────────────
        if (sentFolder == null) {
            logger.w(TAG, "SENT folder not found — skipping sent pass")
        } else {
            val outcome = runFolderPass(
                mailboxKey = MAILBOX_NAVER_SENT,
                imapFolderName = sentFolder,
                imapEmail = imapEmail,
                imapPassword = imapPassword,
                userId = userId,
                lookbackDays = lookbackDays,
            )
            when (outcome) {
                is PassOutcome.Success -> fetchedCount += outcome.fetchedCount
                is PassOutcome.Terminal -> return outcome.result
            }
        }

        processingStatusRepository.recordScanResult(
            sourceType = SourceType.NAVER_IMAP,
            itemCount = fetchedCount,
            newItemsMessage = "Queued backend Gemini extraction",
        )
        sourceStatusRepository.recordSyncSuccess(
            sourceType = SourceType.NAVER_IMAP,
            at = Clock.System.now(),
        )
        logger.d(TAG, "doWork complete")
        return Result.success()
    }

    // ─── Folder discovery ─────────────────────────────────────────────────────

    /**
     * Returns the raw mailbox name for the folder matching [specialUse], skipping anything
     * in [ImapProviderDenylist.NAVER]. Falls back to a name-based match on [fallbackName]
     * when the server does not advertise a SPECIAL-USE flag (common on Naver's Korean
     * Sent folder). Returns `null` when neither resolves.
     */
    private fun resolveFolder(
        folders: List<ImapFolder>,
        specialUse: ImapSpecialUse,
        fallbackName: String,
    ): String? {
        val denylist = ImapProviderDenylist.NAVER
        val allowed = folders.filterNot { it.name in denylist }
        val bySpecial = allowed.firstOrNull { it.specialUse == specialUse }
        if (bySpecial != null) return bySpecial.name
        return allowed.firstOrNull { it.name.equals(fallbackName, ignoreCase = true) }?.name
    }

    // ─── Per-folder pass ──────────────────────────────────────────────────────

    /**
     * Runs one `fetchSince` cycle against [imapFolderName] using the per-folder
     * [mailboxKey] cursor, then advances the cursor on success.
     *
     * Returns [PassOutcome.Success] on a completed pass (possibly zero messages) or
     * [PassOutcome.Terminal] when the worker must exit early with the wrapped
     * WorkManager result.
     */
    private suspend fun runFolderPass(
        mailboxKey: String,
        imapFolderName: String,
        imapEmail: String,
        imapPassword: String,
        userId: String,
        lookbackDays: Int,
    ): PassOutcome {
        val storedCursor: ImapCursorState? =
            syncCursorStore.observeImapState(mailboxKey).first()
        val storedUidValidity = storedCursor?.uidValidity
        val fetchFromUid = storedCursor?.lastSeenUid?.let { it + 1L }

        logger.d(
            TAG,
            "folder pass starting mailbox=$mailboxKey uidValidity=$storedUidValidity fetchFromUid=$fetchFromUid",
        )

        val fetchResult = imapClient.fetchSince(
            host = NAVER_IMAP_HOST,
            port = NAVER_IMAP_PORT,
            user = imapEmail,
            password = imapPassword,
            mailbox = imapFolderName,
            uidValidity = storedUidValidity,
            uidNext = fetchFromUid,
            sinceDays = lookbackDays,
        )

        if (fetchResult is BecalmResult.Failure) {
            return PassOutcome.Terminal(handleFetchFailure(fetchResult.error))
        }

        val fetched = (fetchResult as BecalmResult.Success).value
        val serverUidValidity = fetched.newUidValidity
        val serverUidNext = fetched.newUidNext

        logger.d(
            TAG,
            "folder pass fetched mailbox=$mailboxKey count=${fetched.messages.size} " +
                "serverUidValidity=$serverUidValidity serverUidNext=$serverUidNext",
        )

        // ── Convert + insert raw events ───────────────────────────────────────
        if (fetched.messages.isNotEmpty()) {
            val folderLabel = folderLabelFor(mailboxKey)
            val resolutionResult = resolveImapRawEventIds(
                messages = fetched.messages,
                userId = userId,
                sourceType = SourceType.NAVER_IMAP,
                provider = PROVIDER_NAVER,
                folderLabel = folderLabel,
                emailBodyRepository = emailBodyRepository,
                rawIngestionRepository = rawIngestionRepository,
                toEntity = { message -> message.toEntity(userId, mailboxKey) },
            )
            if (resolutionResult is BecalmResult.Failure) {
                logger.e(
                    TAG,
                    "insertLocalBatch failed mailbox=$mailboxKey error=${resolutionResult.error::class.simpleName}",
                )
                return PassOutcome.Terminal(Result.retry())
            }
            val resolution = (resolutionResult as BecalmResult.Success).value

            logger.d(
                TAG,
                "inserted mailbox=$mailboxKey count=${resolution.insertedCount} " +
                    "deduped=${fetched.messages.size - resolution.insertedCount}",
            )

            // Persist EmailBody + optional extraction enqueue per message.
            fetched.messages.forEachIndexed { index, message ->
                val rawEventId = resolution.rawEventIds.getOrNull(index) ?: return@forEachIndexed
                persistEmailBody(message, rawEventId, mailboxKey)
            }
        }

        // ── Advance cursor after durable write ────────────────────────────────
        val newLastSeenUid = maxOf(serverUidNext - 1L, 0L)
        syncCursorStore.setImapState(
            mailbox = mailboxKey,
            state = ImapCursorState(
                uidValidity = serverUidValidity,
                lastSeenUid = newLastSeenUid,
            ),
        )
        logger.d(
            TAG,
            "cursor advanced mailbox=$mailboxKey uidValidity=$serverUidValidity lastSeenUid=$newLastSeenUid",
        )

        return PassOutcome.Success(fetched.messages.size)
    }

    // ─── Raw event + EmailBody mapping ────────────────────────────────────────

    /**
     * Maps an [ImapMessage] to a [RawIngestionEventEntity].
     *
     * `clientEventId` is folder-scoped and Message-ID based when the provider exposes
     * an RFC 5322 `Message-ID`; it falls back to `UIDVALIDITY:UID` only when that header
     * is absent. This keeps the UNIQUE index on `(user_id, client_event_id)` stable
     * across UIDVALIDITY rebuilds for normal messages while still preserving INBOX/Sent
     * as distinct rows.
     *
     * `sourceRef` is a JSON [SourceRefEnvelope]: the RFC 5322 `Message-Id` header is
     * preferred; when absent (rare but permissible) the composite `"$uidValidity:$uid"`
     * serves as a stable fallback so downstream thread-view code still has a handle.
     *
     * PII note: [ImapMessage.subject] and the snippet payload are stored in Room but
     * never written to logcat.
     */
    private fun ImapMessage.toEntity(userId: String, mailboxKey: String): RawIngestionEventEntity {
        val folderLabel = folderLabelFor(mailboxKey)
        val personRef = derivePersonRef(mailboxKey)
        val sourceRefJson = sourceRefAdapter.toJson(
            SourceRefEnvelope(
                messageId = providerMessageId(),
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
            clientEventId = imapClientEventId(
                provider = PROVIDER_NAVER,
                folder = folderLabel,
                providerMessageId = providerMessageId(),
            ),
            sourceType = SourceType.NAVER_IMAP,
            sourceRef = sourceRefJson,
            personRef = personRef,
            eventTitle = subject,
            eventSnippet = snippetResult.snippet,
            folder = folderLabel,
            timestamp = sentAt,
        )
    }

    /**
     * Returns the EMAIL-002 person_ref for this message given the [mailboxKey]
     * it was fetched under. Delegates to the shared [EmailPersonRef] helper
     * (used by every email ingestion worker).
     */
    private fun ImapMessage.derivePersonRef(mailboxKey: String): String? = when (mailboxKey) {
        MAILBOX_NAVER_INBOX -> EmailPersonRef.forInbox(fromEmail)
        MAILBOX_NAVER_SENT -> EmailPersonRef.forSent(toAddresses)
        else -> null
    }

    /**
     * Writes an [EmailBodyEntity] for [rawEventId] and — when the snippet is NOT
     * subject-only — enqueues backend upload so Railway / Vertex Gemini owns extraction.
     *
     * `parse_failed` flips true when [EmailSnippetBuilder] signals a Jsoup failure on the
     * HTML body; per EMAIL-007 `body_plain` is cleared in the same write so a partial
     * body is never user-visible.
     */
    private suspend fun persistEmailBody(
        message: ImapMessage,
        rawEventId: String,
        mailboxKey: String,
    ) {
        val folderLabel = folderLabelFor(mailboxKey)
        val snippetResult = EmailSnippetBuilder.buildSnippet(
            bodyPlain = message.bodyPlain,
            bodyHtml = message.bodyHtml,
            subject = message.subject,
        )
        val isGroupEmail = mailboxKey == MAILBOX_NAVER_SENT &&
            EmailPersonRef.isGroupEmail(message.toAddresses.size)

        val body = EmailBodyEntity(
            id = UUID.randomUUID().toString(),
            rawEventId = rawEventId,
            providerMessageId = message.providerMessageId(),
            folder = folderLabel,
            subject = message.subject,
            fromAddress = message.fromEmail?.let(::canonicalizeEmail),
            toAddresses = if (message.toAddresses.isEmpty()) {
                null
            } else {
                stringListAdapter.toJson(message.toAddresses.map { it.lowercase() })
            },
            bodyPlain = if (snippetResult.parseFailed) null else message.bodyPlain,
            bodyHtml = message.bodyHtml,
            attachmentsMeta = if (message.attachmentsMeta.isEmpty()) {
                null
            } else {
                attachmentListAdapter.toJson(message.attachmentsMeta)
            },
            rawHeaders = message.rawHeadersJson,
            parseFailed = snippetResult.parseFailed,
            groupEmail = isGroupEmail,
            receivedAt = message.sentAt,
        )
        emailBodyRepository.insert(body)

        // EMAIL-003: subject-only snippets are too thin for LLM extraction; bump
        // the metric and skip the enqueue, but keep the body row so the UI can still
        // surface the event.
        if (snippetResult.sourceKind == SourceKind.SUBJECT_FALLBACK) {
            metricsStore.incrementSubjectOnlySkipped()
            return
        }
        workScheduler.enqueueUpload()
    }

    // ─── Error mapping ────────────────────────────────────────────────────────

    /**
     * Reads IMAP credentials from [ImapCredentialStore]. Logs the provisioning hint when
     * absent and returns `null` so the caller can short-circuit to [Result.failure].
     */
    private suspend fun loadCredentials(): ImapCredentials? {
        val credentials = imapCredentialStore.load(SourceType.NAVER_IMAP)
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
        processingStatusRepository.recordError(SourceType.NAVER_IMAP, error::class.simpleName ?: "IMAP fetch failed")
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
     * Maps the per-folder cursor key to the canonical folder label stored on Room rows
     * ([FOLDER_INBOX] / [FOLDER_SENT]).
     */
    private fun folderLabelFor(mailboxKey: String): String = when (mailboxKey) {
        MAILBOX_NAVER_INBOX -> FOLDER_INBOX
        MAILBOX_NAVER_SENT -> FOLDER_SENT
        else -> FOLDER_INBOX
    }

    // ─── Sync outcome ADT (internal to a single doWork invocation) ────────────

    private sealed interface PassOutcome {
        data class Success(val fetchedCount: Int) : PassOutcome
        data class Terminal(val result: Result) : PassOutcome
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    public companion object {
        private const val TAG = "ImapNaverWorker"
        private const val PROVIDER_NAVER = "naver"

        /** Maximum number of WorkManager retry attempts before failing permanently. */
        public const val MAX_RETRIES: Int = 5

        /** IMAPS server hostname for Naver Mail. */
        public const val NAVER_IMAP_HOST: String = "imap.naver.com"

        /** IMAPS port for Naver Mail (SSL on port 993). */
        public const val NAVER_IMAP_PORT: Int = 993

        /**
         * Per-folder cursor identifier for the Naver INBOX pass.
         * DataStore keys: `imap_naver_inbox_uidvalidity`, `imap_naver_inbox_uid`.
         */
        public const val MAILBOX_NAVER_INBOX: String = "naver_inbox"

        /**
         * Per-folder cursor identifier for the Naver Sent pass.
         * DataStore keys: `imap_naver_sent_uidvalidity`, `imap_naver_sent_uid`.
         */
        public const val MAILBOX_NAVER_SENT: String = "naver_sent"

        /**
         * RFC 6154 SPECIAL-USE fallback for servers that do not advertise `\Inbox`.
         * Naver always uses the literal `"INBOX"` name for its inbox root.
         */
        internal const val FALLBACK_INBOX_NAME: String = "INBOX"

        /**
         * RFC 6154 SPECIAL-USE fallback for Naver's Sent folder. Naver Mail commonly
         * omits `\Sent` on the Korean-named mailbox, so name-based fallback is the
         * only reliable path.
         */
        internal const val FALLBACK_SENT_NAME: String = "보낸메일함"

        /**
         * Upper bound on message age for cold-start and UIDVALIDITY-rebuild passes
         * (ING-013). Passed to [ImapClient.fetchSince.sinceDays].
         */
        internal const val SINCE_DAYS: Int = 30

        /**
         * `@UserPrefs` DataStore key for the current user's Supabase UUID.
         * Mirrors [com.becalm.android.data.local.datastore.UserPrefsStoreImpl].
         */
        private const val PREF_KEY_CURRENT_USER_ID: String = "current_user_id"
    }
}
