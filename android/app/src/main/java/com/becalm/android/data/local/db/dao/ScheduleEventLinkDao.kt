package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

@Dao
public interface ScheduleEventLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entities: List<ScheduleEventLinkEntity>): List<Long>

    @Query(
        """
        SELECT * FROM schedule_event_links
        WHERE user_id = :userId
          AND calendar_event_id = :calendarEventId
        ORDER BY updated_at DESC
        """
    )
    public fun observeByCalendarEvent(userId: String, calendarEventId: String): Flow<List<ScheduleEventLinkEntity>>

    @Query(
        """
        SELECT * FROM schedule_event_links
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND (:sourceRef IS NULL OR source_ref = :sourceRef)
        ORDER BY updated_at DESC
        """
    )
    public fun observeBySourceRef(
        userId: String,
        sourceType: String,
        sourceRef: String?,
    ): Flow<List<ScheduleEventLinkEntity>>

    @Query(
        """
        SELECT * FROM schedule_event_links
        WHERE user_id = :userId
          AND status = 'needs_review'
        ORDER BY updated_at DESC
        """
    )
    public fun observePendingReview(userId: String): Flow<List<ScheduleEventLinkEntity>>

    @Query(
        """
        SELECT * FROM schedule_event_links
        WHERE user_id = :userId
          AND (
              (proposed_start_at IS NOT NULL AND proposed_start_at >= :rangeStart AND proposed_start_at < :rangeEnd)
              OR calendar_event_id IN (:calendarEventIds)
              OR commitment_id IN (:commitmentIds)
          )
        ORDER BY updated_at DESC
        """
    )
    public fun observeForTodayRange(
        userId: String,
        rangeStart: Instant,
        rangeEnd: Instant,
        calendarEventIds: List<String>,
        commitmentIds: List<String>,
    ): Flow<List<ScheduleEventLinkEntity>>

    @Query(
        """
        SELECT * FROM schedule_event_links
        WHERE user_id = :userId
          AND (
              commitment_id IN (:commitmentIds)
              OR raw_event_id IN (:rawEventIds)
              OR calendar_event_id IN (:calendarEventIds)
          )
        ORDER BY updated_at DESC
        """
    )
    public fun observeForProjectionRefs(
        userId: String,
        commitmentIds: List<String>,
        rawEventIds: List<String>,
        calendarEventIds: List<String>,
    ): Flow<List<ScheduleEventLinkEntity>>
}
