package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.SourceArtifactEntity

@Dao
public interface SourceArtifactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: SourceArtifactEntity)

    @Query(
        """
        SELECT * FROM source_artifacts
        WHERE user_id = :userId
          AND raw_event_id = :rawEventId
          AND artifact_type = :artifactType
        ORDER BY updated_at DESC
        LIMIT 1
        """,
    )
    public suspend fun findForRawEvent(
        userId: String,
        rawEventId: String,
        artifactType: String,
    ): SourceArtifactEntity?

    @Query(
        """
        SELECT * FROM source_artifacts
        WHERE user_id = :userId
          AND occurred_at < :cutoffMillis
        ORDER BY occurred_at ASC
        """,
    )
    public suspend fun findBefore(userId: String, cutoffMillis: Long): List<SourceArtifactEntity>

    @Query(
        "DELETE FROM source_artifacts WHERE user_id = :userId AND id IN (:ids)",
    )
    public suspend fun deleteByIds(userId: String, ids: List<String>): Int

    @Query("DELETE FROM source_artifacts WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int

    @Query("SELECT COUNT(*) FROM source_artifacts WHERE user_id = :userId")
    public suspend fun countForUser(userId: String): Int

    @Query("SELECT COALESCE(SUM(byte_size), 0) FROM source_artifacts WHERE user_id = :userId")
    public suspend fun totalBytesForUser(userId: String): Long

    @Query("SELECT * FROM source_artifacts WHERE user_id = :userId ORDER BY occurred_at DESC")
    public suspend fun findAllForUser(userId: String): List<SourceArtifactEntity>
}
