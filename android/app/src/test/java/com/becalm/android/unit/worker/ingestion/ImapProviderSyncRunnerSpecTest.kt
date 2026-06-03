package com.becalm.android.unit.worker.ingestion

import androidx.work.ListenableWorker
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.ImapCursorState
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.email.SourceRefEnvelope
import com.becalm.android.data.remote.imap.ImapFetchResult
import com.becalm.android.data.remote.imap.ImapFolder
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.remote.imap.ImapSpecialUse
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.worker.ingestion.IMAP_FETCH_BATCH_SIZE
import com.becalm.android.worker.ingestion.ImapMessagePersistence
import com.becalm.android.worker.ingestion.ImapProviderConfig
import com.becalm.android.worker.ingestion.ImapProviderSyncOutcome
import com.becalm.android.worker.ingestion.ImapProviderSyncRunner
import com.becalm.android.worker.ingestion.ImapRawEventMapper
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImapProviderSyncRunnerSpecTest {

    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val imapClient: com.becalm.android.data.remote.imap.ImapClient = mockk(relaxed = true)
    private val rawIngestionRepository: RawIngestionRepository = mockk(relaxed = true)
    private val messagePersistence: ImapMessagePersistence = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `imap raw event mapper adds account hash without changing RFC conversation root`() {
        val mapper = ImapRawEventMapper(
            config = config(),
            sourceRefAdapter = Moshi.Builder().build().adapter(SourceRefEnvelope::class.java),
        )
        val rootMessage = imapMessage(10L).copy(
            messageId = "<reply@example.com>",
            inReplyTo = "<parent@example.com>",
            references = "<root@example.com> <parent@example.com>",
        )

        val entity = mapper.toEntity(
            message = rootMessage,
            userId = "user-1",
            accountIdentifier = "owner@example.com",
            mailboxKey = "naver_inbox",
            folderLabel = "INBOX",
        )

        assertEquals("root@example.com", entity.conversationRef)
        assertTrue(entity.sourceRef.orEmpty().contains("source_account_key_hash"))
        assertFalse(entity.sourceRef.orEmpty().contains("owner@example.com"))
    }

    @Test
    fun `full IMAP batch advances cursor only to last persisted uid and reports has more`() = runTest {
        val messages = (1L..IMAP_FETCH_BATCH_SIZE.toLong()).map(::imapMessage)
        coEvery { syncCursorStore.observeImapState("naver_inbox") } returns flowOf(null)
        coEvery { rawIngestionRepository.findByClientEventId("user-1", any()) } returns null
        coEvery { rawIngestionRepository.insertLocalBatch(any()) } returns
            BecalmResult.Success(messages.indices.map { index -> "raw-$index" })
        coEvery {
            imapClient.listFolders(
                host = "imap.naver.com",
                port = 993,
                user = "owner@example.com",
                password = "app-password",
            )
        } returns BecalmResult.Success(listOf(ImapFolder("INBOX", ImapSpecialUse.INBOX)))
        coEvery {
            imapClient.fetchSince(
                host = "imap.naver.com",
                port = 993,
                user = "owner@example.com",
                password = "app-password",
                mailbox = "INBOX",
                uidValidity = null,
                uidNext = null,
                sinceDays = 90,
                maxMessages = IMAP_FETCH_BATCH_SIZE,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = messages,
                newUidValidity = 7L,
                newUidNext = 1_001L,
            ),
        )

        val outcome = runner().run(
            imapEmail = "owner@example.com",
            imapPassword = "app-password",
            userId = "user-1",
            lookbackDays = 90,
        )

        assertTrue(outcome is ImapProviderSyncOutcome.Success)
        assertEquals(IMAP_FETCH_BATCH_SIZE, (outcome as ImapProviderSyncOutcome.Success).fetchedCount)
        assertEquals(true, outcome.hasMore)
        coVerify(exactly = 1) {
            syncCursorStore.setImapState(
                mailbox = "naver_inbox",
                state = ImapCursorState(uidValidity = 7L, lastSeenUid = IMAP_FETCH_BATCH_SIZE.toLong()),
            )
        }
        coVerify(exactly = 0) {
            syncCursorStore.setImapState(
                mailbox = "naver_inbox",
                state = ImapCursorState(uidValidity = 7L, lastSeenUid = 1_000L),
            )
        }
        coVerify(exactly = IMAP_FETCH_BATCH_SIZE) {
            messagePersistence.persistEmailBody(
                message = any(),
                rawEventId = any(),
                userId = "user-1",
                sourceType = SourceType.NAVER_IMAP,
                folderLabel = "INBOX",
                isGroupEmail = false,
            )
        }
    }

    private fun runner(): ImapProviderSyncRunner =
        ImapProviderSyncRunner(
            config = config(),
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepository = rawIngestionRepository,
            messagePersistence = messagePersistence,
            rawEventMapper = ImapRawEventMapper(
                config = config(),
                sourceRefAdapter = Moshi.Builder().build().adapter(SourceRefEnvelope::class.java),
            ),
            logger = logger,
            tag = "ImapProviderSyncRunnerSpec",
            onFetchFailure = { _: BecalmError -> ListenableWorker.Result.retry() },
        )

    private fun config(): ImapProviderConfig =
        ImapProviderConfig(
            sourceType = SourceType.NAVER_IMAP,
            provider = "naver",
            host = "imap.naver.com",
            port = 993,
            inboxMailboxKey = "naver_inbox",
            sentMailboxKey = "naver_sent",
            fallbackInboxName = "INBOX",
            fallbackSentName = "Sent",
            denylist = emptySet(),
        )

    private fun imapMessage(uid: Long): ImapMessage =
        ImapMessage(
            uid = uid,
            uidValidity = 7L,
            folder = "INBOX",
            messageId = "<message-$uid@example.com>",
            subject = "Subject $uid",
            fromEmail = "sender@example.com",
            fromDisplayName = "Sender",
            toAddresses = listOf("owner@example.com"),
            bodyPlain = "Body $uid",
            bodyHtml = null,
            attachmentsMeta = emptyList(),
            inReplyTo = null,
            references = null,
            rawHeadersJson = "{}",
            sentAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
}
