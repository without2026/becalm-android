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
import com.becalm.android.data.remote.msgraph.GraphDeltaResponse
import com.becalm.android.data.remote.msgraph.GraphMessage
import com.becalm.android.data.remote.msgraph.MsGraphClient
import com.becalm.android.data.remote.supabase.SupabaseSession
import com.becalm.android.data.repository.AuthRepository
import com.becalm.android.data.repository.RawIngestionRepository
import com.becalm.android.data.repository.SourceStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Unit tests for [OutlookMailWorker] (ING-006).
 *
 * Structurally similar to [GmailWorkerTest]: both are email delta workers that read a stored
 * cursor, stream pages, and map transport errors onto [Result]. Where Gmail maintains its
 * own history-id numeric cursor, Outlook uses an opaque URL from [GraphDeltaResponse.nextLink]
 * / [GraphDeltaResponse.deltaLink] — so this test concentrates on:
 *   * the [SyncCursorStore.setCursor] calls at mid-batch (nextLink) and end-of-batch (deltaLink)
 *   * the HTTP 410 → [SyncCursorStore.clearCursor] → [Result.retry] contract
 *   * the credential-absent fail-closed path.
 *
 * Test cases:
 * 1. Happy path — cursor null → 2 messages → deltaLink stored. Entities tagged with the
 *    authenticated userId and deterministic `clientEventId="outlook:<id>"`.
 * 2. Empty delta — zero messages, deltaLink persisted, [Result.success].
 * 3. DeltaToken stale (HTTP 410) — [SyncCursorStore.clearCursor] called and [Result.retry]
 *    so the next attempt starts a full re-sync.
 * 4. Credential absent (null session / blank userId) — [Result.failure] without a Graph call.
 * 5. Network error — [Result.retry] with cursor untouched so the next run resumes.
 * 6. Unauthorized (401) — [Result.failure] (MSAL re-auth required in UI).
 *
 * Spec refs: ING-006, ING-012, ING-013, ING-015.
 */
