package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.CommitmentManagementRow
import com.becalm.android.data.local.db.dao.TodayCommitmentRow
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.domain.commitment.CommitmentEditPatch
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.CommitmentStateMachine
import com.becalm.android.domain.commitment.ManualCommitmentInput
import com.becalm.android.domain.commitment.TransitionError
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Manages the local Room cache of commitments and synchronises with Railway.
 *
 * Reactive reads delegate to [com.becalm.android.data.local.db.dao.CommitmentDao]
 * (Room re-emits on every matching write). Remote operations delegate to
 * [com.becalm.android.data.remote.api.RailwayApi] and write results back to Room.
 */
public interface CommitmentRepository {

    // ── Reactive reads (local Room) ──────────────────────────────────────────

    /**
     * Emits every commitment for [userId] regardless of action_state or commitment_state,
     * re-emitting on any table change.
     *
     * Drives the CommitmentManagementScreen list so that a single Room subscription covers
     * all filter tabs without a combine chain over fixed action_state strings.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     */
    public fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>>

    /**
     * Emits display-safe commitment rows for management UI without loading quote,
     * description, sync metadata, or a separate enrichment map.
     */
    public fun observeManagementRowsForUser(userId: String): Flow<List<CommitmentManagementRow>>

    /**
     * Emits pending action/schedule commitment items for [userId] in the Today window.
     *
     * Actions include overdue and undated rows; schedules are bounded to the current
     * KST day so calendar-backed events from previous days do not remain in Today.
     *
     * @param endOfTodayEpochMs Inclusive upper bound as UTC epoch milliseconds; callers
     *   compute it as Asia/Seoul 23:59:59.999 converted to UTC epoch ms.
     * @param startOfTodayEpochMs Inclusive lower bound as UTC epoch milliseconds; callers
     *   compute it as Asia/Seoul 00:00:00.000 converted to UTC epoch ms.
     */
    public fun observePendingForToday(
        userId: String,
        endOfTodayEpochMs: Long,
        startOfTodayEpochMs: Long = Long.MIN_VALUE,
    ): Flow<List<CommitmentEntity>>

    /**
     * Emits the same Today commitment set as [observePendingForToday], but pre-joined and
     * narrowed to display fields so the home screen does not load full rows or the full
     * enrichment map.
     */
    public fun observeTimelineForToday(
        userId: String,
        endOfTodayEpochMs: Long,
        startOfTodayEpochMs: Long = Long.MIN_VALUE,
    ): Flow<List<TodayCommitmentRow>>

    /** Emits all commitments for [userId] linked to [counterpartyRef], re-emits on change. */
    public fun observeAllForPerson(userId: String, counterpartyRef: String): Flow<List<CommitmentEntity>>

    /**
     * Emits the live commitment identified by [id] and re-emits on every matching
     * row write. Emits `null` when no row matches OR the row is soft-deleted.
     *
     * Drives `CommitmentDetailSheet` via `CommitmentDetailViewModel` so the sheet
     * live-updates when a VM handler triggers a state transition (e.g.
     * [transitionState]) and the corresponding DAO write lands.
     *
     * Note: bare-id observation is unscoped — the caller is responsible for
     * making sure `id` belongs to the currently signed-in user. Prefer
     * [observeByIdForUser] on any new UI surface so cross-account leaks
     * (`.spec/contracts/data-model.yml:476`) are impossible by construction.
     *
     * @param id UUID of the commitment to observe.
     */
    public fun observeById(id: String): Flow<CommitmentEntity?>

    /**
     * User-scoped observer paired with the [CommitmentDao.observeByIdForUser]
     * query. Emits `null` when no row matches the `(userId, id)` tuple OR when
     * the row is soft-deleted. This is the preferred observer for detail / edit
     * / create sheets: the same `id` returned by a deep link can resolve to a
     * different user's row after account switching on the shared Room database,
     * and this query guarantees the caller only ever sees rows belonging to
     * [userId].
     */
    public fun observeByIdForUser(userId: String, id: String): Flow<CommitmentEntity?>

