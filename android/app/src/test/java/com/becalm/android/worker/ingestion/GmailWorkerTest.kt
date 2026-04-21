package com.becalm.android.worker.ingestion

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.gmail.GmailClient
import com.becalm.android.data.remote.gmail.GmailHistoryPage
import com.becalm.android.data.remote.gmail.GmailLabel
import com.becalm.android.data.remote.gmail.GmailMessage
import com.becalm.android.data.remote.gmail.GmailMessagePage
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [GmailWorker] (ING-006, ING-012, ING-013, ING-015).
 *
 * Strategy: hand-construct deterministic [BecalmResult]-shaped responses from [GmailClient]
 * and assert (a) the produced [RawIngestionEventEntity] shape, (b) cursor advancement, and
 * (c) the [SyncOutcome] → [Result] mapping for each error class.
 *
 * Test cases:
 * 1. No session — [Result.failure] (fail-closed; no Gmail call made).
 * 2. Incremental happy path — stored historyId triggers [GmailClient.listHistory], inserts
 *    each message via [RawIngestionRepository.insertLocal] with deterministic
 *    `clientEventId="gmail:<msgId>"`, advances cursor with returned historyId.
 * 3. Full-sync (cold start) — null historyId triggers [GmailClient.listMessagesFullSync],
 *    pages via nextPageToken, then seeds historyId via the conventional `listHistory("1")` bootstrap.
 * 4. HistoryExpired (NotFound) → cursor cleared, fallback full-sync runs, final result
 *    follows the fallback's [SyncOutcome].
 * 5. Unauthorized from incremental → [Result.failure], [SourceStatusRepository.recordSyncError].
 * 6. RateLimited from incremental → [Result.retry] with reason in error message.
 * 7. Per-message getMessage failure is logged but does NOT abort the page (idempotency).
 */
