package com.becalm.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.entities.CalendarEvent
import kotlinx.coroutines.flow.Flow

// spec: ING-009, ING-010, TDY-005

@Dao
interface CalendarEventDao {

    // spec: ING-009, ING-010 — upsert by source_ref for calendar deduplication
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: CalendarEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<CalendarEvent>): List<Long>

    // spec: TDY-005 — today's calendar events for timeline
    @Query("""
        SELECT * FROM calendar_events
        WHERE start_at >= :startOfDay AND start_at < :endOfDay
        ORDER BY start_at ASC
    """)
    fun observeTodayEvents(startOfDay: Long, endOfDay: Long): Flow<List<CalendarEvent>>

    // spec: SYNC-003 — pending calendar events for Railway batch upload
    @Query("SELECT * FROM calendar_events WHERE sync_status = 'pending' LIMIT :limit")
    suspend fun getPendingBatch(limit: Int = 100): List<CalendarEvent>

    @Query("UPDATE calendar_events SET sync_status = 'synced' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT COUNT(*) FROM calendar_events")
    suspend fun count(): Int
}
