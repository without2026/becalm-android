package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/*
 * [DAO 인덱스/트랜잭션 규약]
 * - idx_raw_events_user_sync        (user_id, sync_status)            → sync_status 필터링 쿼리 전반
 * - idx_raw_events_user_time        (user_id, timestamp)              → timestamp 정렬/범위 쿼리
 * - idx_raw_events_user_person_time (user_id, person_ref, timestamp)  → person_ref 타임라인
 * - @Transaction 메서드(release / park*AndReturnIds)는 select-then-update
 *   레이스(두 문장 사이에 새 행이 삽입되어 ID 반환에서 누락되는 경우)를 막기 위한 불변식 보장용이다.
 */

/**
 * Room DAO for [RawIngestionEventEntity].
 *
 * All queries that return data once are `suspend` functions executed on Room's
 * internal dispatcher. Observable queries return [Flow] and are collected by
 * the repository layer on the appropriate coroutine scope.
 */
@Dao
public interface RawIngestionEventDao {

    /**
     * Inserts a single event. Uses [OnConflictStrategy.IGNORE] so that a duplicate
     * [RawIngestionEventEntity.clientEventId] (same idempotency key) is silently
     * dropped rather than throwing. The caller should check the returned row ID;
     * `-1L` means the row was ignored.
     *
     * @return The new row ID, or `-1L` if the row was ignored due to a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insert(entity: RawIngestionEventEntity): Long

    /**
     * Inserts a batch of events in a single transaction. Each item uses
     * [OnConflictStrategy.IGNORE] for the same idempotency guarantee as [insert].
     *
     * @return A list of row IDs parallel to [entities]; `-1L` at position N means
     *   the Nth entity was ignored due to a conflict.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertAll(entities: List<RawIngestionEventEntity>): List<Long>

    /** Replaces all columns of an existing row matched by primary key. Returns rows updated (0 or 1). */
    @Update
    public suspend fun update(entity: RawIngestionEventEntity): Int

    /**
     * Looks up an event by its client-generated idempotency key before inserting,
     * allowing callers to skip re-insertion entirely rather than relying solely on
     * the DB conflict strategy.
     */
    @Query(
        """
        SELECT * FROM raw_ingestion_events
        WHERE user_id = :userId
          AND client_event_id = :clientEventId
        LIMIT 1
        """,
    )
    public suspend fun findByClientEventId(
        userId: String,
        clientEventId: String,
    ): RawIngestionEventEntity?