@RunWith(RobolectricTestRunner::class)
class GmailWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val gmailClient: GmailClient = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val cursorStore: SyncCursorStore = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var worker: GmailWorker

    private val fakeUserId = "user-uuid-gmail-1"
    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = fakeUserId,
        email = "alice@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    private val msg1 = GmailMessage(
        messageId = "abc-1",
        subject = "Hello",
        from = "Bob Builder <bob@example.com>",
        to = null,
        snippet = "snippet 1",
        internalDate = 1_700_000_000_000L,
    )

    private val msg2 = GmailMessage(
        messageId = "abc-2",
        subject = null,
        from = "carol@example.com",
        to = null,
        snippet = null,
        internalDate = 1_700_000_060_000L,
    )

    @Before
    fun setUp() {
        worker = GmailWorker(
            appContext = context,
            workerParams = workerParams,
            gmailClient = gmailClient,
            rawIngestionRepository = rawIngestionRepository,
            sourceStatusRepository = sourceStatusRepository,
            cursorStore = cursorStore,
            authRepository = authRepository,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        every { workerParams.runAttemptCount } returns 0
        coEvery { authRepository.currentSession() } returns fakeSession

        // Default: no stored cursor (cold start)
        every { cursorStore.observeGmailHistoryId() } returns flowOf(null)
        // Default: insertLocal succeeds and returns the entity id
        coEvery { rawIngestionRepository.insertLocal(any()) } answers {
            BecalmResult.Success(firstArg<RawIngestionEventEntity>().id)
        }
    }

    // ── T1: No session → Result.failure ──────────────────────────────────────

    @Test
    fun `doWork returns failure and records unauthorized when no session`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { gmailClient.listHistory(any()) }
        coVerify(exactly = 0) { gmailClient.listMessagesFullSync(any(), any()) }
        coVerify {
            sourceStatusRepository.recordSyncError(SourceType.GMAIL, "unauthorized", any())
        }
    }

    // ── T2: Incremental happy path — cursor advance + per-message insert ─────

    @Test
    fun `doWork incremental sync inserts messages with deterministic clientEventId and advances cursor`() = runTest {
        every { cursorStore.observeGmailHistoryId() } returns flowOf(12345L)

        coEvery { gmailClient.listHistory("12345") } returns BecalmResult.Success(
            GmailHistoryPage(
                messageIds = listOf(msg1.messageId, msg2.messageId),
                nextPageToken = null,
                historyId = "67890",
            ),
        )
        coEvery { gmailClient.getMessage(msg1.messageId) } returns BecalmResult.Success(msg1)
        coEvery { gmailClient.getMessage(msg2.messageId) } returns BecalmResult.Success(msg2)

        val capturedEntities = mutableListOf<RawIngestionEventEntity>()
        coEvery { rawIngestionRepository.insertLocal(capture(capturedEntities)) } answers {
            BecalmResult.Success(firstArg<RawIngestionEventEntity>().id)
        }

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        assertEquals(2, capturedEntities.size)
        assertEquals("gmail:abc-1", capturedEntities[0].clientEventId)
        assertEquals("gmail:abc-2", capturedEntities[1].clientEventId)
        assertEquals(SourceType.GMAIL, capturedEntities[0].sourceType)
        assertEquals(fakeUserId, capturedEntities[0].userId)
        // Display-name angle-bracket extraction + lowercase canonicalisation
        assertEquals("bob@example.com", capturedEntities[0].personRef)
        assertEquals("carol@example.com", capturedEntities[1].personRef)
        // Cursor advanced to the page's historyId
        coVerify { cursorStore.setGmailHistoryId(67890L) }
        coVerify { sourceStatusRepository.recordSyncSuccess(SourceType.GMAIL, any()) }
    }

    // ── T3: Full-sync cold start — paginates then seeds historyId ────────────

    @Test
    fun `doWork full-sync paginates via nextPageToken and seeds historyId after`() = runTest {
        // Cold start: cursor null → full sync path. EMAIL-001 backfill pulls both
        // INBOX and SENT labels; the two paginated passes below cover them.
        coEvery {
            gmailClient.listMessagesFullSync(GmailLabel.INBOX, null)
        } returns BecalmResult.Success(
            GmailMessagePage(messageIds = listOf(msg1.messageId), nextPageToken = "page2"),
        )
        coEvery {
            gmailClient.listMessagesFullSync(GmailLabel.INBOX, "page2")
        } returns BecalmResult.Success(
            GmailMessagePage(messageIds = listOf(msg2.messageId), nextPageToken = null),
        )
        // Empty SENT pass keeps the fixture focused on pagination behaviour.
        coEvery {
            gmailClient.listMessagesFullSync(GmailLabel.SENT, null)
        } returns BecalmResult.Success(
            GmailMessagePage(messageIds = emptyList(), nextPageToken = null),
        )
        coEvery { gmailClient.getMessage(msg1.messageId) } returns BecalmResult.Success(msg1)
        coEvery { gmailClient.getMessage(msg2.messageId) } returns BecalmResult.Success(msg2)

        // After full-sync the worker calls listHistory("1") to seed the cursor.
        coEvery { gmailClient.listHistory("1") } returns BecalmResult.Success(
            GmailHistoryPage(
                messageIds = emptyList(),
                nextPageToken = null,
                historyId = "55555",
            ),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerifyOrder {
            gmailClient.listMessagesFullSync(GmailLabel.INBOX, null)
            gmailClient.listMessagesFullSync(GmailLabel.INBOX, "page2")
            gmailClient.listMessagesFullSync(GmailLabel.SENT, null)
            gmailClient.listHistory("1")
        }
        // Seeded cursor must equal the bootstrap historyId.
        coVerify { cursorStore.setGmailHistoryId(55555L) }
    }

    // ── T4: HistoryExpired → cursor cleared and full-sync fallback fires ─────

    @Test
    fun `doWork clears cursor and falls back to full-sync on HistoryExpired (NotFound)`() = runTest {
        every { cursorStore.observeGmailHistoryId() } returns flowOf(99L)

        // Incremental returns NotFound → SyncOutcome.HistoryExpired
        coEvery { gmailClient.listHistory("99") } returns
            BecalmResult.Failure(BecalmError.NotFound("expired"))

        // Fallback full-sync returns empty pages for both labels.
        coEvery {
            gmailClient.listMessagesFullSync(GmailLabel.INBOX, null)
        } returns BecalmResult.Success(
            GmailMessagePage(messageIds = emptyList(), nextPageToken = null),
        )
        coEvery {
            gmailClient.listMessagesFullSync(GmailLabel.SENT, null)
        } returns BecalmResult.Success(
            GmailMessagePage(messageIds = emptyList(), nextPageToken = null),
        )
        coEvery { gmailClient.listHistory("1") } returns BecalmResult.Success(
            GmailHistoryPage(messageIds = emptyList(), nextPageToken = null, historyId = "100"),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        // The stored historyId must be cleared (set to null) before fallback runs.
        coVerify { cursorStore.setGmailHistoryId(null) }
        // Fallback full-sync must fire for BOTH labels.
        coVerify { gmailClient.listMessagesFullSync(GmailLabel.INBOX, null) }
        coVerify { gmailClient.listMessagesFullSync(GmailLabel.SENT, null) }
    }

    // ── T5: Unauthorized → Result.failure + recordSyncError ──────────────────

    @Test
    fun `doWork returns failure and records unauthorized error from incremental`() = runTest {
        every { cursorStore.observeGmailHistoryId() } returns flowOf(7L)
        coEvery { gmailClient.listHistory("7") } returns
            BecalmResult.Failure(BecalmError.Unauthorized)

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify {
            sourceStatusRepository.recordSyncError(
                SourceType.GMAIL,
                "unauthorized",
                any(),
            )
        }
    }

    // ── T6: RateLimited → Result.retry with descriptive reason ───────────────

    @Test
    fun `doWork returns retry on rate-limited incremental sync`() = runTest {
        every { cursorStore.observeGmailHistoryId() } returns flowOf(11L)
        coEvery { gmailClient.listHistory("11") } returns
            BecalmResult.Failure(BecalmError.RateLimited(retryAfterSeconds = 30L))

        val capturedReason = slot<String>()
        coEvery {
            sourceStatusRepository.recordSyncError(SourceType.GMAIL, capture(capturedReason), any())
        } returns BecalmResult.Success(Unit)

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        // The reason string is propagated for dashboard display.
        assertEquals(true, capturedReason.captured.startsWith("rate_limited"))
    }

    // ── T7: Per-message getMessage failure does NOT abort the page (idempotency) ─

    @Test
    fun `doWork skips bad message and continues page when getMessage fails`() = runTest {
        every { cursorStore.observeGmailHistoryId() } returns flowOf(20L)
        coEvery { gmailClient.listHistory("20") } returns BecalmResult.Success(
            GmailHistoryPage(
                messageIds = listOf(msg1.messageId, msg2.messageId),
                nextPageToken = null,
                historyId = "21",
            ),
        )
        // First message getMessage fails; second succeeds.
        coEvery { gmailClient.getMessage(msg1.messageId) } returns
            BecalmResult.Failure(BecalmError.Network(-1, "io"))
        coEvery { gmailClient.getMessage(msg2.messageId) } returns BecalmResult.Success(msg2)

        val result = worker.doWork()

        // Worker still reports success; the page partially completed.
        assertEquals(Result.success(), result)
        coVerify(exactly = 1) {
            rawIngestionRepository.insertLocal(match { it.clientEventId == "gmail:abc-2" })
        }
        coVerify(exactly = 0) {
            rawIngestionRepository.insertLocal(match { it.clientEventId == "gmail:abc-1" })
        }
    }
}
