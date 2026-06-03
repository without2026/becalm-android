package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(
    tableName = "person_action_item_cache",
    indices = [
        Index(value = ["user_id", "status", "urgency_score"], name = "idx_person_action_cache_user_status_urgency"),
        Index(value = ["user_id", "person_id", "status"], name = "idx_person_action_cache_user_person_status"),
        Index(value = ["user_id", "updated_at"], name = "idx_person_action_cache_user_updated"),
        Index(value = ["user_id", "server_watermark"], name = "idx_person_action_cache_user_watermark"),
    ],
)
public data class PersonActionItemCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "person_id")
    val personId: String?,
    @ColumnInfo(name = "person_display_name")
    val personDisplayName: String?,
    @ColumnInfo(name = "person_sort_key")
    val personSortKey: String?,
    @ColumnInfo(name = "surfaces_csv")
    val surfacesCsv: String,
    @ColumnInfo(name = "action_kind")
    val actionKind: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "primary_verb")
    val primaryVerb: String,
    @ColumnInfo(name = "short_reason")
    val shortReason: String,
    @ColumnInfo(name = "commitment_id")
    val commitmentId: String?,
    @ColumnInfo(name = "calendar_event_id")
    val calendarEventId: String?,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String?,
    @ColumnInfo(name = "source_type")
    val sourceType: String?,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,
    @ColumnInfo(name = "due_at")
    val dueAt: Instant?,
    @ColumnInfo(name = "due_hint")
    val dueHint: String?,
    @ColumnInfo(name = "due_is_approximate")
    val dueIsApproximate: Boolean,
    @ColumnInfo(name = "stale_after")
    val staleAfter: Instant?,
    @ColumnInfo(name = "urgency_score")
    val urgencyScore: Double,
    @ColumnInfo(name = "importance_score")
    val importanceScore: Double,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "reason_codes_csv")
    val reasonCodesCsv: String,
    @ColumnInfo(name = "input_watermark")
    val inputWatermark: Instant,
    @ColumnInfo(name = "server_watermark")
    val serverWatermark: Instant,
    @ColumnInfo(name = "computed_at")
    val computedAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "snoozed_until")
    val snoozedUntil: Instant?,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant?,
    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Instant?,
    @ColumnInfo(name = "primary_evidence_kind")
    val primaryEvidenceKind: String? = null,
    @ColumnInfo(name = "primary_evidence_id")
    val primaryEvidenceId: String? = null,
    @ColumnInfo(name = "primary_evidence_source_ref")
    val primaryEvidenceSourceRef: String? = null,
    @ColumnInfo(name = "primary_evidence_occurred_at")
    val primaryEvidenceOccurredAt: Instant? = null,
    @ColumnInfo(name = "primary_evidence_label")
    val primaryEvidenceLabel: String? = null,
    @ColumnInfo(name = "primary_evidence_quote")
    val primaryEvidenceQuote: String? = null,
)
