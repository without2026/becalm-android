package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(
    tableName = "commitment_progress_events",
    indices = [
        Index(
            name = "idx_commitment_progress_events_user_status",
            value = ["user_id", "status", "created_at"],
        ),
        Index(
            name = "idx_commitment_progress_events_user_commitment",
            value = ["user_id", "commitment_id", "created_at"],
        ),
        Index(
            name = "idx_commitment_progress_events_user_source",
            value = ["user_id", "source_event_id", "created_at"],
        ),
        Index(
            name = "idx_commitment_progress_events_user_person",
            value = ["user_id", "person_id", "created_at"],
        ),
    ],
)
public data class CommitmentProgressEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "commitment_id")
    val commitmentId: String? = null,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,
    @ColumnInfo(name = "conversation_ref")
    val conversationRef: String? = null,
    @ColumnInfo(name = "person_id")
    val personId: String? = null,
    @ColumnInfo(name = "event_type")
    val eventType: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "evidence_quote")
    val evidenceQuote: String,
    @ColumnInfo(name = "reason")
    val reason: String? = null,
    @ColumnInfo(name = "applied_at")
    val appliedAt: Instant? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

public object CommitmentProgressEventStatus {
    public const val AUTO_APPLIED: String = "auto_applied"
    public const val NEEDS_REVIEW: String = "needs_review"
    public const val REJECTED: String = "rejected"
}
