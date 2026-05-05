package com.becalm.android.worker.ingestion

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.ImapCursorState
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.dto.FOLDER_INBOX
import com.becalm.android.data.remote.dto.FOLDER_SENT
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapFolder
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.remote.imap.ImapSpecialUse
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.domain.email.EmailPersonRef
import kotlinx.coroutines.flow.first

internal data class ImapProviderConfig(
    val sourceType: String,
    val provider: String,
    val host: String,
    val port: Int,
    val inboxMailboxKey: String,
    val sentMailboxKey: String,
    val fallbackInboxName: String,
    val fallbackSentName: String,
    val denylist: Set<String>,
)

internal sealed interface ImapProviderSyncOutcome {
    data class Success(val fetchedCount: Int) : ImapProviderSyncOutcome
    data class Terminal(val result: ListenableWorker.Result) : ImapProviderSyncOutcome
}

internal class ImapProviderSyncRunner(
    private val config: ImapProviderConfig,
    private val syncCursorStore: SyncCursorStore,
    private val imapClient: ImapClient,
    private val rawIngestionRepository: RawIngestionRepository,
    private val emailBodyRepository: EmailBodyRepository,
    private val messagePersistence: ImapMessagePersistence,
    private val rawEventMapper: ImapRawEventMapper,
    private val logger: Logger,
    private val tag: String,
    private val onFetchFailure: suspend (BecalmError) -> ListenableWorker.Result,
) {
    suspend fun run(
        imapEmail: String,
        imapPassword: String,
        userId: String,
        lookbackDays: Int,
    ): ImapProviderSyncOutcome {
        val listResult = imapClient.listFolders(
            host = config.host,
            port = config.port,
            user = imapEmail,
            password = imapPassword,
        )
        if (listResult is BecalmResult.Failure) {
            return ImapProviderSyncOutcome.Terminal(onFetchFailure(listResult.error))
        }
        val folders = (listResult as BecalmResult.Success).value

        var fetchedCount = 0
        folderPassPlans(folders).forEach { pass ->
            val folder = pass.folderName
            if (folder == null) {
                logger.w(tag, "${pass.logLabel} folder not found — skipping ${pass.logLabel.lowercase()} pass")
                return@forEach
            }
            when (
                val outcome = runFolderPass(
                    mailboxKey = pass.mailboxKey,
                    imapFolderName = folder,
                    imapEmail = imapEmail,
                    imapPassword = imapPassword,
                    userId = userId,
                    lookbackDays = lookbackDays,
                )
            ) {
                is ImapProviderSyncOutcome.Success -> fetchedCount += outcome.fetchedCount
                is ImapProviderSyncOutcome.Terminal -> return outcome
            }
        }

        return ImapProviderSyncOutcome.Success(fetchedCount)
    }

    private fun resolveFolder(
        folders: List<ImapFolder>,
        specialUse: ImapSpecialUse,
        fallbackName: String,
    ): String? {
        val allowed = folders.filterNot { it.name in config.denylist }
        return allowed.firstOrNull { it.specialUse == specialUse }?.name
            ?: allowed.firstOrNull { it.name.equals(fallbackName, ignoreCase = true) }?.name
    }

    private fun folderPassPlans(folders: List<ImapFolder>): List<FolderPassPlan> =
        listOf(
            FolderPassPlan(
                mailboxKey = config.inboxMailboxKey,
                folderName = resolveFolder(folders, ImapSpecialUse.INBOX, config.fallbackInboxName),
                logLabel = "INBOX",
            ),
            FolderPassPlan(
                mailboxKey = config.sentMailboxKey,
                folderName = resolveFolder(folders, ImapSpecialUse.SENT, config.fallbackSentName),
                logLabel = "SENT",
            ),
        )

    private suspend fun runFolderPass(
        mailboxKey: String,
        imapFolderName: String,
        imapEmail: String,
        imapPassword: String,
        userId: String,
        lookbackDays: Int,
    ): ImapProviderSyncOutcome {
        val storedCursor: ImapCursorState? = syncCursorStore.observeImapState(mailboxKey).first()
        val storedUidValidity = storedCursor?.uidValidity
        val fetchFromUid = storedCursor?.lastSeenUid?.let { it + 1L }
        logger.d(
            tag,
            "folder pass starting mailbox=$mailboxKey uidValidity=$storedUidValidity fetchFromUid=$fetchFromUid",
        )

        val fetchResult = imapClient.fetchSince(
            host = config.host,
            port = config.port,
            user = imapEmail,
            password = imapPassword,
            mailbox = imapFolderName,
            uidValidity = storedUidValidity,
            uidNext = fetchFromUid,
            sinceDays = lookbackDays,
        )
        if (fetchResult is BecalmResult.Failure) {
            return ImapProviderSyncOutcome.Terminal(onFetchFailure(fetchResult.error))
        }

        val fetched = (fetchResult as BecalmResult.Success).value
        logger.d(
            tag,
            "folder pass fetched mailbox=$mailboxKey count=${fetched.messages.size} " +
                "serverUidValidity=${fetched.newUidValidity} serverUidNext=${fetched.newUidNext}",
        )

        if (fetched.messages.isNotEmpty()) {
            val folderLabel = folderLabelFor(mailboxKey)
            val resolutionResult = resolveImapRawEventIds(
                messages = fetched.messages,
                userId = userId,
                sourceType = config.sourceType,
                provider = config.provider,
                folderLabel = folderLabel,
                emailBodyRepository = emailBodyRepository,
                rawIngestionRepository = rawIngestionRepository,
                toEntity = { message ->
                    rawEventMapper.toEntity(
                        message = message,
                        userId = userId,
                        mailboxKey = mailboxKey,
                        folderLabel = folderLabel,
                    )
                },
            )
            if (resolutionResult is BecalmResult.Failure) {
                logger.e(
                    tag,
                    "insertLocalBatch failed mailbox=$mailboxKey error=${resolutionResult.error::class.simpleName}",
                )
                return ImapProviderSyncOutcome.Terminal(ListenableWorker.Result.retry())
            }
            val resolution = (resolutionResult as BecalmResult.Success).value
            logger.d(
                tag,
                "inserted mailbox=$mailboxKey count=${resolution.insertedCount} " +
                    "deduped=${fetched.messages.size - resolution.insertedCount}",
            )
            fetched.messages.forEachIndexed { index, message ->
                val rawEventId = resolution.rawEventIds.getOrNull(index) ?: return@forEachIndexed
                messagePersistence.persistEmailBody(
                    message = message,
                    rawEventId = rawEventId,
                    userId = userId,
                    sourceType = config.sourceType,
                    folderLabel = folderLabel,
                    isGroupEmail = mailboxKey == config.sentMailboxKey &&
                        EmailPersonRef.isGroupEmail(message.toAddresses.size),
                )
            }
        }

        val newLastSeenUid = maxOf(fetched.newUidNext - 1L, 0L)
        syncCursorStore.setImapState(
            mailbox = mailboxKey,
            state = ImapCursorState(
                uidValidity = fetched.newUidValidity,
                lastSeenUid = newLastSeenUid,
            ),
        )
        logger.d(
            tag,
            "cursor advanced mailbox=$mailboxKey uidValidity=${fetched.newUidValidity} lastSeenUid=$newLastSeenUid",
        )
        return ImapProviderSyncOutcome.Success(fetched.messages.size)
    }

    private fun folderLabelFor(mailboxKey: String): String =
        if (mailboxKey == config.sentMailboxKey) FOLDER_SENT else FOLDER_INBOX
}

private data class FolderPassPlan(
    val mailboxKey: String,
    val folderName: String?,
    val logLabel: String,
)
