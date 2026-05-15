package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface SelfIdentityAnchorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entities: List<SelfIdentityAnchorEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: SelfIdentityAnchorEntity): Long

    @Query(
        """
        SELECT * FROM self_identity_anchors
        WHERE user_id = :userId
        ORDER BY status ASC, anchor_type ASC, normalized_value ASC
        """,
    )
    public fun observeAll(userId: String): Flow<List<SelfIdentityAnchorEntity>>

    @Query(
        """
        SELECT * FROM self_identity_anchors
        WHERE user_id = :userId AND status = 'active'
        ORDER BY anchor_type ASC, normalized_value ASC
        """,
    )
    public fun observeActive(userId: String): Flow<List<SelfIdentityAnchorEntity>>

    @Query("SELECT * FROM self_identity_anchors WHERE id = :id LIMIT 1")
    public suspend fun findById(id: String): SelfIdentityAnchorEntity?

    @Query(
        """
        SELECT * FROM self_identity_anchors
        WHERE user_id = :userId
          AND status = 'active'
          AND (scope != 'source_event' OR source_event_id = :sourceEventId)
        """,
    )
    public suspend fun findActiveForMatching(userId: String, sourceEventId: String): List<SelfIdentityAnchorEntity>

    @Query("DELETE FROM self_identity_anchors WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int

    @Query("DELETE FROM self_identity_anchors WHERE user_id = :userId AND id NOT IN (:ids)")
    public suspend fun deleteMissingForUser(userId: String, ids: List<String>): Int
}
