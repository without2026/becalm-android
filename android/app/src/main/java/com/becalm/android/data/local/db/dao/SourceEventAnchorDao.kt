package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity

@Dao
public interface SourceEventAnchorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(rows: List<SourceEventAnchorEntity>)

    @Query(
        """
        SELECT * FROM source_event_anchors
        WHERE user_id = :userId
          AND (
              source_event_id = :eventRef
              OR local_raw_event_id = :eventRef
              OR source_ref = :eventRef
              OR provider_event_id = :eventRef
              OR ('raw:' || source_event_id) = :eventRef
              OR ('raw:' || local_raw_event_id) = :eventRef
          )
        ORDER BY
            CASE
                WHEN source_event_id = :eventRef THEN 0
                WHEN local_raw_event_id = :eventRef THEN 1
                ELSE 2
            END,
            updated_at DESC
        LIMIT 1
        """,
    )
    public suspend fun findBestForEventRef(userId: String, eventRef: String): SourceEventAnchorEntity?
}

public object NoopSourceEventAnchorDao : SourceEventAnchorDao {
    override suspend fun upsertAll(rows: List<SourceEventAnchorEntity>) = Unit

    override suspend fun findBestForEventRef(
        userId: String,
        eventRef: String,
    ): SourceEventAnchorEntity? = null
}
