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
 * Unit tests for [ImapNaverWorker] (ING-008; 2-pass INBOX + Sent; EMAIL-001..007).
 *
 * ## Coverage map
 *  1. `doWork twoPasses fourCursorKeys` — INBOX runs before SENT; each pass writes its
 *      own cursor under `"naver_inbox"` / `"naver_sent"` (no write to the legacy
 *      `"naver"` key after migration).
 *  2. `sent personRefFromTo0` — SENT scope derives personRef from `toAddresses[0]`
 *      (EMAIL-002).
 *  3. `sent over10 groupEmailTrue` — recipients above
 *      [com.becalm.android.domain.email.EmailPersonRef.GROUP_EMAIL_RECIPIENT_THRESHOLD]
 *      yield `personRef == null` and `EmailBody.groupEmail == true`.
 *  4. `sourceRef uidFallback` — null `Message-Id` falls back to `"<uidValidity>:<uid>"`
 *      in the JSON envelope (EMAIL-005).
 *  5. `htmlParseFailure gracefulDegrade` — `EmailSnippetBuilder` subject fallback
 *      bumps [MetricsStore.incrementSubjectOnlySkipped] and suppresses
 *      [WorkScheduler.enqueueCommitmentExtraction] (EMAIL-003, EMAIL-007).
 *  6. `usesNaverProviderCredentials` — [ImapCredentialStore.load] is called with
 *      [SourceType.NAVER_IMAP] (ING-011 per-provider isolation).
 *
 * Strategy: mock [ImapClient] + repositories + [SyncCursorStore] + [WorkScheduler]
 * + [MetricsStore]. Hand-construct [ImapMessage] fixtures (the Jakarta-Mail-backed
 * wire parse is exercised separately in `ImapClientImplTest` via GreenMail / fake
 * Store — out of scope here).
 */
@RunWith(RobolectricTestRunner::class)
class ImapNaverWorkerTest {

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

    private lateinit var worker: ImapNaverWorker

    private val fakeUserId = "user-uuid-naver-1"

    private val fakeCredentials = ImapCredentials(
        username = "alice@naver.com",
        appPassword = "app-pass-1234",
        host = ImapNaverWorker.NAVER_IMAP_HOST,
        port = ImapNaverWorker.NAVER_IMAP_PORT,
    )

    // ── Fixture factory ──────────────────────────────────────────────────────

    private fun imsg(
        uid: Long,
        uidValidity: Long = 42L,
        folder: String = "INBOX",
        messageId: String? = "<msg-$uid@naver.com>",
        subject: String? = "subject-$uid",
        fromEmail: String? = "bob@naver.com",
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
        worker = ImapNaverWorker(
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

        coEvery { imapCredentialStore.load(SourceType.NAVER_IMAP) } returns fakeCredentials

        // Default: cold start for both folder cursors.
        every { syncCursorStore.observeImapState(ImapNaverWorker.MAILBOX_NAVER_INBOX) } returns
            flowOf(null)
        every { syncCursorStore.observeImapState(ImapNaverWorker.MAILBOX_NAVER_SENT) } returns
            flowOf(null)

        // Default: both folders resolve cleanly.
        coEvery {
            imapClient.listFolders(
                host = ImapNaverWorker.NAVER_IMAP_HOST,
                port = ImapNaverWorker.NAVER_IMAP_PORT,
                user = fakeCredentials.username,
                password = fakeCredentials.appPassword,
            )
        } returns BecalmResult.Success(
            listOf(
                ImapFolder(name = "INBOX", specialUse = ImapSpecialUse.INBOX),
                ImapFolder(name = "보낸메일함", specialUse = null),
                ImapFolder(name = "스팸메일함", specialUse = null),
            ),
        )

        // Default: insert returns 1-to-1 ids for every captured batch.
        coEvery { rawIngestionRepository.insertLocalBatch(any()) } answers {
            val events = firstArg<List<RawIngestionEventEntity>>()
            BecalmResult.Success(events.map { it.id })
        }
    }

    // ── T1: two passes, four cursor keys ──────────────────────────────────────

    @Test
    fun `doWork twoPasses fourCursorKeys`() = runTest {
        coEvery {
            imapClient.fetchSince(
                host = any(),
                port = any(),
                user = any(),
                password = any(),
                mailbox = "INBOX",
                uidValidity = null,
                uidNext = null,
                sinceDays = 30,
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
                host = any(),
                port = any(),
                user = any(),
                password = any(),
                mailbox = "보낸메일함",
                uidValidity = null,
                uidNext = null,
                sinceDays = 30,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 201L, folder = "보낸메일함", toAddresses = listOf("alice@example.com"))),
                newUidValidity = 77L,
                newUidNext = 202L,
            ),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // INBOX pass must run before SENT — assert strict ordering.
        coVerifyOrder {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
            syncCursorStore.setImapState(ImapNaverWorker.MAILBOX_NAVER_INBOX, any())
            imapClient.fetchSince(any(), any(), any(), any(), "보낸메일함", any(), any(), any())
            syncCursorStore.setImapState(ImapNaverWorker.MAILBOX_NAVER_SENT, any())
        }

        // Per-pass cursor must land under its own folder-scoped key.
        coVerify {
            syncCursorStore.setImapState(
                mailbox = ImapNaverWorker.MAILBOX_NAVER_INBOX,
                state = ImapCursorState(uidValidity = 42L, lastSeenUid = 101L),
            )
            syncCursorStore.setImapState(
                mailbox = ImapNaverWorker.MAILBOX_NAVER_SENT,
                state = ImapCursorState(uidValidity = 77L, lastSeenUid = 201L),
            )
        }

        // Legacy single-INBOX key MUST NOT be touched.
        coVerify(exactly = 0) { syncCursorStore.setImapState("naver", any()) }
    }

