package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.CommitmentProgressEventEntity
import kotlinx.datetime.Instant

@Dao
public interface CommitmentProgressEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(rows: List<CommitmentProgressEventEntity>)

    @Query(
        """
        SELECT * FROM commitment_progress_events
        WHERE user_id = :userId
          AND event_type = 'completed'
          AND status = 'needs_review'
          AND applied_at IS NULL
          AND reason IS NULL
        ORDER BY created_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingCompletionSignals(
        userId: String,
        limit: Int,
    ): List<CommitmentProgressEventEntity>

    @Query(
        """
        UPDATE commitment_progress_events
        SET commitment_id = :commitmentId,
            status = 'auto_applied',
            reason = :reason,
            applied_at = :appliedAt,
            updated_at = :appliedAt
        WHERE id = :id
        """,
    )
    public suspend fun markAutoApplied(
        id: String,
        commitmentId: String,
        reason: String,
        appliedAt: Instant,
    ): Int

    @Query(
        """
        UPDATE commitment_progress_events
        SET status = 'needs_review',
            reason = :reason,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    public suspend fun markNeedsReview(
        id: String,
        reason: String,
        updatedAt: Instant,
    ): Int

    @Query("DELETE FROM commitment_progress_events WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int
}
