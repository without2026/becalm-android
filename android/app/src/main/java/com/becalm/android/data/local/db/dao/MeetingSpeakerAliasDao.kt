package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.MeetingSpeakerAliasEntity

@Dao
public interface MeetingSpeakerAliasDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: MeetingSpeakerAliasEntity)

    @Query(
        """
        SELECT * FROM meeting_speaker_aliases
        WHERE user_id = :userId
          AND raw_event_id = :rawEventId
        ORDER BY speaker_id ASC
        """,
    )
    public suspend fun findForRawEvent(userId: String, rawEventId: String): List<MeetingSpeakerAliasEntity>
}
