package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface SourceConnectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entities: List<SourceConnectionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: SourceConnectionEntity): Long

    @Query(
        """
        SELECT * FROM source_connections
        WHERE user_id = :userId
        ORDER BY provider ASC, capability ASC, account_identifier ASC
        """,
    )
    public fun observeAll(userId: String): Flow<List<SourceConnectionEntity>>

    @Query("SELECT * FROM source_connections WHERE id = :id LIMIT 1")
    public suspend fun findById(id: String): SourceConnectionEntity?

    @Query("DELETE FROM source_connections WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int

    @Query("DELETE FROM source_connections WHERE user_id = :userId AND id NOT IN (:ids)")
    public suspend fun deleteMissingForUser(userId: String, ids: List<String>): Int
}
