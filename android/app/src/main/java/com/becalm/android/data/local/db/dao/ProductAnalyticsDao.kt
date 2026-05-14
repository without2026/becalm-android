package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.becalm.android.data.local.db.entity.QueuedProductEventEntity

@Dao
public interface ProductAnalyticsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(entity: QueuedProductEventEntity): Long

    @Query(
        """
        SELECT * FROM queued_product_events
        ORDER BY created_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun oldest(limit: Int): List<QueuedProductEventEntity>

    @Query("DELETE FROM queued_product_events WHERE id IN (:ids)")
    public suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM queued_product_events")
    public suspend fun count(): Int

    @Query(
        """
        DELETE FROM queued_product_events
        WHERE id IN (
            SELECT id FROM queued_product_events
            ORDER BY created_at ASC
            LIMIT :count
        )
        """,
    )
    public suspend fun deleteOldest(count: Int)

    @Transaction
    public suspend fun trimToMax(maxQueuedEvents: Int) {
        val overflow = count() - maxQueuedEvents
        if (overflow > 0) deleteOldest(overflow)
    }
}