@RunWith(RobolectricTestRunner::class)
class OutlookMailWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val msGraphClient: MsGraphClient = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val authRepository: AuthRepository = mockk()
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var worker: OutlookMailWorker

    private val fakeUserId = "user-uuid-outlook-1"
    private val fakeSession = SupabaseSession(
        accessToken = "access",
        refreshToken = "refresh",
        userId = fakeUserId,
        email = "alice@example.com",
        expiresAt = Instant.fromEpochMilliseconds(Long.MAX_VALUE),
    )

    private val msg1 = GraphMessage(
        id = "AAMkAGI1",
        internetMessageId = "<msg-1@outlook.com>",
        subject = "Hello",
        fromEmail = "bob@outlook.com",
        fromName = "Bob",
        bodyPreview = "body preview 1",
        receivedDateTime = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private val msg2 = GraphMessage(
        id = "AAMkAGI2",
        internetMessageId = "<msg-2@outlook.com>",
        subject = null,
        fromEmail = "carol@outlook.com",
        fromName = "Carol",
        bodyPreview = null,
        receivedDateTime = Instant.fromEpochMilliseconds(1_700_000_060_000),
    )

    @Before
    fun setUp() {
        worker = OutlookMailWorker(
            appContext = context,
            workerParams = workerParams,
            msGraphClient = msGraphClient,
            rawIngestionRepository = rawIngestionRepository,
            sourceStatusRepository = sourceStatusRepository,
            syncCursorStore = syncCursorStore,
            authRepository = authRepository,
            logger = logger,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        every { workerParams.runAttemptCount } returns 0
        coEvery { authRepository.currentSession() } returns fakeSession

        // Default: no stored cursor (cold start)
        every { syncCursorStore.observeCursor(OutlookMailWorker.CURSOR_KEY) } returns flowOf(null)

        // Default: insertLocalBatch succeeds
        coEvery { rawIngestionRepository.insertLocalBatch(any()) } returns
            BecalmResult.Success(listOf("id-1", "id-2"))
    }

    // ── T1: Happy path — 2 messages, deltaLink stored, success ───────────────

    @Test
    fun `doWork inserts messages and stores deltaLink on happy path`() = runTest {
        val deltaLink = "https://graph.microsoft.com/v1.0/me/messages/delta?deltatoken=final"
        coEvery { msGraphClient.messagesDelta(null) } returns BecalmResult.Success(
            GraphDeltaResponse(
                value = listOf(msg1, msg2),
                nextLink = null,
                deltaLink = deltaLink,
            ),
        )

        val insertedEntities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(insertedEntities)) } returns
            BecalmResult.Success(listOf("id-1", "id-2"))

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // Entity shape: 2 rows, deterministic clientEventIds with the "outlook:" prefix.
        assertEquals(2, insertedEntities.captured.size)
        assertEquals("outlook:${msg1.id}", insertedEntities.captured[0].clientEventId)
        assertEquals("outlook:${msg2.id}", insertedEntities.captured[1].clientEventId)
        assertEquals(SourceType.OUTLOOK_MAIL, insertedEntities.captured[0].sourceType)
        assertEquals(fakeUserId, insertedEntities.captured[0].userId)

        // deltaLink is persisted for the next sync pass.
        coVerify { syncCursorStore.setCursor(OutlookMailWorker.CURSOR_KEY, deltaLink) }
        coVerify { sourceStatusRepository.recordSyncSuccess(SourceType.OUTLOOK_MAIL, any()) }
    }

    // ── T2: Empty delta — deltaLink stored, success, no insert ───────────────

    @Test
    fun `doWork persists deltaLink when server returns empty delta page`() = runTest {
        val storedDelta = "https://graph.microsoft.com/v1.0/me/messages/delta?deltatoken=stored"
        every { syncCursorStore.observeCursor(OutlookMailWorker.CURSOR_KEY) } returns flowOf(storedDelta)

        val newDelta = "https://graph.microsoft.com/v1.0/me/messages/delta?deltatoken=new"
        coEvery { msGraphClient.messagesDelta(storedDelta) } returns BecalmResult.Success(
            GraphDeltaResponse(
                value = emptyList(),
                nextLink = null,
                deltaLink = newDelta,
            ),
        )

        val result = worker.doWork()

        assertEquals(Result.success(), result)
        coVerify(exactly = 0) { rawIngestionRepository.insertLocalBatch(any()) }
        coVerify { syncCursorStore.setCursor(OutlookMailWorker.CURSOR_KEY, newDelta) }
    }

    // ── T3: DeltaToken stale (HTTP 410) — clearCursor + retry ────────────────

    @Test
    fun `doWork clears cursor and returns retry on NotFound (HTTP 410)`() = runTest {
        val storedCursor = "https://graph.microsoft.com/v1.0/me/messages/delta?deltatoken=expired"
        every { syncCursorStore.observeCursor(OutlookMailWorker.CURSOR_KEY) } returns flowOf(storedCursor)

        coEvery { msGraphClient.messagesDelta(storedCursor) } returns
            BecalmResult.Failure(BecalmError.NotFound("delta token expired"))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        // The cursor MUST be cleared so the next retry starts a full re-sync.
        coVerify { syncCursorStore.clearCursor(OutlookMailWorker.CURSOR_KEY) }
        coVerify {
            sourceStatusRepository.recordSyncError(
                SourceType.OUTLOOK_MAIL,
                "NotFound",
                any(),
            )
        }
    }

    // ── T4: Credential absent (null session) → failure, no Graph call ────────

    @Test
    fun `doWork returns failure and records unauthorized when no session`() = runTest {
        coEvery { authRepository.currentSession() } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify(exactly = 0) { msGraphClient.messagesDelta(any()) }
        coVerify {
            sourceStatusRepository.recordSyncError(
                SourceType.OUTLOOK_MAIL,
                "unauthorized",
                any(),
            )
        }
    }

    // ── T5: Network error → retry, cursor untouched ──────────────────────────

    @Test
    fun `doWork returns retry on Network error without touching cursor`() = runTest {
        coEvery { msGraphClient.messagesDelta(null) } returns
            BecalmResult.Failure(BecalmError.Network(-1, "timeout"))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)
        // No cursor mutation on transient failure — next run resumes from the same position.
        coVerify(exactly = 0) {
            syncCursorStore.setCursor(OutlookMailWorker.CURSOR_KEY, any())
        }
        coVerify(exactly = 0) {
            syncCursorStore.clearCursor(OutlookMailWorker.CURSOR_KEY)
        }
    }

    // ── T6: Unauthorized (401) → Result.failure ──────────────────────────────

    @Test
    fun `doWork returns failure on Unauthorized error`() = runTest {
        coEvery { msGraphClient.messagesDelta(null) } returns
            BecalmResult.Failure(BecalmError.Unauthorized)

        val result = worker.doWork()

        assertEquals(Result.failure(), result)
        coVerify {
            sourceStatusRepository.recordSyncError(
                SourceType.OUTLOOK_MAIL,
                "Unauthorized",
                any(),
            )
        }
    }
}
