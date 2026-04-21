package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
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
import com.becalm.android.data.remote.imap.ImapAttachmentMeta
import com.becalm.android.data.remote.imap.ImapClient
import com.becalm.android.data.remote.imap.ImapFetchResult
import com.becalm.android.data.remote.imap.ImapFolder
import com.becalm.android.data.remote.imap.ImapMessage
import com.becalm.android.data.remote.imap.ImapSpecialUse
import com.becalm.android.data.repository.EmailBodyRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import com.becalm.android.worker.WorkScheduler
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ImapDaumWorker] — mirror of [ImapNaverWorkerTest] with Daum-specific
 * host, Sent fallback name, and source_type.
 *
 * Coverage matches Naver's:
 *  1. 2 passes, cursors under `"daum_inbox"` / `"daum_sent"`.
 *  2. SENT personRef from `toAddresses[0]`.
 *  3. > 10 recipients → group email, personRef null.
 *  4. Missing Message-Id → `"<uidValidity>:<uid>"` envelope fallback.
 *  5. HTML parse failure → subject fallback + skip extraction enqueue.
 *  6. Per-provider credential isolation.
 */
@RunWith(RobolectricTestRunner::class)
class ImapDaumWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val userPrefs: DataStore<Preferences> = mockk()
    private val imapCredentialStore: ImapCredentialStore = mockk()
    private val imapCredentialStoreMigrator: ImapCredentialStoreMigrator = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val imapClient: ImapClient = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val emailBodyRepository: EmailBodyRepository = mockk(relaxed = true)
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val workScheduler: WorkScheduler = mockk(relaxed = true)
    private val metricsStore: MetricsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)
    private val moshi: Moshi = Moshi.Builder().build()

    private lateinit var worker: ImapDaumWorker

    private val fakeUserId = "user-uuid-daum-1"

    private val fakeCredentials = ImapCredentials(
        username = "alice@daum.net",
        appPassword = "app-pass-1234",
        host = ImapDaumWorker.DAUM_IMAP_HOST,
        port = ImapDaumWorker.DAUM_IMAP_PORT,
    )

    private fun imsg(
        uid: Long,
        uidValidity: Long = 42L,
        folder: String = "INBOX",
        messageId: String? = "<msg-$uid@daum.net>",
        subject: String? = "subject-$uid",
        fromEmail: String? = "bob@daum.net",
        fromName: String? = "Bob",
        toAddresses: List<String> = emptyList(),
        bodyPlain: String? = "Plain-text body $uid.",
        bodyHtml: String? = null,
        attachmentsMeta: List<ImapAttachmentMeta> = emptyList(),
        inReplyTo: String? = null,
        references: String? = null,
        rawHeadersJson: String = "{}",
        sentAt: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000 + uid * 1_000),
    ) = ImapMessage(
        uid = uid,
        uidValidity = uidValidity,
        folder = folder,
        messageId = messageId,
        subject = subject,
        fromEmail = fromEmail,
        fromDisplayName = fromName,
        toAddresses = toAddresses,
        bodyPlain = bodyPlain,
        bodyHtml = bodyHtml,
        attachmentsMeta = attachmentsMeta,
        inReplyTo = inReplyTo,
        references = references,
        rawHeadersJson = rawHeadersJson,
        sentAt = sentAt,
    )

    @Before
    fun setUp() {
        worker = ImapDaumWorker(
            appContext = context,
            workerParams = workerParams,
            userPrefs = userPrefs,
            imapCredentialStore = imapCredentialStore,
            imapCredentialStoreMigrator = imapCredentialStoreMigrator,
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepository = rawIngestionRepository,
            emailBodyRepository = emailBodyRepository,
            sourceStatusRepository = sourceStatusRepository,
            workScheduler = workScheduler,
            metricsStore = metricsStore,
            moshi = moshi,
            logger = logger,
        )

        every { workerParams.runAttemptCount } returns 0

        val prefs = mutablePreferencesOf().apply {
            set(stringPreferencesKey("current_user_id"), fakeUserId)
        }
        every { userPrefs.data } returns flowOf(prefs)

        coEvery { imapCredentialStore.load(SourceType.DAUM_IMAP) } returns fakeCredentials

        every { syncCursorStore.observeImapState(ImapDaumWorker.MAILBOX_DAUM_INBOX) } returns
            flowOf(null)
        every { syncCursorStore.observeImapState(ImapDaumWorker.MAILBOX_DAUM_SENT) } returns
            flowOf(null)

        coEvery {
            imapClient.listFolders(
                host = ImapDaumWorker.DAUM_IMAP_HOST,
                port = ImapDaumWorker.DAUM_IMAP_PORT,
                user = fakeCredentials.username,
                password = fakeCredentials.appPassword,
            )
        } returns BecalmResult.Success(
            listOf(
                ImapFolder(name = "INBOX", specialUse = ImapSpecialUse.INBOX),
                ImapFolder(name = "보낸편지함", specialUse = null),
                ImapFolder(name = "스팸함", specialUse = null),
            ),
        )

        coEvery { rawIngestionRepository.insertLocalBatch(any()) } answers {
            val events = firstArg<List<RawIngestionEventEntity>>()
            BecalmResult.Success(events.map { it.id })
        }
    }

    @Test
    fun `doWork twoPasses fourCursorKeys`() = runTest {
        coEvery {
            imapClient.fetchSince(
                host = any(), port = any(), user = any(), password = any(),
                mailbox = "INBOX", uidValidity = null, uidNext = null, sinceDays = 30,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 101L)),
                newUidValidity = 42L,
                newUidNext = 102L,
            ),
        )
        coEvery {
            imapClient.fetchSince(
                host = any(), port = any(), user = any(), password = any(),
                mailbox = "보낸편지함", uidValidity = null, uidNext = null, sinceDays = 30,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 201L, folder = "보낸편지함", toAddresses = listOf("x@y.com"))),
                newUidValidity = 77L,
                newUidNext = 202L,
            ),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerifyOrder {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
            syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM_INBOX, any())
            imapClient.fetchSince(any(), any(), any(), any(), "보낸편지함", any(), any(), any())
            syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM_SENT, any())
        }
        coVerify {
            syncCursorStore.setImapState(
                mailbox = ImapDaumWorker.MAILBOX_DAUM_INBOX,
                state = ImapCursorState(uidValidity = 42L, lastSeenUid = 101L),
            )
            syncCursorStore.setImapState(
                mailbox = ImapDaumWorker.MAILBOX_DAUM_SENT,
                state = ImapCursorState(uidValidity = 77L, lastSeenUid = 201L),
            )
        }
        coVerify(exactly = 0) { syncCursorStore.setImapState("daum", any()) }
    }

    @Test
    fun `sent personRefFromTo0`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 42L, newUidNext = 1L),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸편지함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(
                    imsg(
                        uid = 301L, folder = "보낸편지함",
                        fromEmail = "alice@daum.net",
                        toAddresses = listOf("Target@Ex.COM", "b@y.com"),
                    ),
                ),
                newUidValidity = 77L, newUidNext = 302L,
            ),
        )

        val entities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(entities)) } answers {
            BecalmResult.Success(firstArg<List<RawIngestionEventEntity>>().map { it.id })
        }
        val bodies = slot<EmailBodyEntity>()
        coEvery { emailBodyRepository.insert(capture(bodies)) } returns Unit

        worker.doWork()

        val sentEntity = entities.captured.single()
        assertEquals("target@ex.com", sentEntity.personRef)
        assertEquals(FOLDER_SENT, sentEntity.folder)
        assertEquals(SourceType.DAUM_IMAP, sentEntity.sourceType)
        assertTrue(
            "Sent client_event_id must embed folder segment",
            sentEntity.clientEventId.startsWith("daum:sent:"),
        )
        assertEquals(false, bodies.captured.groupEmail)
        assertNotNull(bodies.captured.toAddresses)
    }

    @Test
    fun `sent over10 groupEmailTrue`() = runTest {
        val bigList = (1..15).map { "to$it@ex.com" }
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 42L, newUidNext = 1L),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸편지함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 401L, folder = "보낸편지함", toAddresses = bigList)),
                newUidValidity = 77L, newUidNext = 402L,
            ),
        )
        val entities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(entities)) } answers {
            BecalmResult.Success(firstArg<List<RawIngestionEventEntity>>().map { it.id })
        }
        val bodies = slot<EmailBodyEntity>()
        coEvery { emailBodyRepository.insert(capture(bodies)) } returns Unit

        worker.doWork()

        assertNull(entities.captured.single().personRef)
        assertTrue(bodies.captured.groupEmail)
    }

    @Test
    fun `sourceRef uidFallback`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 500L, uidValidity = 42L, messageId = null)),
                newUidValidity = 42L, newUidNext = 501L,
            ),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸편지함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 77L, newUidNext = 1L),
        )

        val entities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(entities)) } answers {
            BecalmResult.Success(firstArg<List<RawIngestionEventEntity>>().map { it.id })
        }

        worker.doWork()

        val sourceRef = entities.captured.single().sourceRef
        assertNotNull(sourceRef)
        assertTrue(
            "Missing Message-Id must fall back to <uidValidity>:<uid>. Got: $sourceRef",
            sourceRef!!.contains("\"message_id\":\"42:500\""),
        )
    }

    @Test
    fun `htmlParseFailure gracefulDegrade`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 600L, subject = "hi", bodyPlain = null, bodyHtml = null)),
                newUidValidity = 42L, newUidNext = 601L,
            ),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸편지함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 77L, newUidNext = 1L),
        )

        worker.doWork()

        coVerify { metricsStore.incrementSubjectOnlySkipped() }
        coVerify(exactly = 0) { workScheduler.enqueueCommitmentExtraction(any()) }
    }

    @Test
    fun `usesDaumProviderCredentials`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), any(), any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 42L, newUidNext = 1L),
        )

        worker.doWork()

        coVerify { imapCredentialStore.load(SourceType.DAUM_IMAP) }
    }
}
