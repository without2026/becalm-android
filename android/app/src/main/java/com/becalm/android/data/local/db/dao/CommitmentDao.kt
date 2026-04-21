package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.becalm.android.data.local.db.entity.CommitmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Room DAO for the `commitments` table.
 *
 * All write paths use [OnConflictStrategy.REPLACE] because Railway is the source of truth;
 * receiving a fresher record from GET /v1/commitments must always overwrite the local copy.
 *
 * The [observePendingForToday] query accepts `endOfTodayEpochMs` as a UTC epoch-millisecond
 * bound rather than a date string because `commitments.due_at` is stored as an
 * [kotlinx.datetime.Instant] (INTEGER epoch ms via the Room converter) as of DB v4.
 * Callers compute the bound as Asia/Seoul 23:59:59.999 → UTC epoch ms — see
 * `.spec/contracts/data-model.yml:132-144` and VOI-003.
 *
 * All [Flow]-returning queries are cold; collection begins on the first downstream
 * collector and Room re-emits on every matching table write.
 */
@Dao
public interface CommitmentDao {

    // ─── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a single commitment, replacing any existing row with the same [CommitmentEntity.id].
     *
     * @param entity The commitment to insert or replace.
     * @return The row ID of the inserted row, or the existing row ID if replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: CommitmentEntity): Long

    /**
     * Inserts a batch of commitments, replacing any existing rows with matching primary keys.
     *
     * Prefer this over calling [insert] in a loop — Room wraps the list in a single transaction.
     *
     * @param entities The list of commitments to insert or replace.
     * @return A list of row IDs in the same order as [entities].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entities: List<CommitmentEntity>): List<Long>

    /**
     * Updates an existing commitment row identified by [CommitmentEntity.id].
     *
     * Prefer [updateActionState] for narrow state-machine transitions to avoid
     * overwriting concurrent server-side updates to other fields.
     *
     * @param entity The commitment with updated fields.
     * @return The number of rows updated (0 if the row did not exist, 1 otherwise).
     */
    @Update
    public suspend fun update(entity: CommitmentEntity): Int

    /**
     * Atomically updates [CommitmentEntity.actionState] and [CommitmentEntity.updatedAt]
     * for the row identified by [id].
     *
     * Used by the action-state state machine (pending → reminded → followed_up → completed)
     * and by SyncWorker after a successful PATCH /v1/commitments/{id} response.
     *
     * @param id UUID of the commitment to update.
     * @param newState Target action state. Valid values: "pending" | "reminded" |
     *   "followed_up" | "completed".
     * @param updatedAt Timestamp to write into [CommitmentEntity.updatedAt]; pass the
     *   server-returned value when available, or a local clock value for optimistic writes.
     * @return The number of rows updated (0 if no row matched [id], 1 otherwise).
     */
    @Query(
        """
        UPDATE commitments
        SET action_state = :newState,
            updated_at   = :updatedAt
        WHERE id = :id
        """
    )
    public suspend fun updateActionState(id: String, newState: String, updatedAt: Instant): Int

    /**
     * Sets [CommitmentEntity.syncStatus] to "synced" for all rows whose [CommitmentEntity.id]
     * is contained in [ids].
     *
     * Called by SyncWorker after a successful Railway batch acknowledgement.
     *
     * @param ids UUIDs of the commitments to mark as synced.
     */
    @Query("UPDATE commitments SET sync_status = 'synced' WHERE id IN (:ids)")
    public suspend fun markSynced(ids: List<String>)

    /**
     * Sets [CommitmentEntity.syncStatus] to "pending" for the single row identified by [id].
     *
     * Called by [com.becalm.android.data.repository.CommitmentRepositoryImpl.updateActionState]
     * when a Railway PATCH fails (5xx / IOException) so that the row is re-picked up by the
     * next SP-29 UploadWorker batch flush (CMT-005..007 + commitment-management.spec.yml
     * invariant 3).
     *
     * @param id UUID of the commitment to demote.
     */
    @Query("UPDATE commitments SET sync_status = 'pending' WHERE id = :id")
    public suspend fun markPending(id: String)

    // ─── Edit / dispute / soft-delete (EDIT-001..008) ─────────────────────────

    /**
     * Applies a user edit to the mutable fields of the commitment identified by [id]
     * (EDIT-003). Updates `title`, `due_at`, `due_hint`, `due_is_approximate`,
     * `person_ref`, `direction` plus the audit columns `last_edited_by` /
     * `last_edited_at` (spec `.spec/commitment-edit.spec.yml` EDIT-003 invariant).
     *
     * Every successful write flips `sync_status='pending'` so the SP-29 UploadWorker
     * replays the full row via [com.becalm.android.data.remote.api.RailwayApi.uploadCommitmentsBatch]
     * on the next flush. The `AND deleted_at IS NULL` guard ensures soft-deleted rows
     * reject edits — the caller receives `0` rows-affected and surfaces
     * [com.becalm.android.core.result.BecalmError.NotFound].
     *
     * `updated_at` is deliberately written from [editedAt] so the local Room copy stays
     * monotone with the server's own `updated_at` once the PATCH round-trips; the
     * server's authoritative value overwrites on the next refresh.
     *
     * @param id UUID of the commitment to edit.
     * @param title New short title (spec: length 1..200, pre-trimmed by the caller).
     * @param dueAt New deadline instant or null (EDIT-004 allows null).
     * @param dueHint New verbatim due-date expression, or null to clear.
     * @param approx New `due_is_approximate` flag (defaults false in the client form).
     * @param personRef Canonicalized counterparty identifier, or null.
     * @param direction `"give"` or `"take"` (EDIT-004 enum).
     * @param actorId Supabase auth.users UUID of the editing user. Never blank —
     *   the repository fails fast with [com.becalm.android.core.result.BecalmError.Unauthorized]
     *   before calling this method.
     * @param editedAt Monotonic edit timestamp (both `last_edited_at` and `updated_at`).
     * @return Number of rows affected: 1 on success, 0 when [id] does not exist or the
     *   row is soft-deleted.
     */
    @Query(
        """
        UPDATE commitments
        SET title = :title,
            due_at = :dueAt,
            due_hint = :dueHint,
            due_is_approximate = :approx,
            person_ref = :personRef,
            direction = :direction,
            last_edited_by = :actorId,
            last_edited_at = :editedAt,
            updated_at = :editedAt,
            sync_status = 'pending'
        WHERE id = :id AND deleted_at IS NULL
        """
    )
    public suspend fun applyEdit(
        id: String,
        title: String,
        dueAt: Instant?,
        dueHint: String?,
        approx: Boolean,
        personRef: String?,
        direction: String,
        actorId: String,
        editedAt: Instant,
    ): Int

    /**
     * Flips [CommitmentEntity.quoteDisputed] to `true` and records the dispute
     * timestamp (EDIT-005 dispute flow). The verbatim [CommitmentEntity.quote] is
     * **never** altered — that column is legally evidentiary per
     * `.spec/commitment-edit.spec.yml` invariant 1.
     *
     * The `AND deleted_at IS NULL` guard silently rejects disputes against
     * soft-deleted rows; the repository surfaces this as
     * [com.becalm.android.core.result.BecalmError.NotFound] on rows-affected = 0.
     *
     * @return Number of rows affected: 1 on success, 0 when absent or soft-deleted.
     */
    @Query(
        """
        UPDATE commitments
        SET quote_disputed = 1,
            quote_disputed_at = :at,
            last_edited_by = :actor,
            last_edited_at = :at,
            updated_at = :at,
            sync_status = 'pending'
        WHERE id = :id AND deleted_at IS NULL
        """
    )
    public suspend fun markQuoteDisputed(id: String, actor: String, at: Instant): Int

    /**
     * Clears a previously-raised dispute (EDIT-005 toggle-off via `[이의 제기 해제]`).
     * Resets [CommitmentEntity.quoteDisputed] to `false` and nulls the paired
     * timestamp. The quote string itself remains immutable — only the flag flips.
     *
     * The `AND deleted_at IS NULL` guard prevents clears against tombstoned rows.
     *
     * @return Number of rows affected: 1 on success, 0 when absent or soft-deleted.
     */
    @Query(
        """
        UPDATE commitments
        SET quote_disputed = 0,
            quote_disputed_at = NULL,
            last_edited_by = :actor,
            last_edited_at = :at,
            updated_at = :at,
            sync_status = 'pending'
        WHERE id = :id AND deleted_at IS NULL
        """
    )
    public suspend fun clearQuoteDispute(id: String, actor: String, at: Instant): Int

    /**
     * Soft-deletes the commitment identified by [id] by writing a non-null
     * [CommitmentEntity.deletedAt] timestamp (EDIT-006).
     *
     * Unlike the edit / dispute queries above, this statement **deliberately omits**
     * the `AND deleted_at IS NULL` guard. Rationale: double-soft-delete of an
     * already-tombstoned row is still a write the server eventually needs to see if
     * the tombstone has not yet round-tripped (for example, the client may overwrite
     * the existing `deleted_at` with a later timestamp and a fresh `last_edited_*`
     * audit pair). The update is therefore idempotent — running it twice is harmless
     * and returns 1 rows-affected both times — and the repository does not treat the
     * second write as an error.
     *
     * The row is not hard-deleted. Retention of tombstones is required for
     * supersede audit lineage and for the PIPA 제36조 right-to-correction trail
     * (see `.spec/commitment-edit.spec.yml` EDIT-007).
     *
     * @return Number of rows affected: 1 on success, 0 only when [id] does not exist
     *   in the local table at all.
     */
    @Query(
        """
        UPDATE commitments
        SET deleted_at = :at,
            last_edited_by = :actor,
            last_edited_at = :at,
            updated_at = :at,
            sync_status = 'pending'
        WHERE id = :id
        """
    )
    public suspend fun softDelete(id: String, actor: String, at: Instant): Int

    /**
     * Marks the row identified by [id] as `sync_status='failed'` (quarantine).
     *
     * Used by [com.becalm.android.data.repository.CommitmentRepositoryImpl.uploadBatch] when
     * the server returns a non-retryable [com.becalm.android.data.remote.dto.FailedEventDto]
     * entry so that the row is not re-uploaded indefinitely.
     *
     * Mirrors the [RawIngestionEventDao.markFailed] signature (note: commitments has no
     * `retry_count` / `last_attempt_at` columns per data-model.yml, so only `sync_status`
     * is written here).
     *
     * @param id UUID of the commitment to quarantine.
     */
    @Query("UPDATE commitments SET sync_status = 'failed' WHERE id = :id")
    public suspend fun markFailed(id: String)

    /**
     * Deletes all commitment rows belonging to [userId].
     *
     * Called on sign-out to purge local data. Returns the number of deleted rows.
     *
     * @param userId Supabase auth.users UUID of the user whose rows to delete.
     * @return The number of rows deleted.
     */
    @Query("DELETE FROM commitments WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int

    // ─── Single-row reads ──────────────────────────────────────────────────────

    /**
     * Returns the commitment with the given [id], or null if no such row exists
     * OR the row has been soft-deleted.
     *
     * Includes the `AND deleted_at IS NULL` filter mandated by
     * `.spec/contracts/data-model.yml:204-205` so that soft-deleted rows are
     * invisible to callers without an explicit opt-in. Restoring a soft-deleted
     * row (EDIT-006 undo flow) is the only path that should bypass this filter,
     * and that path lives in a future dedicated DAO method — not here.
     *
     * @param id UUID of the commitment to fetch.
     * @return The matching live [CommitmentEntity], or null when absent or soft-deleted.
     */
    @Query("SELECT * FROM commitments WHERE id = :id AND deleted_at IS NULL")
    public suspend fun findById(id: String): CommitmentEntity?

    /**
     * Emits the live commitment identified by [id] and re-emits on every matching
     * `commitments` row write. Emits `null` when no row matches OR the matching row
     * has been soft-deleted.
     *
     * This is the reactive sibling of [findById]; consumers such as
     * `CommitmentDetailViewModel` subscribe so the sheet live-updates when the entity
     * changes (e.g. after a state transition flips `action_state`). The `AND deleted_at
     * IS NULL` clause is the same MUST-level invariant applied to [findById] and every
     * other user-facing SELECT per `.spec/contracts/data-model.yml:204-205`.
     *
     * @param id UUID of the commitment to observe.
     * @return A [Flow] that emits the matching live [CommitmentEntity], or `null`
     *   when absent or soft-deleted.
     */
    @Query("SELECT * FROM commitments WHERE id = :id AND deleted_at IS NULL")
    public fun observeById(id: String): Flow<CommitmentEntity?>

    /**
     * Returns the live-or-tombstoned commitments for [userId] whose [CommitmentEntity.id]
     * is in [ids]. Intentionally **omits** the `deleted_at IS NULL` filter so that the
     * refresh-merge path in [com.becalm.android.data.repository.CommitmentRepository.refreshSince]
     * can detect locally-set lifecycle state (edit / dispute / tombstone / supersede)
     * that has not yet round-tripped through the server.
     *
     * This is the only read path on this DAO that returns tombstones. User-facing reads
     * MUST continue to use the filtered queries above — callers of this method are trusted
     * not to surface the results to the UI directly. See the soft-delete invariant at
     * `.spec/contracts/data-model.yml:204-205`.
     *
     * @param userId Supabase auth.users UUID of the owning user. Scoping by user_id is
     *   mandatory because the primary key `id` alone is unique across users on Supabase
     *   but the local Room table carries rows for every signed-in account.
     * @param ids The set of primary keys to resolve.
     * @return The matching rows (live and tombstoned) in arbitrary order. Missing ids
     *   silently drop from the result — callers handle that case by treating them as
     *   "not yet seen locally" and inserting fresh.
     */
    @Query("SELECT * FROM commitments WHERE user_id = :userId AND id IN (:ids)")
    public suspend fun findByIdsForMerge(userId: String, ids: List<String>): List<CommitmentEntity>

    // ─── List reads ────────────────────────────────────────────────────────────

    /**
     * Emits every live commitment for [userId] regardless of action_state or commitment_state,
     * ordered by the source event timestamp descending (newest first).
     *
     * Re-emits on any write to the `commitments` table that affects [userId]. Used by
     * CommitmentManagementScreen so that a single Room subscription drives all filter tabs
     * without maintaining a separate flow per action_state value. Soft-deleted rows
     * (`deleted_at IS NOT NULL`) are excluded per `.spec/contracts/data-model.yml:204-205`
     * MUST-invariant; the index `idx_commitments_user_deleted` backs this filter.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @return A [Flow] that emits a list of live commitments and re-emits on every qualifying
     *   table write.
     */
    @Query(
        """
        SELECT * FROM commitments
        WHERE user_id = :userId
          AND deleted_at IS NULL
        ORDER BY source_event_occurred_at DESC
        """
    )
    public fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>>

    /**
     * Emits all live pending commitments for [userId] that are either undated or due
     * on/before end-of-today in Asia/Seoul.
     *
     * `endOfTodayEpochMs` is an inclusive UTC epoch-millisecond upper bound. The caller
     * must compute it as `Asia/Seoul` 23:59:59.999 converted to UTC epoch ms so that the
     * comparison `due_at <= :endOfTodayEpochMs` correctly captures every commitment whose
     * KST calendar date is on or before today (consistent with data-model.yml:132-144 and
     * VOI-003 KST-rendered due semantics).
     *
     * Soft-deleted rows (`deleted_at IS NOT NULL`) are excluded per
     * `.spec/contracts/data-model.yml:204-205` MUST-invariant.
     *
     * Used by the daily reminder widget and the home screen "due today" badge.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @param endOfTodayEpochMs Inclusive upper bound as UTC epoch ms (Asia/Seoul 23:59:59.999).
     * @return A [Flow] that emits a list and re-emits on every qualifying table write.
     */
    @Query(
        """
        SELECT * FROM commitments
        WHERE user_id      = :userId
          AND action_state = 'pending'
          AND (due_at IS NULL OR due_at <= :endOfTodayEpochMs)
          AND deleted_at IS NULL
        ORDER BY due_at IS NULL ASC, due_at ASC, created_at DESC
        """
    )
    public fun observePendingForToday(userId: String, endOfTodayEpochMs: Long): Flow<List<CommitmentEntity>>

    /**
     * Emits all live commitments for [userId] linked to [personRef], ordered by due date
     * ascending (nulls last) then creation timestamp descending.
     *
     * Used by PersonDetailScreen to render the full commitment timeline for a contact.
     * Soft-deleted rows (`deleted_at IS NOT NULL`) are excluded per
     * `.spec/contracts/data-model.yml:204-205` MUST-invariant.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @param personRef Canonicalized counterparty identifier (matches [CommitmentEntity.personRef]).
     * @return A [Flow] that emits a list and re-emits on every qualifying table write.
     */
    @Query(
        """
        SELECT * FROM commitments
        WHERE user_id    = :userId
          AND person_ref = :personRef
          AND deleted_at IS NULL
        ORDER BY due_at IS NULL ASC, due_at ASC, created_at DESC
        """
    )
    public fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>>

    // ─── Sync helpers ──────────────────────────────────────────────────────────

    /**
     * Returns up to [limit] commitments for [userId] whose [CommitmentEntity.syncStatus]
     * is "pending", eligible for the next Railway upload batch. **Includes soft-deleted
     * rows** — a locally-set tombstone (`deleted_at IS NOT NULL`) is a pending state
     * transition the server must learn about, so omitting it here would trap the
     * user's delete on-device forever and resurrect the commitment on every other
     * client's next refresh.
     *
     * This is the one sanctioned exception to the `.spec/contracts/data-model.yml:204-205`
     * "all client queries MUST include `deleted_at IS NULL`" invariant. That clause
     * targets user-facing reads — the UI must never render a tombstone. The upload
     * path exists *because* the tombstone needs to round-trip; including it here is
     * the only way to observe EDIT-006 soft-delete semantics correctly.
     *
     * The result is a one-shot list rather than a [Flow] because SyncWorker consumes
     * it once per work run without needing reactive updates mid-batch.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @param limit Maximum number of rows to return. Pass the batch-size constant from
     *   SyncWorker (e.g. 50) to respect the Railway 100-event-per-batch ceiling.
     * @return A list of up to [limit] commitments with sync_status = "pending",
     *   including soft-deleted rows with a pending status.
     */
    @Query(
        """
        SELECT * FROM commitments
        WHERE user_id    = :userId
          AND sync_status = 'pending'
        LIMIT :limit
        """
    )
    public suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity>
}
