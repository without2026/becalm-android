package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.domain.commitment.CommitmentEvent
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

    /** Emits commitments for [userId] filtered by [actionState], re-emits on change. */
    public fun observeByActionState(userId: String, actionState: String): Flow<List<CommitmentEntity>>

    /**
     * Emits pending commitments for [userId] that are undated or due on/before [todayIso].
     *
     * @param todayIso ISO-8601 date string, e.g. "2026-04-16".
     */
    public fun observePendingForToday(userId: String, todayIso: String): Flow<List<CommitmentEntity>>

    /** Emits all commitments for [userId] linked to [personRef], re-emits on change. */
    public fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>>

    /** Emits the count of non-completed commitments for [userId] linked to [personRef]. */
    public fun observeOpenCountForPerson(userId: String, personRef: String): Flow<Int>

    /** Emits the commitment with [id], or null when absent; re-emits on change. */
    public fun observeById(id: String): Flow<CommitmentEntity?>

    // ── One-shot reads ───────────────────────────────────────────────────────

    /** Returns the commitment with [id], or null when no matching row exists. */
    public suspend fun findById(id: String): CommitmentEntity?

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

    /**
     * Fetches a single commitment by [id] from Railway and upserts it into Room.
     *
     * @return The upserted [CommitmentEntity] on success, or a typed failure.
     */
    public suspend fun fetchSingle(id: String): BecalmResult<CommitmentEntity>

    // ── State machine (CMT-005..007 / SP-36) ────────────────────────────────

    /**
     * Loads the commitment with [id], applies [event] via [CommitmentStateMachine], and
     * persists the resulting [com.becalm.android.domain.commitment.CommitmentState] to Room.
     *
     * Error mapping:
     * - Row absent                           → [BecalmError.NotFound]
     * - [TransitionError.IllegalTransition]  → [BecalmError.Validation]
     * - [TransitionError.MissingSchedule]    → [BecalmError.Validation]
     *
     * @param id    UUID of the commitment to transition.
     * @param event The [CommitmentEvent] to apply.
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
}