    /**
     * Returns up to [limit] events for [userId] with syncStatus = "pending", ordered oldest-first
     * so that the sync worker uploads in ingestion order.
     */
    @Query(
        """
        SELECT * FROM raw_ingestion_events
        WHERE user_id = :userId
          AND sync_status = 'pending'
        ORDER BY timestamp ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingForUpload(
        userId: String,
        limit: Int,
    ): List<RawIngestionEventEntity>

    /**
     * Marks a set of events as successfully synced in a single UPDATE.
     * Called by the sync worker after a successful Railway batch acknowledgement.
     */
    @Query(
        """
        UPDATE raw_ingestion_events
        SET sync_status = 'synced'
        WHERE id IN (:ids)
        """,
    )
    public suspend fun markSynced(ids: List<String>)

    /**
     * Records a failed upload attempt for a single event: sets syncStatus = "failed",
     * increments retryCount by [retryIncrement], and stamps [now] as lastAttemptAt.
     *
     * The sync worker calls this for each event returned in the Railway
     * `BatchUploadResponse.failed` list where `retryable == true`, and may also
     * call it for transient network errors. Callers set [retryIncrement] to 0
     * when recording a transient error without consuming a retry budget.
     */
    @Query(
        """
        UPDATE raw_ingestion_events
        SET sync_status = 'failed',
            retry_count = retry_count + :retryIncrement,
            last_attempt_at = :now
        WHERE id = :id
        """,
    )
    public suspend fun markFailed(
        id: String,
        retryIncrement: Int = 1,
        now: Instant,
    )

    /**
     * Emits a live list of the most recent events associated with [personRef] for [userId],
     * newest-first. Drives the PersonDetailScreen event timeline.
     */
    @Query(
        """
        SELECT * FROM raw_ingestion_events
        WHERE user_id = :userId
          AND person_ref = :personRef
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    public fun observeRecentForPerson(
        userId: String,
        personRef: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits a live list of the most recent events with no identified counterparty
     * (person_ref IS NULL) for [userId], newest-first. Drives the "Unassigned" group
     * in the main timeline.
     */
    @Query(
        """
        SELECT * FROM raw_ingestion_events
        WHERE user_id = :userId
          AND person_ref IS NULL
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    public fun observeUnassignedRecent(
        userId: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>>

    /**
     * Hard-deletes all events owned by [userId]. Called on logout to clear local data
     * per the local-first privacy model — data that has not yet been synced is discarded
     * without upload.
     */
    @Query(
        """
        DELETE FROM raw_ingestion_events
        WHERE user_id = :userId
        """,
    )
    public suspend fun deleteAllForUser(userId: String): Int

    /**
     * Looks up a single event by its primary key [id], scoped to [userId].
     *
     * The user_id predicate prevents cross-user data leaks in multi-account or stale
     * back-stack scenarios: if one user's event UUID ends up in another user's navigation
     * arguments (e.g. process-death restore), this query returns null rather than the other
     * user's row. PK lookup stays O(1); the user_id check is a constant-time residual filter
     * on the fetched row.
     */
    @Query(
        """
        SELECT * FROM raw_ingestion_events
        WHERE id = :id
          AND user_id = :userId
        LIMIT 1
        """,
    )
    public suspend fun findById(id: String, userId: String): RawIngestionEventEntity?

    /**
     * Returns all voice events for [userId] that are blocked waiting for PIPA consent
     * (sync_status = "awaiting_consent").
     *
     * Test-only consumer (PipaConsentReleaseFlowTest): production code goes through
     * [findAwaitingConsentVoiceIds] / [releaseAwaitingConsentVoiceAndReturnIds].
     *
     * Spec refs: VOI-004.
     */
    @Query(
        "SELECT * FROM raw_ingestion_events " +
            "WHERE user_id = :userId " +
            "AND source_type = 'voice' " +
            "AND sync_status = 'awaiting_consent'",
    )
    public suspend fun findVoiceAwaitingConsent(userId: String): List<RawIngestionEventEntity>

    /**
     * Returns only the IDs (not full entities) of voice events for [userId] that are currently
     * awaiting consent, keeping the payload small for the SELECT + UPDATE transaction.
     *
     * Spec refs: VOI-004.
     */
    @Query(
        "SELECT id FROM raw_ingestion_events " +
            "WHERE user_id = :userId " +
            "AND source_type = 'voice' " +
            "AND sync_status = 'awaiting_consent'",
    )
    public suspend fun findAwaitingConsentVoiceIds(userId: String): List<String>

    /**
     * Flips the sync_status of every voice "awaiting_consent" row for [userId] to "pending".
     *
     * Private UPDATE half of [releaseAwaitingConsentVoiceAndReturnIds]; always invoked inside
     * that @Transaction so it sees the same row set selected by [findAwaitingConsentVoiceIds].
     *
     * Spec refs: VOI-004.
     */
    @Query(
        "UPDATE raw_ingestion_events " +
            "SET sync_status = 'pending' " +
            "WHERE user_id = :userId " +
            "AND source_type = 'voice' " +
            "AND sync_status = 'awaiting_consent'",
    )
    public suspend fun flipAwaitingConsentVoiceToPending(userId: String)

    /**
     * Atomically transitions all voice events for [userId] from "awaiting_consent" to
     * "pending" and returns the IDs of the affected rows so the caller can re-enqueue
     * exactly those [com.becalm.android.worker.VoiceUploadWorker] jobs.
     *
     * The two-step SELECT + UPDATE runs inside a single [Transaction] so no concurrent
     * writer can insert a new awaiting_consent row that slips through without being
     * included in the returned ID list.
     *
     * Sole entry point for the consent-grant flow in
     * [com.becalm.android.ui.settings.SettingsViewModel] (finding #2 fix).
     *
     * Spec refs: VOI-004.
     */
    @Transaction
    public suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): List<String> {
        val ids = findAwaitingConsentVoiceIds(userId)
        if (ids.isNotEmpty()) {
            flipAwaitingConsentVoiceToPending(userId)
        }
        return ids
    }

    /**
     * Returns the IDs of all voice events for [userId] that are in a genuinely in-flight
     * state when consent is withdrawn — i.e. "pending", "queued", or "failed_retryable".
     *
     * "awaiting_consent" rows are intentionally excluded: they are already parked and have
     * no active WorkManager job to cancel. Including them caused spurious cancel calls and
     * re-parked the same rows unnecessarily (finding #1 re-review fix).
     *
     * Spec refs: VOI-004.
     */
    @Query(
        "SELECT id FROM raw_ingestion_events " +
            "WHERE user_id = :userId " +
            "AND source_type = 'voice' " +
            "AND sync_status IN ('pending', 'queued', 'failed_retryable')",
    )
    public suspend fun findCancellableVoiceIds(userId: String): List<String>

    /**
     * Atomically selects the IDs of all voice events for [userId] in an in-flight state
     * ("pending", "queued", "failed_retryable"), then parks exactly those rows to "awaiting_consent".
     *
     * Running SELECT then UPDATE inside a single [Transaction] prevents a new "pending" row
     * inserted between the two statements from being parked without its ID being returned —
     * the exact race the two-call pattern in the repository had (finding #1 fix).
     *
     * Spec refs: VOI-004.
     */
    @Transaction
    public suspend fun parkCancellablePendingVoiceAndReturnIds(userId: String, now: Long): List<String> {
        val ids = findCancellableVoiceIds(userId)
        if (ids.isNotEmpty()) {
            parkVoiceByIds(ids, now)
        }
        return ids
    }

    /**
     * Parks the voice rows whose primary keys are in [ids] to "awaiting_consent".
     * Only called from [parkCancellablePendingVoiceAndReturnIds]; private to that transaction.
     */
    @Query(
        "UPDATE raw_ingestion_events " +
            "SET sync_status = 'awaiting_consent', last_attempt_at = :now " +
            "WHERE id IN (:ids)",
    )
    public suspend fun parkVoiceByIds(ids: List<String>, now: Long)

    /**
     * Deletes every `raw_ingestion_events` row whose `timestamp < :cutoffMillis` AND
     * whose `sync_status = 'synced'` — the two-condition gate mandated by
     * `.spec/data-ingestion.spec.yml:160`:
     *
     * > "Room raw_ingestion_events와 EmailBody는 timestamp 기준 30일 rolling window로
     * >  자동 삭제된다 — 단 sync_status='synced' 조건을 만족할 때만."
     *
     * Pending / failed / awaiting_consent rows are never pruned because the Railway
     * ack contract (`.spec/data-ingestion.spec.yml:151`) forbids local deletion before
     * the server has confirmed receipt. Commitments and calendar events have separate
     * lifecycles and are out of scope for this sweep.
     *
     * Invoked by [com.becalm.android.worker.RetentionSweepWorker] inside a single
     * Room transaction together with
     * [EmailBodyDao.deleteOlderThanForSynced], sharing the same `cutoffMillis` so the
     * two tables are pruned atomically against a consistent clock snapshot.
     *
     * @param cutoffMillis Exclusive upper bound as UTC epoch milliseconds — rows with
     *   `timestamp < :cutoffMillis` are eligible. Callers compute this as
     *   `now - 30.days` via [kotlinx.datetime.Clock.System].
     * @return The number of `raw_ingestion_events` rows deleted. Suitable for
     *   observability logging through [androidx.work.ListenableWorker.Result.success]
     *   output [androidx.work.Data].
     */
    @Query(
        "DELETE FROM raw_ingestion_events " +
            "WHERE sync_status = 'synced' AND timestamp < :cutoffMillis",
    )
    public suspend fun deleteSyncedOlderThan(cutoffMillis: Long): Int

}
