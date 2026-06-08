package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.ManualMemoryOutboxEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
public interface ManualMemoryOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: ManualMemoryOutboxEntity)

    @Query(
        """
        SELECT * FROM manual_memory_outbox
        WHERE user_id = :userId
          AND sync_status = 'pending'
        ORDER BY updated_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingForUser(userId: String, limit: Int): List<ManualMemoryOutboxEntity>

    @Query(
        """
        SELECT * FROM manual_memory_outbox
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY updated_at DESC
        """,
    )
    public fun observeForPerson(userId: String, personId: String): Flow<List<ManualMemoryOutboxEntity>>

    @Query(
        """
        SELECT * FROM manual_memory_outbox
        WHERE user_id = :userId
          AND client_memory_id = :clientMemoryId
        """,
    )
    public suspend fun findByKey(userId: String, clientMemoryId: String): ManualMemoryOutboxEntity?

    @Query(
        """
        UPDATE manual_memory_outbox
        SET sync_status = 'pending',
            retry_count = retry_count + 1,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE user_id = :userId
          AND client_memory_id = :clientMemoryId
        """,
    )
    public suspend fun markRetryableFailure(
        userId: String,
        clientMemoryId: String,
        lastError: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE manual_memory_outbox
        SET sync_status = 'failed',
            retry_count = retry_count + 1,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE user_id = :userId
          AND client_memory_id = :clientMemoryId
        """,
    )
    public suspend fun markFailed(
        userId: String,
        clientMemoryId: String,
        lastError: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE manual_memory_outbox
        SET sync_status = 'pending',
            last_error = NULL,
            updated_at = :updatedAt
        WHERE user_id = :userId
          AND person_id = :personId
          AND sync_status = 'failed'
        """,
    )
    public suspend fun markFailedForPersonPending(
        userId: String,
        personId: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        DELETE FROM manual_memory_outbox
        WHERE user_id = :userId
          AND client_memory_id = :clientMemoryId
        """,
    )
    public suspend fun deleteByKey(userId: String, clientMemoryId: String): Int
}
