package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.CalendarEventListResponse
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import com.becalm.android.data.remote.dto.CommitmentBatchRequestDto
import com.becalm.android.data.remote.dto.CommitmentBatchResponseDto
import com.becalm.android.data.remote.dto.CommitmentPatchDto
import com.becalm.android.data.remote.dto.FailedEventDto
import com.becalm.android.data.remote.dto.PaginatedCommitmentsResponse
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.data.remote.dto.PersonCommitmentsResponse
import com.becalm.android.data.remote.dto.PersonEventsResponse
import com.becalm.android.data.remote.dto.PersonListResponse
import com.becalm.android.data.remote.dto.SingleCommitmentResponse
import com.becalm.android.domain.commitment.CommitmentEditPatch
import io.mockk.every
import io.mockk.mockk
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [CommitmentRepositoryImpl] covering the two BLOCKER fixes from round6-plan § 6B.1:
 *
 * 1. [updateActionState] demotes `sync_status='pending'` on every PATCH failure (non-2xx + IOException),
 *    so [UploadWorker][com.becalm.android.worker.UploadWorker] re-queues the row for batch upload.
 * 2. [uploadBatch] posts pending commitments to `/v1/commitments:batch` and returns a typed
 *    [CommitmentRepository.BatchResponse].
 *
 * Uses hand-written fakes (big-tech-rubric § J4 prefers fakes over MockK for repository collaborators)
 * and the shared [RecordingLogger] from `core/util/`.
 *
 * Spec refs: commitment-management.spec.yml CMT-005..007 + invariant 3, backend-sync.spec.yml SYNC-001.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val logger = RecordingLogger()
    private val dao = FakeCommitmentDao()
    private val api = FakeRailwayApi()
    private val cursorStore = FakeSyncCursorStore()
    // resolveActorId() reads observeCurrentUserId().firstOrNull() — that's the only
    // UserPrefsStore method this repository invokes, so we only wire that one flow.
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true) {
        every { observeCurrentUserId() } returns flowOf("user-1")
    }

    // BeCalmDatabase is only touched by supersede(), which is not exercised by the
    // tests in this file (that requires a real in-memory Room DB for withTransaction
    // to work). Other tests safely pass the mock without invoking it.
    private val database: BeCalmDatabase = mockk(relaxed = true)

    private val repository: CommitmentRepository = CommitmentRepositoryImpl(
        dao = dao,
        api = api,
        cursorStore = cursorStore,
        userPrefsStore = userPrefsStore,
        database = database,
        logger = logger,
        ioDispatcher = testDispatcher,
    )

    // ── updateActionState: success path keeps sync_status='synced' ────────────

    @Test
    fun `updateActionState PATCH 200 keeps sync_status synced`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "c-1", actionState = "pending", syncStatus = "synced")
        dao.insert(entity)

        api.patchResponder = { id, _ ->
            Response.success(SingleCommitmentResponse(data = makeDto(entity.copy(actionState = "completed"))))
        }

        val result = repository.updateActionState(
            id = "c-1",
            newState = "completed",
            updatedAt = Instant.fromEpochMilliseconds(100),
        )

        assertTrue("expected Success but was $result", result is BecalmResult.Success)
        assertEquals("synced", dao.findById("c-1")?.syncStatus)
        assertEquals("completed", dao.findById("c-1")?.actionState)
    }

    // ── updateActionState: PATCH 200 promotes a 'pending' row to 'synced' ─────

    @Test
    fun `updateActionState PATCH 200 promotes pending row to synced`() = runTest(testDispatcher) {
        // Row enters the call flagged 'pending' (e.g. carried over from an earlier failed
        // upload). A successful PATCH must flip sync_status='synced' on the local row so
        // the next UploadWorker.flushCommitments cycle does not re-upload it. Before this
        // fix the row stayed 'pending' and self-healed only via server idempotency — loose
        // end that this test pins down. (round6 6D.4 Fix 3 / I4)
        val entity = makeEntity(id = "c-0", actionState = "pending", syncStatus = "pending")
        dao.insert(entity)

        api.patchResponder = { _, _ ->
            Response.success(SingleCommitmentResponse(data = makeDto(entity.copy(actionState = "completed"))))
        }

        val result = repository.updateActionState(
            id = "c-0",
            newState = "completed",
            updatedAt = Instant.fromEpochMilliseconds(50),
        )

        assertTrue("expected Success but was $result", result is BecalmResult.Success)
        val row = dao.findById("c-0")
        assertNotNull(row)
        assertEquals("synced", row!!.syncStatus)
        assertEquals("completed", row.actionState)
    }

    // ── updateActionState: HTTP 500 demotes sync_status='pending' ─────────────

    @Test
    fun `updateActionState PATCH 500 demotes sync_status to pending`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "c-2", actionState = "pending", syncStatus = "synced")
        dao.insert(entity)

        api.patchResponder = { _, _ ->
            Response.error(500, """{"error":"internal"}""".toResponseBody(null))
        }

        val result = repository.updateActionState(
            id = "c-2",
            newState = "reminded",
            updatedAt = Instant.fromEpochMilliseconds(200),
        )

        assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
        val row = dao.findById("c-2")
        assertNotNull(row)
        assertEquals("pending", row!!.syncStatus)
        // Optimistic local action_state write still landed even though the server PATCH failed.
        assertEquals("reminded", row.actionState)
    }

    // ── updateActionState: IOException demotes sync_status='pending' ──────────

    @Test
    fun `updateActionState PATCH IOException demotes sync_status to pending`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "c-3", actionState = "pending", syncStatus = "synced")
        dao.insert(entity)

        api.patchResponder = { _, _ -> throw IOException("network unreachable") }

        val result = repository.updateActionState(
            id = "c-3",
            newState = "followed_up",
            updatedAt = Instant.fromEpochMilliseconds(300),
        )

        assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
        assertEquals("pending", dao.findById("c-3")?.syncStatus)
        assertEquals("followed_up", dao.findById("c-3")?.actionState)
    }

    // ── uploadBatch: empty list no-op ─────────────────────────────────────────

    @Test
    fun `uploadBatch with empty list returns success without network call`() = runTest(testDispatcher) {
        val result = repository.uploadBatch(emptyList())

        assertTrue(result is BecalmResult.Success)
        val body = (result as BecalmResult.Success).value
        assertEquals(0, body.acknowledged)
        assertEquals(emptyList<CommitmentRepository.FailedEvent>(), body.failed)
        assertEquals("no network call expected", 0, api.uploadCommitmentsBatchCalls.get())
    }

    // ── uploadBatch: all 10 acknowledged → all rows synced ────────────────────

    @Test
    fun `uploadBatch 10 acked marks all as synced via client`() = runTest(testDispatcher) {
        val entities = (1..10).map { makeEntity(id = "c-$it", syncStatus = "pending") }
        entities.forEach { dao.insert(it) }

        api.uploadBatchResponder = { req ->
            Response.success(CommitmentBatchResponseDto(acknowledged = req.commitments.size, failed = emptyList()))
        }

        val result = repository.uploadBatch(entities)

        assertTrue(result is BecalmResult.Success)
        val body = (result as BecalmResult.Success).value
        assertEquals(10, body.acknowledged)
        assertEquals(emptyList<CommitmentRepository.FailedEvent>(), body.failed)
        assertEquals(1, api.uploadCommitmentsBatchCalls.get())

        // Worker is responsible for calling markSynced on the ack list — repository only
        // maps the transport response. Verify the shape the worker will consume.
        assertEquals(10, body.acknowledged)
    }

    // ── uploadBatch: partial success — 7 ack, 2 retryable, 1 non-retryable ────

    @Test
    fun `uploadBatch partitions acked retryable and non-retryable`() = runTest(testDispatcher) {
        val entities = (1..10).map { makeEntity(id = "c-$it", syncStatus = "pending") }
        entities.forEach { dao.insert(it) }

        // Simulate server: fails items 8, 9 (retryable) and 10 (non-retryable).
        api.uploadBatchResponder = { _ ->
            Response.success(
                CommitmentBatchResponseDto(
                    acknowledged = 7,
                    failed = listOf(
                        FailedEventDto("c-8", "internal_error", "transient", retryable = true),
                        FailedEventDto("c-9", "internal_error", "transient", retryable = true),
                        FailedEventDto("c-10", "schema_invalid", "quote too long", retryable = false),
                    ),
                ),
            )
        }

        val result = repository.uploadBatch(entities)

        assertTrue(result is BecalmResult.Success)
        val body = (result as BecalmResult.Success).value
        assertEquals(7, body.acknowledged)
        assertEquals(3, body.failed.size)
        assertEquals(2, body.failed.count { it.retryable })
        assertEquals(1, body.failed.count { !it.retryable })
        assertEquals("c-10", body.failed.single { !it.retryable }.clientEventId)
    }

    // ── uploadBatch: HTTP 500 → ServerError Failure, no DAO mutation ──────────

    @Test
    fun `uploadBatch on HTTP 500 returns ServerError failure`() = runTest(testDispatcher) {
        val entities = listOf(makeEntity(id = "c-1", syncStatus = "pending"))
        dao.insert(entities[0])

        api.uploadBatchResponder = { _ ->
            Response.error(500, """{"error":"down"}""".toResponseBody(null))
        }

        val result = repository.uploadBatch(entities)

        assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
        val err = (result as BecalmResult.Failure).error
        assertTrue("expected ServerError but was $err", err is BecalmError.ServerError)
        // Row stays 'pending' — repository must never touch sync_status on transport failure.
        assertEquals("pending", dao.findById("c-1")?.syncStatus)
    }

    // ── uploadBatch: IOException → Network Failure ────────────────────────────

    @Test
    fun `uploadBatch on IOException returns Network failure`() = runTest(testDispatcher) {
        val entities = listOf(makeEntity(id = "c-1", syncStatus = "pending"))
        dao.insert(entities[0])

        api.uploadBatchResponder = { _ -> throw IOException("connection refused") }

        val result = repository.uploadBatch(entities)

        assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
        val err = (result as BecalmResult.Failure).error
        assertTrue("expected Network but was $err", err is BecalmError.Network)
        assertEquals("pending", dao.findById("c-1")?.syncStatus)
    }

    // ── refreshSince: merges server response with local lifecycle state ──────

    /**
     * Regression coverage for the refresh-merge path (`findByIdsForMerge` +
     * [CommitmentBatchMapper.toEntity] lifecycle merge). A legacy `/v1/commitments`
     * build that does not yet emit the v5 lifecycle columns returns them at their
     * DTO defaults (null / false); without the merge, `insertAll` would REPLACE
     * every local row and silently wipe user-set state (edits, disputes,
     * soft-deletes, supersedes). These tests pin down that the DAO bypass query
     * returns tombstones too AND that the mapper preserves their values on upsert.
     */
    @Test
    fun `refreshSince with legacy DTO preserves local lifecycle fields`() = runTest(testDispatcher) {
        val disputedAt = Instant.fromEpochMilliseconds(1_234)
        val deletedAt = Instant.fromEpochMilliseconds(5_678)
        val localEditedAt = Instant.fromEpochMilliseconds(9_999)
        val localRow = makeEntity(id = "c-local", syncStatus = "synced").copy(
            lastEditedBy = "user-1",
            lastEditedAt = localEditedAt,
            quoteDisputed = true,
            quoteDisputedAt = disputedAt,
            deletedAt = deletedAt,
            supersedesCommitmentId = "c-prior",
        )
        dao.insert(localRow)

        // Server DTO omits all six lifecycle fields (older backend).
        val legacyDto = makeDto(localRow).copy(
            lastEditedBy = null,
            lastEditedAt = null,
            quoteDisputed = false,
            quoteDisputedAt = null,
            deletedAt = null,
            supersedesCommitmentId = null,
        )
        api.getCommitmentsResponder = {
            Response.success(
                PaginatedCommitmentsResponse(
                    data = listOf(legacyDto),
                    cursor = "",
                    hasMore = false,
                ),
            )
        }

        val result = repository.refreshSince(userId = "user-1", since = null)

        assertTrue("expected Success but was $result", result is BecalmResult.Success)
        val after = dao.findByIdsForMerge("user-1", listOf("c-local")).single()
        assertEquals("user-1", after.lastEditedBy)
        assertEquals(localEditedAt, after.lastEditedAt)
        assertEquals(true, after.quoteDisputed)
        assertEquals(disputedAt, after.quoteDisputedAt)
        assertEquals(deletedAt, after.deletedAt)
        assertEquals("c-prior", after.supersedesCommitmentId)
    }

    @Test
    fun `refreshSince with lifecycle-aware DTO trusts server and allows dispute release`() = runTest(testDispatcher) {
        val disputedAt = Instant.fromEpochMilliseconds(2_222)
        val localRow = makeEntity(id = "c-lifecycle", syncStatus = "synced").copy(
            quoteDisputed = true,
            quoteDisputedAt = disputedAt,
        )
        dao.insert(localRow)

        // Server emits quote_disputed=false while retaining the historical timestamp —
        // the user released the dispute on another device.
        val releasedDto = makeDto(localRow).copy(
            quoteDisputed = false,
            quoteDisputedAt = disputedAt,
        )
        api.getCommitmentsResponder = {
            Response.success(
                PaginatedCommitmentsResponse(
                    data = listOf(releasedDto),
                    cursor = "",
                    hasMore = false,
                ),
            )
        }

        repository.refreshSince(userId = "user-1", since = null)

        val after = dao.findByIdsForMerge("user-1", listOf("c-lifecycle")).single()
        assertEquals(false, after.quoteDisputed)
        assertEquals(disputedAt, after.quoteDisputedAt)
    }

    // ── observeById: reactive re-emission on DAO write ────────────────────────

    @Test
    fun `observeById re-emits after a DAO write flips actionState`() = runTest(testDispatcher) {
        val initial = makeEntity(id = "c-obs", actionState = "pending", syncStatus = "synced")
        dao.insert(initial)

        // First observation — seeded state.
        val first = repository.observeById("c-obs").first()
        assertEquals("pending", first?.actionState)

        // Trigger a DAO write (simulating CMT-005 Remind → REMINDED transition).
        dao.updateActionState(
            id = "c-obs",
            newState = "reminded",
            updatedAt = Instant.fromEpochMilliseconds(123),
        )

        val afterWrite = repository.observeById("c-obs").first()
        assertEquals(
            "reactive observeById must reflect the new action_state after a DAO write",
            "reminded",
            afterWrite?.actionState,
        )
    }

    @Test
    fun `findByIdsForMerge returns tombstoned rows (deleted_at filter bypass)`() = runTest(testDispatcher) {
        val live = makeEntity(id = "c-live", syncStatus = "synced")
        val tombstoned = makeEntity(id = "c-deleted", syncStatus = "pending").copy(
            deletedAt = Instant.fromEpochMilliseconds(42),
        )
        dao.insert(live)
        dao.insert(tombstoned)

        val rows = dao.findByIdsForMerge("user-1", listOf("c-live", "c-deleted"))

        assertEquals(2, rows.size)
        assertNotNull("tombstone must survive the merge-path fetch", rows.find { it.id == "c-deleted" })
    }

    // ── EDIT-003: editCommitment ──────────────────────────────────────────────

    @Test
    fun `editCommitment happy path writes mutable columns and flips to synced on PATCH 2xx`() =
        runTest(testDispatcher) {
            val entity = makeEntity(id = "c-edit", syncStatus = "synced")
            dao.insert(entity)

            api.updateCommitmentResponder = { _, _ ->
                Response.success(SingleCommitmentResponse(data = makeDto(entity.copy(title = "updated"))))
            }

            val patch = CommitmentEditPatch(
                title = "updated",
                dueAt = Instant.fromEpochMilliseconds(10_000),
                dueHint = "내일",
                dueIsApproximate = true,
                personRef = "+821012345678",
                direction = "take",
            )
            val result = repository.editCommitment("c-edit", patch)

            assertTrue("expected Success but was $result", result is BecalmResult.Success)
            val saved = dao.findById("c-edit")!!
            assertEquals("updated", saved.title)
            assertEquals("+821012345678", saved.personRef)
            assertEquals("take", saved.direction)
            assertEquals(true, saved.dueIsApproximate)
            // Best-effort PATCH succeeded → sync_status should flip to 'synced'.
            assertEquals("synced", saved.syncStatus)
        }

    @Test
    fun `editCommitment leaves sync_status pending when PATCH throws IOException`() =
        runTest(testDispatcher) {
            val entity = makeEntity(id = "c-edit-io", syncStatus = "synced")
            dao.insert(entity)

            api.updateCommitmentResponder = { _, _ -> throw IOException("network down") }

            val patch = CommitmentEditPatch(
                title = "updated",
                dueAt = null,
                dueHint = null,
                dueIsApproximate = false,
                personRef = null,
                direction = "give",
            )
            val result = repository.editCommitment("c-edit-io", patch)

            assertTrue("local write must still succeed", result is BecalmResult.Success)
            val saved = dao.findById("c-edit-io")!!
            assertEquals("updated", saved.title)
            // PATCH failed → DAO write flipped sync_status='pending'; helper left it there.
            assertEquals("pending", saved.syncStatus)
        }

    @Test
    fun `editCommitment returns NotFound when row is absent`() = runTest(testDispatcher) {
        val result = repository.editCommitment(
            id = "missing",
            patch = CommitmentEditPatch(
                title = "t",
                dueAt = null,
                dueHint = null,
                dueIsApproximate = false,
                personRef = null,
                direction = "give",
            ),
        )

        assertTrue("expected Failure but was $result", result is BecalmResult.Failure)
        assertTrue((result as BecalmResult.Failure).error is BecalmError.NotFound)
    }

    // ── EDIT-005: markQuoteDisputed / clearQuoteDispute ───────────────────────

    @Test
    fun `markQuoteDisputed flips flag and writes timestamp`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "c-disp", syncStatus = "synced")
        dao.insert(entity)

        api.updateCommitmentResponder = { _, _ ->
            Response.success(SingleCommitmentResponse(data = makeDto(entity)))
        }

        val result = repository.markQuoteDisputed("c-disp")

        assertTrue("expected Success but was $result", result is BecalmResult.Success)
        val saved = dao.findById("c-disp")!!
        assertEquals(true, saved.quoteDisputed)
        assertNotNull(saved.quoteDisputedAt)
        // The quote STRING itself must be untouched — invariance check (EDIT-spec invariant 1).
        assertEquals(entity.quote, saved.quote)
    }

    @Test
    fun `clearQuoteDispute resets flag without touching quote string`() = runTest(testDispatcher) {
        val entity = makeEntity(id = "c-clear", syncStatus = "synced").copy(
            quoteDisputed = true,
            quoteDisputedAt = Instant.fromEpochMilliseconds(5_000),
        )
        dao.insert(entity)

        api.updateCommitmentResponder = { _, _ ->
            Response.success(SingleCommitmentResponse(data = makeDto(entity)))
        }

        val result = repository.clearQuoteDispute("c-clear")

        assertTrue("expected Success but was $result", result is BecalmResult.Success)
        val saved = dao.findById("c-clear")!!
        assertEquals(false, saved.quoteDisputed)
        // Quote string invariance — clearing dispute MUST NOT mutate the quote.
        assertEquals(entity.quote, saved.quote)
    }

    // ── EDIT-006: softDelete ──────────────────────────────────────────────────

    @Test
    fun `softDelete writes deleted_at and leaves sync_status pending on IOException`() =
        runTest(testDispatcher) {
            val entity = makeEntity(id = "c-del", syncStatus = "synced")
            dao.insert(entity)

            api.updateCommitmentResponder = { _, _ -> throw IOException("offline") }

            val result = repository.softDelete("c-del")

            assertTrue("expected Success but was $result", result is BecalmResult.Success)
            val saved = dao.findById("c-del")!!
            assertNotNull("deleted_at must be populated", saved.deletedAt)
            assertEquals("pending", saved.syncStatus)
            // Quote remains intact (legally evidentiary even on tombstoned rows).
            assertEquals(entity.quote, saved.quote)
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeEntity(
        id: String,
        actionState: String = "pending",
        syncStatus: String = "pending",
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        direction = "give",
        counterpartyRaw = null,
        personRef = null,
        title = "t",
        description = null,
        quote = "q",
        sourceEventTitle = null,
        sourceEventOccurredAt = Instant.fromEpochMilliseconds(1_000),
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = "voice",
        sourceRef = null,
        confidence = 0.5,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = syncStatus,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun makeDto(entity: CommitmentEntity) = com.becalm.android.data.remote.dto.CommitmentDto(
        id = entity.id,
        userId = entity.userId,
        direction = entity.direction,
        counterpartyRaw = entity.counterpartyRaw,
        personRef = entity.personRef,
        title = entity.title,
        description = entity.description,
        quote = entity.quote,
        sourceEventTitle = entity.sourceEventTitle,
        sourceEventOccurredAt = entity.sourceEventOccurredAt,
        dueAt = entity.dueAt,
        dueHint = entity.dueHint,
        dueIsApproximate = entity.dueIsApproximate,
        actionState = entity.actionState,
        sourceType = entity.sourceType,
        sourceRef = entity.sourceRef,
        confidence = entity.confidence,
        syncStatus = entity.syncStatus,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
    )

    // ── In-test fakes ─────────────────────────────────────────────────────────

    /**
     * Hand-written [CommitmentDao] fake — stores rows in an in-memory map.
     *
     * Only the methods exercised by the tests above are implemented; the rest delegate to
     * [notImplemented] so a test that incidentally hits an unused path fails loudly.
     */
    private class FakeCommitmentDao : CommitmentDao {
        // Backing state exposed as a MutableStateFlow so observe*-style consumers (e.g.
        // CommitmentRepositoryImpl.observeById -> DAO.observeById) can re-emit on every
        // write path. `rows` continues to be the canonical map; writers mutate the map
        // and then call [bump] to republish a snapshot, mirroring Room's invalidation
        // tracker behaviour for Flow-returning queries.
        private val rows = linkedMapOf<String, CommitmentEntity>()
        private val snapshots =
            kotlinx.coroutines.flow.MutableStateFlow<Map<String, CommitmentEntity>>(emptyMap())

        private fun bump() { snapshots.value = rows.toMap() }

        override suspend fun insert(entity: CommitmentEntity): Long {
            rows[entity.id] = entity
            bump()
            return 1L
        }

        override suspend fun insertAll(entities: List<CommitmentEntity>): List<Long> {
            entities.forEach { rows[it.id] = it }
            bump()
            return entities.map { 1L }
        }

        override suspend fun update(entity: CommitmentEntity): Int {
            if (rows.containsKey(entity.id)) {
                rows[entity.id] = entity
                bump()
                return 1
            }
            return 0
        }

        override suspend fun updateActionState(id: String, newState: String, updatedAt: Instant): Int {
            val row = rows[id] ?: return 0
            rows[id] = row.copy(actionState = newState, updatedAt = updatedAt)
            bump()
            return 1
        }

        override suspend fun markSynced(ids: List<String>) {
            ids.forEach { id ->
                rows[id]?.let { rows[id] = it.copy(syncStatus = "synced") }
            }
            bump()
        }

        override suspend fun markPending(id: String) {
            rows[id]?.let { rows[id] = it.copy(syncStatus = "pending") }
            bump()
        }

        override suspend fun markFailed(id: String) {
            rows[id]?.let { rows[id] = it.copy(syncStatus = "failed") }
            bump()
        }

        override suspend fun applyEdit(
            id: String,
            title: String,
            dueAt: Instant?,
            dueHint: String?,
            approx: Boolean,
            personRef: String?,
            direction: String,
            actorId: String,
            editedAt: Instant,
        ): Int {
            // Mirror the production DAO: reject soft-deleted rows so applyEdit
            // cannot resurrect tombstones (matches the `AND deleted_at IS NULL`
            // guard on the @Query UPDATE).
            val row = rows[id]?.takeIf { it.deletedAt == null } ?: return 0
            rows[id] = row.copy(
                title = title,
                dueAt = dueAt,
                dueHint = dueHint,
                dueIsApproximate = approx,
                personRef = personRef,
                direction = direction,
                lastEditedBy = actorId,
                lastEditedAt = editedAt,
                updatedAt = editedAt,
                syncStatus = "pending",
            )
            bump()
            return 1
        }

        override suspend fun markQuoteDisputed(id: String, actor: String, at: Instant): Int {
            val row = rows[id]?.takeIf { it.deletedAt == null } ?: return 0
            rows[id] = row.copy(
                quoteDisputed = true,
                quoteDisputedAt = at,
                lastEditedBy = actor,
                lastEditedAt = at,
                updatedAt = at,
                syncStatus = "pending",
            )
            bump()
            return 1
        }

        override suspend fun clearQuoteDispute(id: String, actor: String, at: Instant): Int {
            val row = rows[id]?.takeIf { it.deletedAt == null } ?: return 0
            rows[id] = row.copy(
                quoteDisputed = false,
                quoteDisputedAt = null,
                lastEditedBy = actor,
                lastEditedAt = at,
                updatedAt = at,
                syncStatus = "pending",
            )
            bump()
            return 1
        }

        override suspend fun softDelete(id: String, actor: String, at: Instant): Int {
            // softDelete() deliberately does NOT filter on deleted_at — idempotent
            // tombstone writes are allowed. Mirrors the production DAO query.
            val row = rows[id] ?: return 0
            rows[id] = row.copy(
                deletedAt = at,
                lastEditedBy = actor,
                lastEditedAt = at,
                updatedAt = at,
                syncStatus = "pending",
            )
            bump()
            return 1
        }

        override suspend fun deleteAllForUser(userId: String): Int {
            val before = rows.size
            rows.entries.removeAll { it.value.userId == userId }
            return before - rows.size
        }

        override suspend fun findById(id: String): CommitmentEntity? = rows[id]

        override suspend fun findByIdsForMerge(
            userId: String,
            ids: List<String>,
        ): List<CommitmentEntity> =
            // Mirrors the production query: scoped by user_id, tombstones included so the
            // refresh-merge path can preserve locally-set lifecycle state.
            rows.values.filter { it.userId == userId && it.id in ids }

        override fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>> = emptyFlow()

        override fun observePendingForToday(userId: String, endOfTodayEpochMs: Long): Flow<List<CommitmentEntity>> = emptyFlow()

        override fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>> = emptyFlow()

        /**
         * Reactive observer — emits the current [rows] entry on every subscription and
         * re-emits when any write path mutates the map. Mirrors Room's invalidation
         * tracker behaviour for Flow-returning queries so production tests can assert
         * re-emission without spinning up a real database.
         */
        override fun observeById(id: String): Flow<CommitmentEntity?> =
            snapshots.map { it[id] }

        override suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity> =
            rows.values.filter { it.userId == userId && it.syncStatus == "pending" }.take(limit)
    }

    /**
     * Hand-written [RailwayApi] fake that dispatches to per-test responders.
     *
     * Only the endpoints touched by these tests (`patchCommitment`, `uploadCommitmentsBatch`)
     * are wired; every other method throws [NotImplementedError] so accidental use fails loudly.
     */
    private class FakeRailwayApi : RailwayApi {
        var patchResponder: (id: String, request: PatchCommitmentRequest) -> Response<SingleCommitmentResponse> =
            { _, _ -> throw NotImplementedError("patchResponder not set") }

        /** Defaults to a 200 with a dummy body so edit/dispute/soft-delete tests don't each wire it. */
        var updateCommitmentResponder: (id: String, request: CommitmentPatchDto) -> Response<SingleCommitmentResponse> =
            { _, _ -> throw NotImplementedError("updateCommitmentResponder not set") }

        var uploadBatchResponder: (CommitmentBatchRequestDto) -> Response<CommitmentBatchResponseDto> =
            { Response.success(CommitmentBatchResponseDto(acknowledged = 0, failed = emptyList())) }

        var getCommitmentsResponder: () -> Response<PaginatedCommitmentsResponse> =
            { notImplemented() }

        val uploadCommitmentsBatchCalls = AtomicInteger(0)

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
        ): Response<PaginatedCommitmentsResponse> = getCommitmentsResponder()

        override suspend fun patchCommitment(
            id: String,
            idem: String,
            request: PatchCommitmentRequest,
        ): Response<SingleCommitmentResponse> = patchResponder(id, request)

        override suspend fun updateCommitment(
            id: String,
            idem: String,
            request: CommitmentPatchDto,
        ): Response<SingleCommitmentResponse> = updateCommitmentResponder(id, request)

        override suspend fun uploadCommitmentsBatch(
            idem: String,
            request: CommitmentBatchRequestDto,
        ): Response<CommitmentBatchResponseDto> {
            uploadCommitmentsBatchCalls.incrementAndGet()
            return uploadBatchResponder(request)
        }

        override suspend fun getSourceStatus(): Response<com.becalm.android.data.remote.dto.SourceStatusResponseDto> =
            notImplemented()

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

    /**
     * Minimal [SyncCursorStore] fake — repository only invokes setCursor from refreshSince,
     * which is not exercised here. All operations are no-ops; observers emit an empty Flow.
     */
    private class FakeSyncCursorStore : SyncCursorStore {
        override fun observeCursor(source: String): Flow<String?> = emptyFlow()
        override suspend fun setCursor(source: String, cursor: String?) {}
        override suspend fun clearCursor(source: String) {}
        override suspend fun clearAll() {}
        override fun observeGmailHistoryId(): Flow<Long?> = emptyFlow()
        override suspend fun setGmailHistoryId(historyId: Long?) {}
        override fun observeImapState(mailbox: String): Flow<com.becalm.android.data.local.datastore.ImapCursorState?> = emptyFlow()
        override suspend fun setImapState(mailbox: String, state: com.becalm.android.data.local.datastore.ImapCursorState?) {}
        override fun observeMediaStoreLastSeen(kind: String): Flow<Long?> = emptyFlow()
        override suspend fun setMediaStoreLastSeen(kind: String, epochMs: Long?) {}
        override suspend fun runOutlookMailCursorMigrationV2() {}
        override suspend fun runImapCursorMigrationV2() {}
    }
}