    // ── T2: SENT personRef = to[0] ───────────────────────────────────────────

    @Test
    fun `sent personRefFromTo0`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 42L, newUidNext = 1L),
        )

        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸메일함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(
                    imsg(
                        uid = 301L,
                        folder = "보낸메일함",
                        fromEmail = "alice@naver.com",
                        toAddresses = listOf("Recipient@Example.COM", "cc@example.com"),
                    ),
                ),
                newUidValidity = 77L,
                newUidNext = 302L,
            ),
        )

        val entities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(entities)) } answers {
            val events = firstArg<List<RawIngestionEventEntity>>()
            BecalmResult.Success(events.map { it.id })
        }
        val bodies = slot<EmailBodyEntity>()
        coEvery { emailBodyRepository.insert(capture(bodies)) } returns Unit

        worker.doWork()

        // SENT pass emits exactly one event (empty inbox); personRef is the
        // lowercased first recipient.
        val sentEntity = entities.captured.single()
        assertEquals("recipient@example.com", sentEntity.personRef)
        assertEquals(FOLDER_SENT, sentEntity.folder)
        assertEquals(SourceType.NAVER_IMAP, sentEntity.sourceType)
        assertTrue(
            "Sent client_event_id must embed folder segment",
            sentEntity.clientEventId.startsWith("naver:sent:"),
        )
        // EmailBody row mirrors the group_email = false case and keeps toAddresses JSON.
        assertEquals(false, bodies.captured.groupEmail)
        assertNotNull(bodies.captured.toAddresses)
    }

    // ── T3: > threshold recipients → group email ─────────────────────────────

    @Test
    fun `sent over10 groupEmailTrue`() = runTest {
        val bigList = (1..15).map { "to$it@example.com" }

        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 42L, newUidNext = 1L),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸메일함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 401L, folder = "보낸메일함", toAddresses = bigList)),
                newUidValidity = 77L,
                newUidNext = 402L,
            ),
        )

        val entities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(entities)) } answers {
            BecalmResult.Success(firstArg<List<RawIngestionEventEntity>>().map { it.id })
        }
        val bodies = slot<EmailBodyEntity>()
        coEvery { emailBodyRepository.insert(capture(bodies)) } returns Unit

        worker.doWork()

        val sent = entities.captured.single()
        assertNull("> threshold recipients must zero out personRef", sent.personRef)
        assertTrue(
            "EmailBody.groupEmail must flag > threshold recipients",
            bodies.captured.groupEmail,
        )
    }

    // ── T4: sourceRef JSON envelope uid fallback ─────────────────────────────

    @Test
    fun `sourceRef uidFallback`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(imsg(uid = 500L, uidValidity = 42L, messageId = null)),
                newUidValidity = 42L,
                newUidNext = 501L,
            ),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸메일함", any(), any(), any())
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

    // ── T5: HTML parse failure → subject fallback, no extraction enqueue ────

    @Test
    fun `htmlParseFailure gracefulDegrade`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "INBOX", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(
                    imsg(
                        uid = 600L,
                        subject = "Reminder: meeting tomorrow",
                        bodyPlain = null,
                        bodyHtml = null, // snippet builder falls through to subject
                    ),
                ),
                newUidValidity = 42L,
                newUidNext = 601L,
            ),
        )
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), "보낸메일함", any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 77L, newUidNext = 1L),
        )

        worker.doWork()

        // Subject-only → metric bumped + extraction enqueue skipped.
        coVerify { metricsStore.incrementSubjectOnlySkipped() }
        coVerify(exactly = 0) { workScheduler.enqueueCommitmentExtraction(any()) }
    }

    // ── T6: Per-provider credential isolation ────────────────────────────────

    @Test
    fun `usesNaverProviderCredentials`() = runTest {
        // Default stubs already cover both passes returning empty. The one
        // assertion we care about is that [ImapCredentialStore.load] is called
        // with SourceType.NAVER_IMAP (ING-011 per-provider isolation).
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), any(), any(), any(), any())
        } returns BecalmResult.Success(
            ImapFetchResult(messages = emptyList(), newUidValidity = 42L, newUidNext = 1L),
        )

        worker.doWork()

        coVerify { imapCredentialStore.load(SourceType.NAVER_IMAP) }
    }
}
