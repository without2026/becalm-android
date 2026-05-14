package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

public object ScheduleEventLinkRelationType {
    public const val CONFIRMS: String = "confirms"
    public const val RESCHEDULES: String = "reschedules"
    public const val CANCELS: String = "cancels"
    public const val CREATES_CANDIDATE: String = "creates_candidate"
    public const val FOLLOW_UP: String = "follow_up"
}

public object ScheduleEventLinkStatus {
    public const val AUTO_LINKED: String = "auto_linked"
    public const val NEEDS_REVIEW: String = "needs_review"
    public const val APPROVED: String = "approved"
    public const val REJECTED: String = "rejected"
}

@Entity(
    tableName = "schedule_event_links",
    indices = [
        Index(value = ["user_id", "calendar_event_id"], name = "idx_schedule_event_links_user_calendar"),
        Index(value = ["user_id", "status", "updated_at"], name = "idx_schedule_event_links_user_status_updated"),
        Index(value = ["user_id", "source_type", "source_ref"], name = "idx_schedule_event_links_user_source"),
    ],
)
public data class ScheduleEventLinkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "calendar_event_id")
    val calendarEventId: String?,

    @ColumnInfo(name = "calendar_source_type")
    val calendarSourceType: String?,

    @ColumnInfo(name = "calendar_source_ref")
    val calendarSourceRef: String?,

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,

    @ColumnInfo(name = "raw_event_id")
    val rawEventId: String?,

    @ColumnInfo(name = "commitment_id")
    val commitmentId: String?,

    @ColumnInfo(name = "relation_type")
    val relationType: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "confidence")
    val confidence: Double,

    @ColumnInfo(name = "proposed_start_at")
    val proposedStartAt: Instant?,

    @ColumnInfo(name = "proposed_end_at")
    val proposedEndAt: Instant?,

    @ColumnInfo(name = "proposed_title")
    val proposedTitle: String?,

    @ColumnInfo(name = "evidence")
    val evidence: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
