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
 * Periodic [CoroutineWorker] that syncs Daum Mail via IMAPS and stores new messages
 * as [RawIngestionEventEntity] rows + 1:1 [EmailBodyEntity] rows in Room.
 *
 * Mirrors [ImapNaverWorker] byte-for-byte at the logical level — only the host, port,
 * folder-fallback names, denylist, and source_type differ. See the Naver worker's KDoc
 * for the full semantics.
 *
 * ## ING-008 — Daum 2-pass INBOX + Sent scope
 * Connects to `imap.daum.net:993` using the app-password credential retrieved from
 * [ImapCredentialStore.load] under the [SourceType.DAUM_IMAP] namespace (CRIT-01).
 * RFC 6154 SPECIAL-USE detection with a name-based fallback of `"보낸편지함"` for Sent.
 * Folder denylist: [ImapProviderDenylist.DAUM].
 *
 * ## Per-folder cursor pair
 * Two independent UIDVALIDITY + UIDNEXT cursors under [MAILBOX_DAUM_INBOX] /
 * [MAILBOX_DAUM_SENT]. Cold-start + UIDVALIDITY-mismatch rebuilds are bounded to the
 * last 30 days via [ImapClient.fetchSince]'s `SEARCH SINCE` (ING-013).
 *
 * ## EmailBody + commitment extraction (EMAIL-001..007)
 * See [ImapNaverWorker] — the per-message persistence and extraction enqueue logic
 * is shared conceptually; the two workers diverge only on provider-specific constants.
 */