    // ── Remote refresh ───────────────────────────────────────────────────────

    /**
     * Delta-syncs commitments from Railway, upserts results into Room, and persists the cursor.
     *
     * Iterates pages until `hasMore=false` or a safety cap of 5 pages is consumed. The persisted
     * cursor key is `"commitments_cursor"`.
     *
     * @param since When non-null, only commitments updated after this instant are returned.
     */
    public suspend fun refreshSince(
        userId: String,
        since: Instant?,
        counterpartyRef: String? = null,
        direction: String? = null,
        actionState: String? = null,
    ): BecalmResult<RefreshStats>

    // ── State machine (CMT-005 / 006 / 007 / 011 / 012) ─────────────────────

    /**
     * Loads the commitment with [id], applies [event] via [CommitmentStateMachine], and
     * persists the resulting [CommitmentState] to Room on the spec-aligned
     * `action_state` column.
     *
     * Error mapping:
     * - Row absent                          → [BecalmError.NotFound]
     * - [TransitionError.IllegalTransition] → [BecalmError.Validation]
     *
     * @param id    UUID of the commitment to transition.
     * @param event The [CommitmentEvent] to apply (Remind / FollowUp / Complete /
     *   Cancel from the UI; MarkOverdue is internal to the worker layer).
     * @return The updated [CommitmentEntity] on success, or a typed failure.
     */
    public suspend fun transitionState(id: String, event: CommitmentEvent): BecalmResult<CommitmentEntity>

    /**
     * Optimistically writes [newState] to Room, then PATCHes Railway.
     *
     * On a non-2xx response the local write is NOT reverted; the SP-29 UploadWorker will
     * retry via `sync_status='pending'` semantics.
     */
    public suspend fun updateActionState(
        id: String,
        newState: String,
        updatedAt: Instant,
    ): BecalmResult<Unit>

    /**
     * Returns up to [limit] commitments eligible for the CMT-011 overdue sweep.
     *
     * Eligibility is defined as:
     * - `due_at IS NOT NULL`
     * - `due_at < cutoff`
     * - `action_state IN ('pending', 'reminded', 'followed_up')`
     * - `deleted_at IS NULL`
     */
    public suspend fun findOverdueCandidates(
        userId: String,
        cutoff: Instant,
        limit: Int,
    ): List<CommitmentEntity>

    /**
     * Applies the system-derived CMT-011 overdue transition to the rows in [ids].
     *
     * The write is local-first and leaves `sync_status='pending'` so the existing
     * upload path can mirror the state to Railway when network is available.
     *
     * @return Updated-row count on success.
     */
    public suspend fun markOverdue(
        ids: List<String>,
        updatedAt: Instant,
    ): BecalmResult<Int>

    // ── Edit / dispute / soft-delete / supersede (EDIT-001..008) ────────────

    /**
     * Applies the user-validated [patch] to the commitment identified by [id]
     * (EDIT-003). The repository resolves the acting user id from
     * [com.becalm.android.data.local.datastore.UserPrefsStore] and writes both
     * the mutable columns AND the audit columns (`last_edited_by`, `last_edited_at`)
     * atomically in a single DAO update.
     *
     * On DAO write:
     * - 0 rows affected → [com.becalm.android.core.result.BecalmError.NotFound].
     * - Row is soft-deleted → DAO filter rejects the write (also 0 rows).
     *
     * On Railway PATCH:
     * - 2xx → `Success(Unit)`.
     * - Any non-2xx or IOException → leaves `sync_status='pending'` so the
     *   existing UploadWorker drains the queue on the next run. Returns
     *   `Success(Unit)` — the local write already landed, and the spec guarantees
     *   eventual consistency via the pending queue (EDIT-003 invariant 2).
     *
     * @param id UUID of the commitment to edit.
     * @param patch Validated user patch.
     */
    public suspend fun editCommitment(id: String, patch: CommitmentEditPatch): BecalmResult<Unit>

