package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.ScheduleRowTombstoneEntity

@Dao
public interface ScheduleRowTombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: ScheduleRowTombstoneEntity)

    @Query(
        """
        SELECT * FROM schedule_row_tombstones
        WHERE user_id = :userId
          AND sync_status = 'pending'
        ORDER BY updated_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingSync(userId: String, limit: Int): List<ScheduleRowTombstoneEntity>

    @Query(
        """
        UPDATE schedule_row_tombstones
        SET sync_status = 'synced', updated_at = :updatedAt
        WHERE id IN (:ids)
        """,
    )
    public suspend fun markSynced(ids: List<String>, updatedAt: kotlinx.datetime.Instant)

    @Query(
        """
        UPDATE schedule_row_tombstones
        SET sync_status = 'failed', updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    public suspend fun markFailed(id: String, updatedAt: kotlinx.datetime.Instant)

    @Query("DELETE FROM schedule_row_tombstones WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int
}
