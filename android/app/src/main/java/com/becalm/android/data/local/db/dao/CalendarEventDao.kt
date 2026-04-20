package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
     * Inserts a batch of calendar events, replacing any row whose primary key conflicts.
     *
     * @param entities The list of entities to persist.
     * @return List of SQLite rowids in the same order as [entities].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entities: List<CalendarEventEntity>): List<Long>

    // ── Reactive queries ─────────────────────────────────────────────────────

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
}