    /**
     * Raises a dispute against the commitment's quote (EDIT-005). Writes
     * `quote_disputed=true` + timestamp + audit columns; **never** mutates the
     * quote string itself.
     */
    public suspend fun markQuoteDisputed(id: String): BecalmResult<Unit>

    /**
     * Clears a previously-raised dispute (EDIT-005 toggle-off). Inverse of
     * [markQuoteDisputed]; the quote string is untouched.
     */
    public suspend fun clearQuoteDispute(id: String): BecalmResult<Unit>

    /**
     * Soft-deletes the commitment identified by [id] (EDIT-006). Writes
     * `deleted_at=now()` so that every user-facing SELECT filters the row out.
     * This is distinct from `action_state='cancelled'` — cancellation means
     * "the agreement was rescinded"; soft-delete means "this row was wrong".
     *
     * The local DAO write always lands. The Railway PATCH is best-effort; on
     * failure the row remains `sync_status='pending'` and UploadWorker replays it.
     */
    public suspend fun softDelete(id: String): BecalmResult<Unit>

    /**
     * Implements EDIT-007 "this is actually a different commitment". Atomically:
     * 1. Inserts [newRow] (expected to already carry `supersedes_commitment_id = oldId`).
     * 2. Soft-deletes the [oldId] row.
     *
     * Both writes share a single Room transaction so a crash between them cannot
     * leave the table with orphaned supersede lineage. After the transaction
     * commits, the repository attempts a best-effort POST /v1/commitments:batch
     * for the new row and a PATCH for the old row; both failures are absorbed
     * into the standard `sync_status='pending'` retry queue.
     *
     * @param oldId UUID of the row being superseded.
     * @param newRow Fully populated replacement entity. The caller is responsible
     *   for copying the legally evidentiary [com.becalm.android.data.local.db.entity.CommitmentEntity.quote]
     *   and `source_*` columns from the old row verbatim.
     * @return `Success(newId)` on success, `Failure(error)` on DAO failure.
     */
    public suspend fun supersede(oldId: String, newRow: CommitmentEntity): BecalmResult<String>

    // ── Supersede correction (EDIT-007) ──────────────────────────────────────

    /**
     * Saves a user correction for EDIT-007 "이건 다른 약속입니다".
     * The old row is atomically soft-deleted and the new row carries
     * `supersedes_commitment_id = oldId`.
     *
     * **No `raw_ingestion_events` row is ever created on this path.** The new
     * row inherits source_type/source_ref/source_event_* and quote from the old
     * row so evidentiary provenance remains attached to the replacement.
     *
     * Actor id is resolved from
     * [com.becalm.android.data.local.datastore.UserPrefsStore]; a blank id
     * short-circuits to [com.becalm.android.core.result.BecalmError.Unauthorized].
     *
     * @param input Validated user input (title / direction / quote /
     *   counterpartyRef / due_*). The caller should pass the old row's quote
     *   verbatim; the repository also copies it from the old row defensively.
     * @param supersedeOf UUID of the row being superseded.
     * @return `Success(newId)` on success, typed `Failure` on DAO / auth error.
     */
    public suspend fun saveManualCommitment(
        input: ManualCommitmentInput,
        supersedeOf: String,
    ): BecalmResult<String>

    // ── Sync helpers (SP-29 worker) ──────────────────────────────────────────

    /** Returns up to [limit] commitments for [userId] with `sync_status='pending'`. */
    public suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity>

    /** Marks the commitments identified by [ids] as `sync_status='synced'`. */
    public suspend fun markSynced(ids: List<String>): BecalmResult<Unit>

