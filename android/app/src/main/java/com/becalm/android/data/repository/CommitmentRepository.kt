package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.CommitmentStateMachine
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
     * Emits pending commitments for [userId] that are undated or due on/before end-of-today.
     *
     * @param endOfTodayEpochMs Inclusive upper bound as UTC epoch milliseconds; callers
     *   compute it as Asia/Seoul 23:59:59.999 converted to UTC epoch ms.
     */
    public fun observePendingForToday(userId: String, endOfTodayEpochMs: Long): Flow<List<CommitmentEntity>>

    /** Emits all commitments for [userId] linked to [personRef], re-emits on change. */
    public fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>>

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
        personRef: String? = null,
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
