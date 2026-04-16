package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Room DAO for the `calendar_events` table.
 *
 * All suspend functions run on the Room executor — callers do not need to
 * switch dispatchers manually. [Flow]-returning queries emit a new list
 * whenever the underlying table changes (Room's invalidation tracker).
 *
 * Instant columns require a Room TypeConverter supplied by SP-13's converter
 * class registered on the database. No converter logic lives here.
 */
@Dao
public interface CalendarEventDao {

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Inserts a single calendar event, replacing any existing row with the same
     * primary key ([CalendarEventEntity.id]).
     *
     * @param entity The entity to persist.
     * @return The SQLite rowid of the inserted row, or the existing rowid after replace.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entity: CalendarEventEntity): Long

    /**
     * Inserts a batch of calendar events, replacing any row whose primary key conflicts.
     *
     * Prefer this over calling [insert] in a loop to minimise transaction overhead.
     *
     * @param entities The list of entities to persist.
     * @return List of SQLite rowids in the same order as [entities].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entities: List<CalendarEventEntity>): List<Long>

    /**
     * Updates an existing calendar event row matched by primary key.
     *
     * @param entity The entity to update. Must have an [CalendarEventEntity.id] that
     *   already exists in the table; otherwise no row is affected.
     * @return Number of rows updated (0 or 1).
     */
    @Update
    public suspend fun update(entity: CalendarEventEntity): Int

    // ── Point reads ──────────────────────────────────────────────────────────

    /**
     * Returns the calendar event with the given primary key, or null if absent.
     *
     * @param id The UUID primary key to look up.
     */
    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    public suspend fun findById(id: String): CalendarEventEntity?

    /**
     * Looks up a calendar event by its external source reference for deduplication.
     *
     * Used before inserting a server-returned event to detect a locally-cached copy
     * that was originally created with the same external calendar ID.
     *
     * @param userId   Owner user UUID.
     * @param sourceType  Calendar provider string (e.g. "google_calendar").
     * @param sourceRef   External calendar event ID from the provider.
     * @return The matching entity, or null if not found.
     */
    @Query(
        """
        SELECT * FROM calendar_events
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND source_ref  = :sourceRef
        LIMIT 1
        """,
    )
    public suspend fun findBySourceRef(
        userId: String,
        sourceType: String,
        sourceRef: String,
    ): CalendarEventEntity?

    // ── Reactive queries ─────────────────────────────────────────────────────

    /**
     * Returns a [Flow] of upcoming events for [userId] starting at or after [fromInstant],
     * ordered chronologically, capped at [limit] rows.
     *
     * The flow re-emits automatically when the table changes (Room invalidation).
     * Suitable for feeding a home-screen "next events" widget.
     *
     * @param userId      Owner user UUID.
     * @param fromInstant Lower bound (inclusive) on [CalendarEventEntity.startAt].
     * @param limit       Maximum number of events to return.
     */
    @Query(
        """
        SELECT * FROM calendar_events
        WHERE user_id  = :userId
          AND start_at >= :fromInstant
        ORDER BY start_at ASC
        LIMIT :limit
        """,
    )
    public fun observeUpcoming(
        userId: String,
        fromInstant: Instant,
        limit: Int,
    ): Flow<List<CalendarEventEntity>>

    /**
     * Returns a [Flow] of events for [userId] whose [CalendarEventEntity.startAt] falls
     * within [[rangeStart], [rangeEnd]), ordered chronologically.
     *
     * The flow re-emits automatically when the table changes. Intended for the
     * today-timeline view where the caller controls the day window.
     *
     * @param userId     Owner user UUID.
     * @param rangeStart Inclusive lower bound on [CalendarEventEntity.startAt].
     * @param rangeEnd   Exclusive upper bound on [CalendarEventEntity.startAt].
     */
    @Query(
        """
        SELECT * FROM calendar_events
        WHERE user_id  = :userId
          AND start_at >= :rangeStart
          AND start_at <  :rangeEnd
        ORDER BY start_at ASC
        """,
    )
    public fun observeInRange(
        userId: String,
        rangeStart: Instant,
        rangeEnd: Instant,
    ): Flow<List<CalendarEventEntity>>

    // ── Sync helpers ─────────────────────────────────────────────────────────

    /**
     * Returns up to [limit] events for [userId] whose [CalendarEventEntity.syncStatus]
     * equals "pending", for use by the background sync worker.
     *
     * @param userId Owner user UUID.
     * @param limit  Maximum number of pending rows to dequeue per worker run.
     */
    @Query(
        """
        SELECT * FROM calendar_events
        WHERE user_id    = :userId
          AND sync_status = 'pending'
        LIMIT :limit
        """,
    )
    public suspend fun findPendingSync(userId: String, limit: Int): List<CalendarEventEntity>

    /**
     * Marks all rows in [ids] as synced by setting [CalendarEventEntity.syncStatus]
     * to "synced".
     *
     * Called by the sync worker after Railway acknowledges a batch upload.
     *
     * @param ids List of primary-key UUIDs to mark synced.
     */
    @Query("UPDATE calendar_events SET sync_status = 'synced' WHERE id IN (:ids)")
    public suspend fun markSynced(ids: List<String>)

    // ── Deletion ─────────────────────────────────────────────────────────────

    /**
     * Deletes all calendar events owned by [userId].
     *
     * Called on logout to clear the local cache per the local-first data policy.
     *
     * @param userId Owner user UUID.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM calendar_events WHERE user_id = :userId")
    public suspend fun deleteAllForUser(userId: String): Int

    /**
     * Deletes past events for [userId] whose [CalendarEventEntity.startAt] is strictly
     * before [beforeInstant].
     *
     * Intended for periodic housekeeping to bound table growth. Callers should pass
     * a sensible retention cutoff (e.g. 30 days ago).
     *
     * @param userId        Owner user UUID.
     * @param beforeInstant Exclusive upper bound — events starting before this are deleted.
     * @return Number of rows deleted.
     */
    @Query("DELETE FROM calendar_events WHERE user_id = :userId AND start_at < :beforeInstant")
    public suspend fun deleteStaleBefore(userId: String, beforeInstant: Instant): Int
}