    /**
     * Marks a single commitment as `sync_status='failed'` (quarantine).
     *
     * Called by [com.becalm.android.worker.UploadWorker] when the server returns a
     * non-retryable [BatchResponse.failed] entry for this row, so that subsequent
     * [findPendingSync] calls stop picking it up.
     */
    public suspend fun markFailed(id: String): BecalmResult<Unit>

    /**
     * Uploads [pending] commitments to Railway via POST /v1/commitments:batch.
     *
     * Used by [com.becalm.android.worker.UploadWorker.flushCommitments] to drain rows that
     * were demoted to `sync_status='pending'` by [updateActionState] after a failed PATCH
     * (CMT-005..007 + commitment-management.spec.yml invariant 3).
     *
     * Returns [BecalmResult.Success] with a [BatchResponse] describing per-item outcomes.
     * The caller is responsible for partitioning [BatchResponse.failed] into retryable
     * (leave as `pending`) vs non-retryable (mark as `failed` / quarantine).
     *
     * An empty [pending] list short-circuits to `Success(BatchResponse(0, emptyList()))`
     * without hitting the network.
     *
     * HTTP error mapping mirrors [RawIngestionRepository.uploadBatch]:
     * - 401 → [com.becalm.android.core.result.BecalmError.Unauthorized]
     * - 413 → [com.becalm.android.core.result.BecalmError.Validation]
     * - 422 → [com.becalm.android.core.result.BecalmError.Validation]
     * - 429 → [com.becalm.android.core.result.BecalmError.RateLimited]
     * - 5xx → [com.becalm.android.core.result.BecalmError.ServerError]
     * - IOException / other non-2xx → [com.becalm.android.core.result.BecalmError.Network]
     *
     * Spec refs: CMT-005..007, SYNC-001.
     */
    public suspend fun uploadBatch(pending: List<CommitmentEntity>): BecalmResult<BatchResponse>

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Deletes all commitment rows for [userId].
     *
     * @return [BecalmResult.Success] with the deleted-row count on success.
     */
    public suspend fun deleteAllForUser(userId: String): BecalmResult<Int>

    // ── Supporting types ─────────────────────────────────────────────────────

    /**
     * Aggregate statistics returned by [refreshSince].
     *
     * @property fetched Total DTOs received across all pages.
     * @property upserted Total entities written to Room (equal to [fetched] on success).
     * @property hasMore True when the server indicated more pages beyond the safety cap.
     * @property nextCursor The final cursor persisted to [SyncCursorStore], or null.
     */
    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )

    /**
     * Domain representation of a POST /v1/commitments:batch response.
     *
     * Wraps the DTO layer so worker code depends on a pure-Kotlin type that does not leak
     * Moshi/Retrofit types outward (per :data layer boundary rules, big-tech-rubric § A).
     *
     * @property acknowledged Count of commitments accepted or deduped by the server.
     * @property failed Per-item failures; partition on [FailedEvent.retryable] to decide
     *   whether to re-queue ([failed]: true) or quarantine ([failed]: false) each row.
     */
    public data class BatchResponse(
        val acknowledged: Int,
        val failed: List<FailedEvent>,
    )

    /**
     * Domain representation of a single failed item in [BatchResponse.failed].
     *
     * Mirrors the wire `FailedEvent` type from api-contract.yml (top of file).
     *
     * @property clientEventId The idempotency key of the failed commitment (matches
     *   [CommitmentEntity.id] for commitments uploaded via [uploadBatch]).
     * @property reason Machine-readable reason code:
     *   "schema_invalid" | "source_type_unknown" | "timestamp_parse_error" | "internal_error"
     * @property message Human-readable detail.
     * @property retryable When true, client SHOULD re-enqueue (next worker run retries).
     *   When false, client SHOULD quarantine via `sync_status='failed'`.
     */
    public data class FailedEvent(
        val clientEventId: String,
        val reason: String,
        val message: String,
        val retryable: Boolean,
    )
}
