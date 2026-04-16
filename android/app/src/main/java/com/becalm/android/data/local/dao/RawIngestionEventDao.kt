package com.becalm.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.becalm.android.data.local.entities.RawIngestionEvent
import kotlinx.coroutines.flow.Flow

// spec: ING-001, ING-002, ING-003, ING-011

@Dao
interface RawIngestionEventDao {

    // spec: ING-001 — INSERT from ContentObserver or catch-up adapter
    // OnConflictStrategy.IGNORE implements the idempotency invariant:
    // duplicate client_event_id inserts are silently dropped (same as ING-015 on server side)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: RawIngestionEvent): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<RawIngestionEvent>): List<Long>

    // spec: ING-002 — UploadWorker queries pending batch
    @Query("SELECT * FROM raw_ingestion_events WHERE sync_status = :status ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPendingBatch(
        status: String = RawIngestionEvent.SyncStatus.PENDING,
        limit: Int = 100
    ): List<RawIngestionEvent>

    // spec: ING-002 — mark as synced after successful Railway ack
    @Query("UPDATE raw_ingestion_events SET sync_status = 'synced', last_attempt_at = :now WHERE client_event_id IN (:ids)")
    suspend fun markSynced(ids: List<String>, now: Long = System.currentTimeMillis())

    // spec: ING-003 — increment retry_count + set failed status
    @Query("""
        UPDATE raw_ingestion_events
        SET sync_status = 'failed', retry_count = retry_count + 1, last_attempt_at = :now
        WHERE client_event_id IN (:ids)
    """)
    suspend fun markFailed(ids: List<String>, now: Long = System.currentTimeMillis())

    // spec: ING-003 — quarantine on 400/413/422; no retry
    @Query("UPDATE raw_ingestion_events SET sync_status = 'quarantined', last_attempt_at = :now WHERE client_event_id IN (:ids)")
    suspend fun markQuarantined(ids: List<String>, now: Long = System.currentTimeMillis())

    // spec: ING-003 — reset failed back to pending for retryable items (after backoff)
    @Query("UPDATE raw_ingestion_events SET sync_status = 'pending' WHERE sync_status = 'failed' AND retry_count < :maxRetries")
    suspend fun resetFailedForRetry(maxRetries: Int = 5)

    // spec: ING-011 — catch-up adapter uses this to check last inserted timestamp per source
    @Query("SELECT MAX(timestamp) FROM raw_ingestion_events WHERE source_type = :sourceType")
    suspend fun getLatestTimestampForSource(sourceType: String): Long?

    // spec: TDY-001..TDY-004 — today timeline query
    @Query("""
        SELECT * FROM raw_ingestion_events
        WHERE timestamp >= :startOfDay AND timestamp < :endOfDay
        ORDER BY timestamp DESC
    """)
    fun observeTodayEvents(startOfDay: Long, endOfDay: Long): Flow<List<RawIngestionEvent>>

    // spec: SRC-002 — PersonDetailScreen interaction history
    @Query("""
        SELECT * FROM raw_ingestion_events
        WHERE person_ref = :personRef
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getEventsByPersonRef(personRef: String, limit: Int = 20, offset: Int = 0): List<RawIngestionEvent>

    // spec: SRC-003 — unassigned events (person_ref IS NULL)
    @Query("SELECT * FROM raw_ingestion_events WHERE person_ref IS NULL ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getUnassignedEvents(limit: Int = 20, offset: Int = 0): List<RawIngestionEvent>

    // spec: ING-011 — overall sync status for OverallSyncIndicator
    @Query("SELECT COUNT(*) FROM raw_ingestion_events WHERE sync_status = 'pending'")
    fun observePendingCount(): Flow<Int>

    // spec: ENR-001 — get distinct non-null person_refs for enrichment
    @Query("SELECT DISTINCT person_ref FROM raw_ingestion_events WHERE person_ref IS NOT NULL")
    suspend fun getDistinctPersonRefs(): List<String>

    // spec: VOI-001 — update event_snippet after STT completes
    @Query("UPDATE raw_ingestion_events SET event_snippet = :snippet, commitments_extracted_count = :count WHERE client_event_id = :id")
    suspend fun updateSnippetAndCommitmentsCount(id: String, snippet: String, count: Int)

    // spec: data-model — delete on logout (cursor only; Room data preserved per AUTH-005)
    // Note: AUTH-005 says Room DB is NOT deleted on logout. Cursors in DataStore are deleted separately.
    @Query("SELECT COUNT(*) FROM raw_ingestion_events")
    suspend fun count(): Int
}
