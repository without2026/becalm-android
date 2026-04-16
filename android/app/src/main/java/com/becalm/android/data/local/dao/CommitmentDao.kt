package com.becalm.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.becalm.android.data.local.entities.Commitment
import kotlinx.coroutines.flow.Flow

// spec: CMT-001..CMT-010, SYNC-001..SYNC-006

@Dao
interface CommitmentDao {

    // spec: VOI-002 — LLM extraction inserts commitments
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(commitment: Commitment): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(commitments: List<Commitment>): List<Long>

    @Update
    suspend fun update(commitment: Commitment)

    // spec: CMT-010 — list all, filter by direction + action_state
    @Query("""
        SELECT * FROM commitments
        WHERE (:direction IS NULL OR direction = :direction)
          AND (:actionState IS NULL OR action_state = :actionState)
        ORDER BY COALESCE(due_date, '9999-12-31') ASC, created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getFiltered(
        direction: String? = null,
        actionState: String? = null,
        limit: Int = 20,
        offset: Int = 0
    ): List<Commitment>

    // spec: CMT-003 — get single commitment
    @Query("SELECT * FROM commitments WHERE id = :id")
    suspend fun getById(id: String): Commitment?

    // spec: CMT-005..CMT-007 — update action_state
    @Query("UPDATE commitments SET action_state = :actionState, updated_at = :now WHERE id = :id")
    suspend fun updateActionState(id: String, actionState: String, now: Long = System.currentTimeMillis())

    // spec: TDY-004 — today's due commitments for timeline
    @Query("""
        SELECT * FROM commitments
        WHERE due_date = :today
          AND action_state != 'completed'
        ORDER BY direction ASC, created_at DESC
    """)
    fun observeTodayDueCommitments(today: String): Flow<List<Commitment>>

    // spec: SRC-002 — PersonDetailScreen: commitments by person_ref
    @Query("""
        SELECT * FROM commitments
        WHERE person_ref = :personRef
        ORDER BY COALESCE(due_date, '9999-12-31') ASC, created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getByPersonRef(personRef: String, limit: Int = 20, offset: Int = 0): List<Commitment>

    // spec: SRC-001 — PersonCard DNBadge: pending commitment count per person
    @Query("""
        SELECT person_ref, COUNT(*) as pending_count
        FROM commitments
        WHERE action_state = 'pending' AND person_ref IS NOT NULL
        GROUP BY person_ref
    """)
    suspend fun getPendingCountPerPerson(): List<PersonPendingCount>

    // spec: SYNC-001 — get pending commitments for upload batch
    @Query("SELECT * FROM commitments WHERE sync_status = 'pending' ORDER BY created_at ASC LIMIT :limit")
    suspend fun getPendingBatch(limit: Int = 100): List<Commitment>

    @Query("UPDATE commitments SET sync_status = 'synced' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE commitments SET sync_status = 'failed', updated_at = :now WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<String>, now: Long = System.currentTimeMillis())

    // Upsert from server response (GET /v1/commitments)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(commitments: List<Commitment>)

    @Query("SELECT COUNT(*) FROM commitments")
    suspend fun count(): Int
}

data class PersonPendingCount(val personRef: String, val pendingCount: Int)
