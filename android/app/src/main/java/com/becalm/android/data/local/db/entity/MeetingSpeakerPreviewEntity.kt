package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

public object MeetingSpeakerPreviewStatus {
    public const val PENDING: String = "meeting_preview_pending"
    public const val REVIEW_REQUIRED: String = "meeting_review_required"
    public const val EXTRACT_PENDING: String = "meeting_extract_pending"
    public const val EXTRACT_RUNNING: String = "meeting_extract_running"
    public const val FAILED: String = "meeting_extract_failed"
    public const val DONE: String = "meeting_extract_done"
}

@Entity(
    tableName = "meeting_speaker_previews",
    indices = [
        Index(
            name = "ux_meeting_speaker_previews_raw_event",
            value = ["raw_event_id"],
            unique = true,
        ),
        Index(
            name = "idx_meeting_speaker_previews_user_status",
            value = ["user_id", "status", "updated_at"],
        ),
        Index(
            name = "idx_meeting_speaker_previews_preview_id",
            value = ["speaker_preview_id"],
        ),
    ],
)
public data class MeetingSpeakerPreviewEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "raw_event_id")
    val rawEventId: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "speaker_preview_id")
    val speakerPreviewId: String?,
    @ColumnInfo(name = "speakers_json")
    val speakersJson: String,
    @ColumnInfo(name = "transcript_segments_json")
    val transcriptSegmentsJson: String?,
    @ColumnInfo(name = "billable_seconds")
    val billableSeconds: Int,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "selected_self_speaker_id")
    val selectedSelfSpeakerId: String?,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
    @ColumnInfo(name = "expires_at")
    val expiresAt: Instant?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
