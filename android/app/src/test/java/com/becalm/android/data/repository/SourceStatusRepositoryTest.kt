package com.becalm.android.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.ImapCursorState
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.CalendarEventListResponse
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.CommitmentBatchRequestDto
import com.becalm.android.data.remote.dto.CommitmentBatchResponseDto
import com.becalm.android.data.remote.dto.CommitmentPatchDto
import com.becalm.android.data.remote.dto.PaginatedCommitmentsResponse
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.data.remote.dto.PersonCommitmentsResponse
import com.becalm.android.data.remote.dto.PersonEventsResponse
import com.becalm.android.data.remote.dto.PersonListResponse
import com.becalm.android.data.remote.dto.SingleCommitmentResponse
import com.becalm.android.data.remote.dto.SourceStatusItemDto
import com.becalm.android.data.remote.dto.SourceStatusResponseDto
import com.becalm.android.data.remote.dto.SourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Unit tests for [SourceStatusRepositoryImpl] covering the round6-plan § 6B.2 additions:
 *
 * 1. [SourceStatusRepositoryImpl.refreshFromServer] merges a 200-response into the per-source
 *    DataStore cache so [SourceStatusRepositoryImpl.observeAll] reflects server state.
 * 2. On HTTP 500 the returned [BecalmResult.Failure] is emitted but the local cache is preserved,
 *    i.e. the offline fallback remains authoritative (no erasure of previously-synced timestamps).
 * 3. On [IOException] the same invariant holds — transport failures never clobber local state.
 * 4. `runCatching`-removal regression: a [CancellationException] thrown from inside a repo
 *    operation propagates up the stack instead of being mapped to [BecalmError.Unknown] — the
 *    previous `runCatching` helper was not structured-concurrency-safe, and [rethrowIfCancellation]
 *    is now called from every catch block (see [SourceStatusRepositoryImpl.runOp]).
 *
 * Uses hand-written fakes (big-tech-rubric § J4 prefers fakes over MockK for repository
 * collaborators) and the shared [RecordingLogger] from `core/util/`.
 *
 * Spec refs: TDY-003, TDY-006, TDY-008, TDY-009, SMG-001.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SourceStatusRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val logger = RecordingLogger()
    private val userPrefs = FakePreferencesDataStore()
    private val api = FakeRailwayApi()
    private val cursorStore = FakeSyncCursorStore()

    private val repository: SourceStatusRepository = SourceStatusRepositoryImpl(
        cursorStore = cursorStore,
        userPrefs = userPrefs,
        api = api,
        ioDispatcher = testDispatcher,
        logger = logger,
    )

    // ── refreshFromServer 200 → 6 items merged; observeAll reflects each ──────

    @Test
    fun `refreshFromServer 200 merges 6 wire items into observable state`() = runTest(testDispatcher) {
        val serverAt = Instant.fromEpochMilliseconds(1_713_430_200_000L)
        api.sourceStatusResponder = {
            Response.success(
                SourceStatusResponseDto(
                    sources = listOf(
                        SourceStatusItemDto(SourceType.GMAIL, "synced", serverAt, null),
                        SourceStatusItemDto(SourceType.OUTLOOK_MAIL, "synced", serverAt, null),
                        SourceStatusItemDto(SourceType.NAVER_IMAP, "syncing", null, null),
                        SourceStatusItemDto(SourceType.DAUM_IMAP, "idle", null, null),
                        SourceStatusItemDto(SourceType.GOOGLE_CALENDAR, "error", serverAt, "auth expired"),
                        SourceStatusItemDto(SourceType.OUTLOOK_CALENDAR, "synced", serverAt, null),
                    ),
                ),
            )
        }

        val result = repository.refreshFromServer()

        assertTrue("expected Success but was $result", result is BecalmResult.Success)

        val statuses = repository.observeAll().first().associateBy { it.sourceType }
        assertEquals(
            "Gmail should be CONNECTED after merge",
            SourceConnectionStatus.CONNECTED,
            statuses[SourceType.GMAIL]?.status,
        )
        assertEquals(serverAt, statuses[SourceType.GMAIL]?.lastSyncedAt)
        assertEquals(
            "Naver should be SYNCING after merge",
            SourceConnectionStatus.SYNCING,
            statuses[SourceType.NAVER_IMAP]?.status,
        )
        assertEquals(
            "GCal should be ERROR after merge",
            SourceConnectionStatus.ERROR,
            statuses[SourceType.GOOGLE_CALENDAR]?.status,
        )
        assertEquals("auth expired", statuses[SourceType.GOOGLE_CALENDAR]?.errorMessage)
    }

    // ── refreshFromServer 500 → Failure; local state preserved ───────────────

    @Test
    fun `refreshFromServer HTTP 500 returns Network failure and does not erase local state`() =
        runTest(testDispatcher) {
            // Seed local cache with a previously-synced Gmail timestamp (offline fallback).
            val localAt = Instant.fromEpochMilliseconds(1_713_000_000_000L)
            repository.recordSyncSuccess(SourceType.GMAIL, localAt)

            api.sourceStatusResponder = {
                Response.error(500, """{"error":"internal"}""".toResponseBody(null))
            }

            val result = repository.refreshFromServer()

            assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
            val error = (result as BecalmResult.Failure).error
            assertTrue("expected Network error but was $error", error is BecalmError.Network)
            assertEquals(500, (error as BecalmError.Network).code)

            // Local state must still reflect the pre-refresh success — no erasure.
            val statusesAfter = repository.observeAll().first().associateBy { it.sourceType }
            assertEquals(
                "Gmail lastSyncedAt should be preserved after server 500",
                localAt,
                statusesAfter[SourceType.GMAIL]?.lastSyncedAt,
            )
            assertEquals(
                SourceConnectionStatus.CONNECTED,
                statusesAfter[SourceType.GMAIL]?.status,
            )
        }

    // ── refreshFromServer IOException → Failure; offline fallback survives ───

    @Test
    fun `refreshFromServer IOException returns Network failure and offline fallback remains authoritative`() =
        runTest(testDispatcher) {
            val localAt = Instant.fromEpochMilliseconds(1_713_000_000_000L)
            repository.recordSyncSuccess(SourceType.GMAIL, localAt)
            repository.recordSyncSuccess(SourceType.OUTLOOK_MAIL, localAt)

            api.sourceStatusResponder = { throw IOException("connection refused") }

            val result = repository.refreshFromServer()

            assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
            assertTrue(
                "expected BecalmError.Network but was ${(result as BecalmResult.Failure).error}",
                result.error is BecalmError.Network,
            )

            // Both locally-recorded sources still appear CONNECTED with their local timestamps.
            val statusesAfter = repository.observeAll().first().associateBy { it.sourceType }
            assertEquals(localAt, statusesAfter[SourceType.GMAIL]?.lastSyncedAt)
            assertEquals(localAt, statusesAfter[SourceType.OUTLOOK_MAIL]?.lastSyncedAt)
            assertNull(
                "unseeded source must remain null (no synthetic data from failed refresh)",
                statusesAfter[SourceType.DAUM_IMAP]?.lastSyncedAt,
            )
        }

    // ── runCatching removal regression: CancellationException must propagate ─

    @Test(expected = CancellationException::class)
    fun `CancellationException raised inside repo op is re-thrown not mapped to Unknown`() {
        // The `runOp` helper wraps the DataStore edit. If we force that edit to throw
        // a CancellationException, `rethrowIfCancellation` inside every catch branch must
        // re-throw it so structured-concurrency cancellation propagates out. Previously the
        // `runCatching` helper swallowed all Throwables, mapping cancellation to
        // BecalmError.Unknown — a dangerous silent-failure mode this regression pins.
        //
        // We use `runBlocking` (not `runTest`) because `runTest` intercepts uncaught
        // [CancellationException]s to reason about the TestScope's own cancellation. For a
        // raw re-throw assertion we want JUnit4's `@Test(expected = ...)` to observe the
        // exception without TestScope rewriting it.
        userPrefs.cancelNextEdit = true

        runBlocking {
            // Any write path that goes through `runOp` — pick the narrowest one.
            repository.recordSyncStart(SourceType.GMAIL)
            // If the CancellationException is mapped to a BecalmResult.Failure (old behaviour),
            // the test will finish normally and fail the @Test(expected = ...) contract.
        }
    }

    // ─── In-test fakes ───────────────────────────────────────────────────────

    /**
     * Hand-written in-memory [DataStore<Preferences>] fake with a forced-cancellation hook.
     *
     * Emits a single [MutableStateFlow] of [Preferences] snapshots through [data], applies
     * every [edit] transformation atomically via an internal mutex, and optionally throws
     * a [CancellationException] from inside the transform block when [cancelNextEdit] is
     * set. The cancellation hook exists solely so the runCatching-removal regression
     * ([`CancellationException raised inside repo op is re-thrown not mapped to Unknown`])
     * can simulate cooperative cancellation without depending on [kotlinx.coroutines.Job]
     * plumbing in the test harness.
     */
    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val stateFlow = MutableStateFlow<Preferences>(emptyPreferences())
        private val editMutex = Mutex()

        /** When true, the NEXT call to [updateData] throws [CancellationException]. */
        var cancelNextEdit: Boolean = false

        override val data: Flow<Preferences> = stateFlow

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            editMutex.withLock {
                if (cancelNextEdit) {
                    cancelNextEdit = false
                    throw CancellationException("forced cancellation inside DataStore edit")
                }
                // Copy current snapshot into a mutable instance so the transform sees the
                // caller's accumulated edits and can diff them atomically against the new state.
                val current = stateFlow.value
                val mutable: MutablePreferences = mutablePreferencesOf().apply {
                    current.asMap().forEach { (k, v) ->
                        @Suppress("UNCHECKED_CAST")
                        set(k as Preferences.Key<Any>, v)
                    }
                }
                val updated = transform(mutable)
                stateFlow.value = updated
                stateFlow.value
            }
    }

    /**
     * Hand-written [RailwayApi] fake — only [getSourceStatus] is wired. Every other endpoint
     * throws [NotImplementedError] so a test that incidentally touches another path fails loudly.
     */
    private class FakeRailwayApi : RailwayApi {
        var sourceStatusResponder: () -> Response<SourceStatusResponseDto> = {
            throw NotImplementedError("sourceStatusResponder not set")
        }

        override suspend fun getSourceStatus(): Response<SourceStatusResponseDto> =
            sourceStatusResponder()

        override suspend fun batchUploadRawEvents(
            idem: String,
            request: BatchUploadRequest,
        ): Response<BatchUploadResponse> = notImplemented()

        override suspend fun getCommitments(
            cursor: String?,
            limit: Int,
            since: String?,
            personRef: String?,
            direction: String?,
            actionState: String?,
        ): Response<PaginatedCommitmentsResponse> = notImplemented()

        override suspend fun patchCommitment(
            id: String,
            idem: String,
            request: PatchCommitmentRequest,
        ): Response<SingleCommitmentResponse> = notImplemented()

        override suspend fun updateCommitment(
            id: String,
            idem: String,
            request: CommitmentPatchDto,
        ): Response<SingleCommitmentResponse> = notImplemented()

        override suspend fun uploadCommitmentsBatch(
            idem: String,
            request: CommitmentBatchRequestDto,
        ): Response<CommitmentBatchResponseDto> = notImplemented()

        override suspend fun getCalendarEvents(
            cursor: String?,
            since: String?,
            limit: Int?,
        ): Response<CalendarEventListResponse> = notImplemented()

        override suspend fun syncCalendarEvents(): Response<CalendarSyncResponse> = notImplemented()

        override suspend fun getPersons(
            cursor: String?,
            limit: Int?,
            query: String?,
        ): Response<PersonListResponse> = notImplemented()

        override suspend fun getPersonEvents(
            personId: String,
            cursor: String?,
            limit: Int?,
        ): Response<PersonEventsResponse> = notImplemented()

        override suspend fun getPersonCommitments(
            personId: String,
            cursor: String?,
            limit: Int?,
        ): Response<PersonCommitmentsResponse> = notImplemented()

        private fun notImplemented(): Nothing =
            throw NotImplementedError("endpoint not wired in FakeRailwayApi")
    }

    /** Minimal [SyncCursorStore] fake — every method is a no-op; observers emit empty flows. */
    private class FakeSyncCursorStore : SyncCursorStore {
        override fun observeCursor(source: String): Flow<String?> = emptyFlow()
        override suspend fun setCursor(source: String, cursor: String?) {}
        override suspend fun clearCursor(source: String) {}
        override suspend fun clearAll() {}
        override fun observeGmailHistoryId(): Flow<Long?> = emptyFlow()
        override suspend fun setGmailHistoryId(historyId: Long?) {}
        override fun observeImapState(mailbox: String): Flow<ImapCursorState?> = emptyFlow()
        override suspend fun setImapState(mailbox: String, state: ImapCursorState?) {}
        override fun observeMediaStoreLastSeen(kind: String): Flow<Long?> = emptyFlow()
        override suspend fun setMediaStoreLastSeen(kind: String, epochMs: Long?) {}
        override suspend fun runOutlookMailCursorMigrationV2() {}
        override suspend fun runImapCursorMigrationV2() {}
    }
}