@HiltWorker
public class ImapDaumWorker @AssistedInject constructor(
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

        // Idempotent legacy-tuple credential migration (see ImapNaverWorker.doWork §0).
        imapCredentialStoreMigrator.migrateIfNeeded()

        val credentials = loadCredentials() ?: return Result.success()
        val imapEmail = credentials.username
        val imapPassword = credentials.appPassword

        val userId = userPrefs.data
            .map { it[stringPreferencesKey(PREF_KEY_CURRENT_USER_ID)] }
            .first()
        if (userId.isNullOrBlank()) {
            logger.w(TAG, "no active userId — draining stale work")
            return Result.success()
        }

        sourceStatusRepository.recordSyncStart(SourceType.DAUM_IMAP)

        // ── Discover folders ─────────────────────────────────────────────────
        val listResult = imapClient.listFolders(
            host = DAUM_IMAP_HOST,
            port = DAUM_IMAP_PORT,
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

        // ── Pass 1: INBOX ────────────────────────────────────────────────────
        if (inboxFolder == null) {
            logger.w(TAG, "INBOX folder not found — skipping inbox pass")
        } else {
            val outcome = runFolderPass(
                mailboxKey = MAILBOX_DAUM_INBOX,
                imapFolderName = inboxFolder,
                imapEmail = imapEmail,
                imapPassword = imapPassword,
                userId = userId,
                lookbackDays = lookbackDays,
            )
            if (outcome is PassOutcome.Terminal) return outcome.result
        }

        // ── Pass 2: SENT ─────────────────────────────────────────────────────
        if (sentFolder == null) {
            logger.w(TAG, "SENT folder not found — skipping sent pass")
        } else {
            val outcome = runFolderPass(
                mailboxKey = MAILBOX_DAUM_SENT,
                imapFolderName = sentFolder,
                imapEmail = imapEmail,
                imapPassword = imapPassword,
                userId = userId,
                lookbackDays = lookbackDays,
            )
            if (outcome is PassOutcome.Terminal) return outcome.result
        }

        sourceStatusRepository.recordSyncSuccess(
            sourceType = SourceType.DAUM_IMAP,
            at = Clock.System.now(),
        )
        logger.d(TAG, "doWork complete")
        return Result.success()
    }

    // ─── Folder discovery ─────────────────────────────────────────────────────

    /**
     * Filters [folders] by [ImapProviderDenylist.DAUM], then prefers an RFC 6154
     * SPECIAL-USE match, falling back to a case-insensitive name match on [fallbackName].
     */
    private fun resolveFolder(
        folders: List<ImapFolder>,
        specialUse: ImapSpecialUse,
        fallbackName: String,
    ): String? {
        val denylist = ImapProviderDenylist.DAUM
        val allowed = folders.filterNot { it.name in denylist }
        val bySpecial = allowed.firstOrNull { it.specialUse == specialUse }
        if (bySpecial != null) return bySpecial.name
        return allowed.firstOrNull { it.name.equals(fallbackName, ignoreCase = true) }?.name
    }

    // ─── Per-folder pass ──────────────────────────────────────────────────────

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
            host = DAUM_IMAP_HOST,
            port = DAUM_IMAP_PORT,
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

        if (fetched.messages.isNotEmpty()) {
            val entities = fetched.messages.map { msg -> msg.toEntity(userId, mailboxKey) }
            val insertResult = rawIngestionRepository.insertLocalBatch(entities)
            if (insertResult is BecalmResult.Failure) {
                logger.e(
                    TAG,
                    "insertLocalBatch failed mailbox=$mailboxKey error=${insertResult.error::class.simpleName}",
                )
                return PassOutcome.Terminal(Result.retry())
            }
            val rawEventIds = (insertResult as BecalmResult.Success).value

            logger.d(
                TAG,
                "inserted mailbox=$mailboxKey count=${entities.size} " +
                    "uidHashes=${entities.map { redact(it.clientEventId) }}",
            )

            fetched.messages.forEachIndexed { index, message ->
                val rawEventId = rawEventIds.getOrNull(index) ?: return@forEachIndexed
                persistEmailBody(message, rawEventId, mailboxKey)
            }
        }

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
     * Maps an [ImapMessage] to a [RawIngestionEventEntity] with folder-aware
     * `clientEventId` (`"daum:<folder>:<uid>:<uidValidity>"`).
     *
     * See [ImapNaverWorker.toEntity] for the invariants shared across providers.
     */
    private fun ImapMessage.toEntity(userId: String, mailboxKey: String): RawIngestionEventEntity {
        val folderLabel = folderLabelFor(mailboxKey)
        val personRef = derivePersonRef(mailboxKey)
        val sourceRefJson = sourceRefAdapter.toJson(
            SourceRefEnvelope(
                messageId = messageId?.takeIf { it.isNotBlank() } ?: "$uidValidity:$uid",
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
            clientEventId = "daum:${folderLabel.lowercase()}:$uid:$uidValidity",
            sourceType = SourceType.DAUM_IMAP,
            sourceRef = sourceRefJson,
            personRef = personRef,
            eventTitle = subject,
            eventSnippet = snippetResult.snippet,
            folder = folderLabel,
            timestamp = sentAt,
        )
    }

    private fun ImapMessage.derivePersonRef(mailboxKey: String): String? = when (mailboxKey) {
        MAILBOX_DAUM_INBOX -> EmailPersonRef.forInbox(fromEmail)
        MAILBOX_DAUM_SENT -> EmailPersonRef.forSent(toAddresses)
        else -> null
    }

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
        val isGroupEmail = mailboxKey == MAILBOX_DAUM_SENT &&
            EmailPersonRef.isGroupEmail(message.toAddresses.size)

        val body = EmailBodyEntity(
            id = UUID.randomUUID().toString(),
            rawEventId = rawEventId,
            providerMessageId = message.messageId?.takeIf { it.isNotBlank() }
                ?: "${message.uidValidity}:${message.uid}",
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

        if (snippetResult.sourceKind == SourceKind.SUBJECT_FALLBACK) {
            metricsStore.incrementSubjectOnlySkipped()
            return
        }
        workScheduler.enqueueCommitmentExtraction(rawEventId)
    }

    // ─── Error mapping ────────────────────────────────────────────────────────

    private suspend fun loadCredentials(): ImapCredentials? {
        val credentials = imapCredentialStore.load(SourceType.DAUM_IMAP)
        if (credentials == null) {
            logger.w(TAG, "IMAP Daum credentials absent — provisioned by SP-53")
        }
        return credentials
    }

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

    private fun folderLabelFor(mailboxKey: String): String = when (mailboxKey) {
        MAILBOX_DAUM_INBOX -> FOLDER_INBOX
        MAILBOX_DAUM_SENT -> FOLDER_SENT
        else -> FOLDER_INBOX
    }

    private sealed interface PassOutcome {
        data class Success(val fetchedCount: Int) : PassOutcome
        data class Terminal(val result: Result) : PassOutcome
    }

    public companion object {
        private const val TAG = "ImapDaumWorker"

        /** Maximum number of WorkManager retry attempts before failing permanently. */
        public const val MAX_RETRIES: Int = 5

        /** IMAPS server hostname for Daum Mail. */
        public const val DAUM_IMAP_HOST: String = "imap.daum.net"

        /** IMAPS port for Daum Mail (SSL on port 993). */
        public const val DAUM_IMAP_PORT: Int = 993

        /**
         * Per-folder cursor identifier for the Daum INBOX pass.
         * DataStore keys: `imap_daum_inbox_uidvalidity`, `imap_daum_inbox_uid`.
         */
        public const val MAILBOX_DAUM_INBOX: String = "daum_inbox"

        /**
         * Per-folder cursor identifier for the Daum Sent pass.
         * DataStore keys: `imap_daum_sent_uidvalidity`, `imap_daum_sent_uid`.
         */
        public const val MAILBOX_DAUM_SENT: String = "daum_sent"

        /**
         * RFC 6154 SPECIAL-USE fallback for Daum's INBOX. Daum follows the canonical
         * `"INBOX"` name like Naver.
         */
        internal const val FALLBACK_INBOX_NAME: String = "INBOX"

        /**
         * RFC 6154 SPECIAL-USE fallback for Daum's Sent folder. Daum Mail uses
         * `"보낸편지함"` (different from Naver's `"보낸메일함"`).
         */
        internal const val FALLBACK_SENT_NAME: String = "보낸편지함"

        /** Upper bound on message age for cold-start / rebuild passes (ING-013). */
        internal const val SINCE_DAYS: Int = 30

        /**
         * `@UserPrefs` DataStore key for the current user's Supabase UUID.
         * Mirrors [com.becalm.android.data.local.datastore.UserPrefsStoreImpl].
         */
        private const val PREF_KEY_CURRENT_USER_ID: String = "current_user_id"
    }
}
