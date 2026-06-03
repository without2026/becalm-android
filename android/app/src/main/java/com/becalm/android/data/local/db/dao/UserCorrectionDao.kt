package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.UserCorrectionEntity
import kotlinx.datetime.Instant

@Dao
public interface UserCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: UserCorrectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(entities: List<UserCorrectionEntity>)

    @Query(
        """
        SELECT * FROM user_corrections
        WHERE user_id = :userId
          AND sync_status = 'pending'
        ORDER BY updated_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingSync(userId: String, limit: Int): List<UserCorrectionEntity>

    @Query(
        """
        SELECT * FROM user_corrections
        WHERE user_id = :userId
          AND status = 'active'
        ORDER BY updated_at ASC
        """,
    )
    public suspend fun findActiveForUser(userId: String): List<UserCorrectionEntity>

    @Query(
        """
        SELECT * FROM user_corrections
        WHERE user_id = :userId
          AND status = 'active'
          AND source_event_id IN (:sourceEventIds)
        ORDER BY updated_at ASC
        """,
    )
    public suspend fun findActiveForSourceEvents(
        userId: String,
        sourceEventIds: List<String>,
    ): List<UserCorrectionEntity>

    @Query(
        """
        SELECT * FROM user_corrections
        WHERE user_id = :userId
          AND status = 'active'
          AND commitment_id IN (:commitmentIds)
        ORDER BY updated_at ASC
        """,
    )
    public suspend fun findActiveForCommitments(
        userId: String,
        commitmentIds: List<String>,
    ): List<UserCorrectionEntity>

    @Query(
        """
        UPDATE user_corrections
        SET sync_status = 'synced',
            failure_reason = NULL,
            updated_at = :updatedAt
        WHERE id IN (:ids)
        """,
    )
    public suspend fun markSynced(ids: List<String>, updatedAt: Instant)

    @Query(
        """
        UPDATE user_corrections
        SET sync_status = 'failed',
            failure_reason = :reason,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    public suspend fun markFailed(id: String, reason: String?, updatedAt: Instant)

    @Query(
        """
        UPDATE user_corrections
        SET applied_at = :appliedAt,
            updated_at = :appliedAt
        WHERE id = :id
        """,
    )
    public suspend fun markApplied(id: String, appliedAt: Instant)

    @Query("DELETE FROM user_corrections WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int
}
