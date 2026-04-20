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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ImapDaumWorker] (SP-27b, mirror of [ImapNaverWorker]).
 *
 * Test cases:
 * 1. Happy path — 2 fetched messages produce 2 RawIngestionEventEntity inserts with
 *    source_type='daum_imap', deterministic client_event_ids, and cursor advance.
 * 2. Empty folder — cursor is still written with the server's fresh UIDVALIDITY/UIDNEXT
 *    pair and [SourceStatusRepository.recordSyncSuccess] is called.
 * 3. UIDVALIDITY change — worker passes the stale value to [ImapClient.fetchSince] (which
 *    handles the bounded full-resync internally) and overwrites the cursor with the
 *    server's fresh UIDVALIDITY, implicitly invalidating the stale pair. Asserts the
 *    new-cursor save matches the server values.
 * 4. Credentials absent — [Result.failure] (same as Naver: missing IMAP creds is terminal).
 * 5. Network error — [Result.retry] plus [SourceStatusRepository.recordSyncError].
 *
 * Spec refs: ING-008, ING-012, ING-013, ING-015, CRIT-01, SP-27b.
 */
@RunWith(RobolectricTestRunner::class)
class ImapDaumWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val userPrefs: DataStore<Preferences> = mockk()
    private val imapCredentialStore: ImapCredentialStore = mockk()
    private val syncCursorStore: SyncCursorStore = mockk(relaxed = true)
    private val imapClient: ImapClient = mockk()
    private val rawIngestionRepository: RawIngestionRepository = mockk()
    private val sourceStatusRepository: SourceStatusRepository = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    private lateinit var worker: ImapDaumWorker

    private val fakeUserId = "user-uuid-daum-1"

    private val fakeCredentials = ImapCredentials(
        username = "alice@daum.net",
        appPassword = "app-pass-1234",
        host = ImapDaumWorker.DAUM_IMAP_HOST,
        port = ImapDaumWorker.DAUM_IMAP_PORT,
    )

    private val fakeMessage1 = ImapMessage(
        uid = 101L,
        uidValidity = 42L,
        messageId = "<a@daum.net>",
        subject = "subject-1",
        fromEmail = "bob@daum.net",
        fromDisplayName = "Bob",
        bodyPreview = "body preview 1",
        sentAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
    )

    private val fakeMessage2 = ImapMessage(
        uid = 102L,
        uidValidity = 42L,
        messageId = "<b@daum.net>",
        subject = "subject-2",
        fromEmail = "carol@daum.net",
        fromDisplayName = "Carol",
        bodyPreview = "body preview 2",
        sentAt = Instant.fromEpochMilliseconds(1_700_000_060_000),
    )

    @Before
    fun setUp() {
        worker = ImapDaumWorker(
            appContext = context,
            workerParams = workerParams,
            userPrefs = userPrefs,
            imapCredentialStore = imapCredentialStore,
            syncCursorStore = syncCursorStore,
            imapClient = imapClient,
            rawIngestionRepository = rawIngestionRepository,
            sourceStatusRepository = sourceStatusRepository,
            logger = logger,
        )

        every { workerParams.runAttemptCount } returns 0

        // Default: userPrefs emits a current_user_id
        val prefs = mutablePreferencesOf().apply {
            set(stringPreferencesKey("current_user_id"), fakeUserId)
        }
        every { userPrefs.data } returns flowOf(prefs)

        // Default: credentials present
        coEvery { imapCredentialStore.getCredentials() } returns fakeCredentials

        // Default: no stored cursor (first run)
        every { syncCursorStore.observeImapState(ImapDaumWorker.MAILBOX_DAUM) } returns flowOf(null)
    }

    // ── T1: Happy path — 2 new messages → 2 inserts + cursor advance ────────

    @Test
    fun `doWork inserts 2 entities with deterministic clientEventIds and advances cursor`() = runTest {
        coEvery {
            imapClient.fetchSince(
                host = ImapDaumWorker.DAUM_IMAP_HOST,
                port = ImapDaumWorker.DAUM_IMAP_PORT,
                user = fakeCredentials.username,
                password = fakeCredentials.appPassword,
                uidValidity = null,
                uidNext = null,
                maxMessages = ImapDaumWorker.MAX_MESSAGES_PER_RUN,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(fakeMessage1, fakeMessage2),
                newUidValidity = 42L,
                newUidNext = 103L,
            ),
        )

        val insertedEntities = slot<List<RawIngestionEventEntity>>()
        coEvery { rawIngestionRepository.insertLocalBatch(capture(insertedEntities)) } returns
            BecalmResult.Success(listOf("id-1", "id-2"))

        val savedState = slot<ImapCursorState?>()
        coEvery {
            syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM, capture(savedState))
        } returns Unit

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // Entity shape: 2 rows, correct source_type, deterministic client_event_ids
        assertEquals(2, insertedEntities.captured.size)
        assertEquals(SourceType.DAUM_IMAP, insertedEntities.captured[0].sourceType)
        assertEquals(SourceType.DAUM_IMAP, insertedEntities.captured[1].sourceType)
        assertEquals("daum:101:42", insertedEntities.captured[0].clientEventId)
        assertEquals("daum:102:42", insertedEntities.captured[1].clientEventId)
        assertEquals(fakeUserId, insertedEntities.captured[0].userId)
        assertEquals(fakeUserId, insertedEntities.captured[1].userId)

        // Cursor advance: uidValidity=42 and lastSeenUid=serverUidNext-1=102
        assertEquals(42L, savedState.captured?.uidValidity)
        assertEquals(102L, savedState.captured?.lastSeenUid)

        coVerify { sourceStatusRepository.recordSyncSuccess(SourceType.DAUM_IMAP, any()) }
    }

    // ── T2: Empty folder — cursor still advances, success recorded ───────────

    @Test
    fun `doWork records success and writes server cursor when folder is empty`() = runTest {
        // Stored cursor: uidValidity=42, lastSeenUid=200 ⇒ fetchFromUid=201
        every { syncCursorStore.observeImapState(ImapDaumWorker.MAILBOX_DAUM) } returns
            flowOf(ImapCursorState(uidValidity = 42L, lastSeenUid = 200L))

        coEvery {
            imapClient.fetchSince(
                host = ImapDaumWorker.DAUM_IMAP_HOST,
                port = ImapDaumWorker.DAUM_IMAP_PORT,
                user = fakeCredentials.username,
                password = fakeCredentials.appPassword,
                uidValidity = 42L,
                uidNext = 201L,
                maxMessages = ImapDaumWorker.MAX_MESSAGES_PER_RUN,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = emptyList(),
                newUidValidity = 42L,
                newUidNext = 201L,
            ),
        )

        val savedState = slot<ImapCursorState?>()
        coEvery {
            syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM, capture(savedState))
        } returns Unit

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // No insert attempted for an empty fetch
        coVerify(exactly = 0) { rawIngestionRepository.insertLocalBatch(any()) }

        // Cursor is still written: uidValidity unchanged, lastSeenUid = newUidNext - 1 = 200
        assertEquals(42L, savedState.captured?.uidValidity)
        assertEquals(200L, savedState.captured?.lastSeenUid)

        coVerify { sourceStatusRepository.recordSyncSuccess(SourceType.DAUM_IMAP, any()) }
    }

    // ── T3: UIDVALIDITY change — stale pair overwritten with server's fresh pair ─

    @Test
    fun `doWork overwrites stale cursor when server returns new UIDVALIDITY`() = runTest {
        // Stored cursor with stale UIDVALIDITY=10
        every { syncCursorStore.observeImapState(ImapDaumWorker.MAILBOX_DAUM) } returns
            flowOf(ImapCursorState(uidValidity = 10L, lastSeenUid = 500L))

        // Worker passes the stale pair to the client. The client internally detects the
        // UIDVALIDITY mismatch and performs a bounded resync from UID 1 capped at
        // MAX_MESSAGES_PER_RUN (ING-013). Emulate by returning a fresh UIDVALIDITY.
        coEvery {
            imapClient.fetchSince(
                host = ImapDaumWorker.DAUM_IMAP_HOST,
                port = ImapDaumWorker.DAUM_IMAP_PORT,
                user = fakeCredentials.username,
                password = fakeCredentials.appPassword,
                uidValidity = 10L,
                uidNext = 501L,
                maxMessages = ImapDaumWorker.MAX_MESSAGES_PER_RUN,
            )
        } returns BecalmResult.Success(
            ImapFetchResult(
                messages = listOf(fakeMessage1.copy(uidValidity = 99L)),
                newUidValidity = 99L,
                newUidNext = 102L,
            ),
        )

        coEvery { rawIngestionRepository.insertLocalBatch(any()) } returns
            BecalmResult.Success(listOf("id-1"))

        val savedState = slot<ImapCursorState?>()
        coEvery {
            syncCursorStore.setImapState(ImapDaumWorker.MAILBOX_DAUM, capture(savedState))
        } returns Unit

        val result = worker.doWork()

        assertEquals(Result.success(), result)

        // Stale (uidValidity=10, lastSeenUid=500) is overwritten atomically with the
        // server's fresh pair. This is the mirror-of-Naver cursor-invalidation path —
        // setImapState supersedes the stale tuple; no explicit clearCursor call needed.
        assertEquals(99L, savedState.captured?.uidValidity)
        assertEquals(101L, savedState.captured?.lastSeenUid)
        assertTrue(
            "stored uidValidity must not equal the stale pre-resync value",
            savedState.captured?.uidValidity != 10L,
        )
    }

    // ── T4: Credentials absent → Result.failure ──────────────────────────────

    @Test
    fun `doWork returns failure when credentials are absent`() = runTest {
        coEvery { imapCredentialStore.getCredentials() } returns null

        val result = worker.doWork()

        assertEquals(Result.failure(), result)

        coVerify(exactly = 0) {
            imapClient.fetchSince(any(), any(), any(), any(), any(), any(), any())
        }
        coVerify(exactly = 0) { rawIngestionRepository.insertLocalBatch(any()) }
    }

    // ── T5: Network error → Result.retry + recordSyncError ───────────────────

    @Test
    fun `doWork returns retry and records sync error on Network failure`() = runTest {
        coEvery {
            imapClient.fetchSince(
                host = ImapDaumWorker.DAUM_IMAP_HOST,
                port = ImapDaumWorker.DAUM_IMAP_PORT,
                user = fakeCredentials.username,
                password = fakeCredentials.appPassword,
                uidValidity = null,
                uidNext = null,
                maxMessages = ImapDaumWorker.MAX_MESSAGES_PER_RUN,
            )
        } returns BecalmResult.Failure(BecalmError.Network(-1, "IMAP connect failed"))

        val result = worker.doWork()

        assertEquals(Result.retry(), result)

        coVerify {
            sourceStatusRepository.recordSyncError(
                sourceType = SourceType.DAUM_IMAP,
                error = any(),
                at = any(),
            )
        }
        // No cursor advance / insert on failure
        coVerify(exactly = 0) { rawIngestionRepository.insertLocalBatch(any()) }
        coVerify(exactly = 0) { syncCursorStore.setImapState(any(), any()) }
    }

    // ── T6: Unauthorized → Result.failure (mirrors Naver's auth-terminal path) ─

    @Test
    fun `doWork returns failure on Unauthorized error`() = runTest {
        coEvery {
            imapClient.fetchSince(any(), any(), any(), any(), any(), any(), any())
        } returns BecalmResult.Failure(BecalmError.Unauthorized)

        val result = worker.doWork()

        assertEquals(Result.failure(), result)

        coVerify {
            sourceStatusRepository.recordSyncError(
                sourceType = SourceType.DAUM_IMAP,
                error = any(),
                at = any(),
            )
        }
    }
}
