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
 * The [observePendingForToday] query accepts [todayIso] as an ISO-8601 date string
 * (yyyy-MM-dd) rather than a [kotlinx.datetime.LocalDate] because the LocalDate ↔ String
 * type converter is registered on the database class in SP-13. Using a raw String here
 * avoids a compile-time dependency on the converter being present in this module.
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
     * Returns the commitment with the given [id], or null if no such row exists.
     *
     * @param id UUID of the commitment to fetch.
     * @return The matching [CommitmentEntity], or null.
     */
    @Query("SELECT * FROM commitments WHERE id = :id")
    public suspend fun findById(id: String): CommitmentEntity?

    // ─── List reads ────────────────────────────────────────────────────────────

    /**
     * Emits every commitment for [userId] regardless of action_state or commitment_state,
     * ordered by the source event timestamp descending (newest first).
     *
     * Re-emits on any write to the `commitments` table that affects [userId]. Used by
     * CommitmentManagementScreen so that a single Room subscription drives all filter tabs
     * without maintaining a separate flow per action_state value.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @return A [Flow] that emits a list and re-emits on every qualifying table write.
     */
    @Query(
        """
        SELECT * FROM commitments
        WHERE user_id = :userId
        ORDER BY source_event_occurred_at DESC
        """
    )
    public fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>>

    /**
     * Emits all pending commitments for [userId] that are either undated or due on/before
     * [todayIso].
     *
     * [todayIso] must be an ISO-8601 date string (yyyy-MM-dd). String comparison is
     * lexicographically correct for this format, which is why no explicit type converter
     * is needed in the query itself.
     *
     * Used by the daily reminder widget and the home screen "due today" badge.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @param todayIso Today's date as an ISO-8601 string, e.g. "2026-04-16".
     * @return A [Flow] that emits a list and re-emits on every qualifying table write.
     */
    @Query(
        """
        SELECT * FROM commitments
        WHERE user_id      = :userId
          AND action_state = 'pending'
          AND (due_date IS NULL OR due_date <= :todayIso)
        ORDER BY due_date IS NULL ASC, due_date ASC, created_at DESC
        """
    )
    public fun observePendingForToday(userId: String, todayIso: String): Flow<List<CommitmentEntity>>

    /**
     * Emits all commitments for [userId] linked to [personRef], ordered by due date
     * ascending (nulls last) then creation timestamp descending.
     *
     * Used by PersonDetailScreen to render the full commitment timeline for a contact.
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
        ORDER BY due_date IS NULL ASC, due_date ASC, created_at DESC
        """
    )
    public fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>>

    // ─── Sync helpers ──────────────────────────────────────────────────────────

    /**
     * Returns up to [limit] commitments for [userId] whose [CommitmentEntity.syncStatus]
     * is "pending", eligible for the next Railway upload batch.
     *
     * The result is a one-shot list rather than a [Flow] because SyncWorker consumes it
     * once per work run without needing reactive updates mid-batch.
     *
     * @param userId Supabase auth.users UUID of the owning user.
     * @param limit Maximum number of rows to return. Pass the batch-size constant from
     *   SyncWorker (e.g. 50) to respect the Railway 100-event-per-batch ceiling.
     * @return A list of up to [limit] commitments with sync_status = "pending".
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
