package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.datetime.Instant

@Entity(
    tableName = "meeting_speaker_aliases",
    primaryKeys = ["raw_event_id", "speaker_id"],
    indices = [
        Index(
            name = "idx_meeting_speaker_aliases_user_raw_event",
            value = ["user_id", "raw_event_id"],
        ),
    ],
)
public data class MeetingSpeakerAliasEntity(
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "raw_event_id")
    val rawEventId: String,
    @ColumnInfo(name = "speaker_id")
    val speakerId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
