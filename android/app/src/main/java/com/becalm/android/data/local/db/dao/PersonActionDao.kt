package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.becalm.android.data.local.db.entity.PersonActionItemCacheEntity
import com.becalm.android.data.local.db.entity.PersonActionMutationQueueEntity
import com.becalm.android.data.local.db.entity.PersonActionSyncStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
public interface PersonActionDao {
    @Query(
        """
        SELECT * FROM person_action_item_cache
        WHERE user_id = :userId
          AND status = 'active'
          AND (',' || surfaces_csv || ',') LIKE '%,' || :surface || ',%'
        ORDER BY urgency_score DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    public fun observeActiveForSurface(userId: String, surface: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>

    @Query(
        """
        SELECT * FROM person_action_item_cache
        WHERE user_id = :userId
          AND status = 'active'
          AND person_id = :personId
        ORDER BY urgency_score DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    public fun observeActiveForPerson(userId: String, personId: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>

    @Query(
        """
        SELECT * FROM person_action_item_cache
        WHERE user_id = :userId
          AND status = 'active'
          AND commitment_id = :commitmentId
        ORDER BY urgency_score DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    public fun observeActiveForCommitment(userId: String, commitmentId: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>

    @Query(
        """
        SELECT * FROM person_action_item_cache
        WHERE user_id = :userId
          AND status = 'active'
          AND calendar_event_id = :calendarEventId
        ORDER BY urgency_score DESC, updated_at DESC
        LIMIT :limit
        """,
    )
    public fun observeActiveForCalendarEvent(userId: String, calendarEventId: String, limit: Int = 100): Flow<List<PersonActionItemCacheEntity>>

    @Query(
        """
        SELECT server_watermark FROM person_action_sync_state
        WHERE user_id = :userId
          AND surface_key = :surfaceKey
          AND status = :status
        LIMIT 1
        """,
    )
    public suspend fun latestServerWatermark(userId: String, surfaceKey: String, status: String): Instant?

    @Query(
        """
        SELECT * FROM person_action_sync_state
        WHERE user_id = :userId
          AND surface_key = :surfaceKey
          AND status = :status
        LIMIT 1
        """,
    )
    public fun observeSyncState(userId: String, surfaceKey: String, status: String): Flow<PersonActionSyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertActionItems(rows: List<PersonActionItemCacheEntity>)

    @Query("DELETE FROM person_action_item_cache WHERE user_id = :userId AND id IN (:ids)")
    public suspend fun deleteActionItems(userId: String, ids: List<String>)

    @Query("DELETE FROM person_action_item_cache WHERE user_id = :userId")
    public suspend fun deleteAllActionItemsForUser(userId: String): Int

    @Query(
        """
        DELETE FROM person_action_item_cache
        WHERE user_id = :userId
          AND (:surface IS NULL OR (',' || surfaces_csv || ',') LIKE '%,' || :surface || ',%')
        """,
    )
    public suspend fun deleteAllActionItemsForScope(userId: String, surface: String?): Int

    @Query(
        """
        DELETE FROM person_action_item_cache
        WHERE user_id = :userId
          AND (:surface IS NULL OR (',' || surfaces_csv || ',') LIKE '%,' || :surface || ',%')
          AND id NOT IN (:retainedIds)
        """,
    )
    public suspend fun deleteActionItemsNotInScope(userId: String, surface: String?, retainedIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertSyncState(row: PersonActionSyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertMutation(row: PersonActionMutationQueueEntity)

    @Query(
        """
        SELECT * FROM person_action_mutation_queue
        WHERE user_id = :userId
          AND (
            sync_status = 'pending'
            OR (sync_status = 'failed' AND next_attempt_at IS NOT NULL AND next_attempt_at <= :now)
          )
        ORDER BY updated_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingMutations(userId: String, now: Instant, limit: Int = 20): List<PersonActionMutationQueueEntity>

    @Query(
        """
        SELECT COUNT(*) FROM person_action_mutation_queue
        WHERE user_id = :userId
          AND (
            sync_status = 'pending'
            OR (sync_status = 'failed' AND next_attempt_at IS NOT NULL)
          )
        """,
    )
    public fun observePendingMutationCount(userId: String): Flow<Int>

    @Query(
        """
        UPDATE person_action_mutation_queue
        SET sync_status = 'synced',
            last_error_code = NULL,
            last_error_client_action = NULL,
            next_attempt_at = NULL,
            updated_at = :updatedAt
        WHERE user_id = :userId AND client_mutation_id = :clientMutationId
        """,
    )
    public suspend fun markMutationSynced(userId: String, clientMutationId: String, updatedAt: Instant)

    @Query(
        """
        UPDATE person_action_mutation_queue
        SET sync_status = 'failed',
            attempt_count = attempt_count + 1,
            last_error_code = :errorCode,
            last_error_client_action = :clientAction,
            next_attempt_at = :nextAttemptAt,
            updated_at = :updatedAt
        WHERE user_id = :userId AND client_mutation_id = :clientMutationId
        """,
    )
    public suspend fun markMutationFailed(
        userId: String,
        clientMutationId: String,
        errorCode: String?,
        clientAction: String?,
        nextAttemptAt: Instant?,
        updatedAt: Instant,
    )

    @Transaction
    public suspend fun applyFeedSnapshot(
        userId: String,
        rows: List<PersonActionItemCacheEntity>,
        deletedIds: List<String>,
        replaceScope: Boolean,
        surface: String?,
        syncState: PersonActionSyncStateEntity,
    ) {
        if (replaceScope) {
            val retainedIds = rows.map(PersonActionItemCacheEntity::id).distinct()
            if (retainedIds.isEmpty()) {
                deleteAllActionItemsForScope(userId = userId, surface = surface)
            } else {
                deleteActionItemsNotInScope(userId = userId, surface = surface, retainedIds = retainedIds)
            }
        }
        if (deletedIds.isNotEmpty()) {
            deleteActionItems(userId, deletedIds.distinct())
        }
        if (rows.isNotEmpty()) {
            upsertActionItems(rows)
        }
        upsertSyncState(syncState)
    }
}
