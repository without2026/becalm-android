package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

public object UserCorrectionStatus {
    public const val ACTIVE: String = "active"
    public const val SUPERSEDED: String = "superseded"
    public const val REVERTED: String = "reverted"
    public const val FAILED: String = "failed"
}

public object UserCorrectionSyncStatus {
    public const val PENDING: String = "pending"
    public const val SYNCED: String = "synced"
    public const val FAILED: String = "failed"
}

@Entity(
    tableName = "user_corrections",
    indices = [
        Index(value = ["user_id", "idempotency_key"], unique = true, name = "ux_user_corrections_user_idempotency"),
        Index(value = ["user_id", "sync_status", "updated_at"], name = "idx_user_corrections_user_sync_updated"),
        Index(value = ["user_id", "conflict_key", "status"], name = "idx_user_corrections_user_conflict_status"),
        Index(value = ["user_id", "source_event_id"], name = "idx_user_corrections_user_source_event"),
        Index(value = ["user_id", "commitment_id"], name = "idx_user_corrections_user_commitment"),
        Index(value = ["user_id", "updated_at"], name = "idx_user_corrections_user_updated"),
    ],
)
public data class UserCorrectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "domain")
    val domain: String,
    @ColumnInfo(name = "action")
    val action: String,
    @ColumnInfo(name = "target_type")
    val targetType: String,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String?,
    @ColumnInfo(name = "commitment_id")
    val commitmentId: String?,
    @ColumnInfo(name = "from_person_id")
    val fromPersonId: String?,
    @ColumnInfo(name = "to_person_id")
    val toPersonId: String?,
    @ColumnInfo(name = "conflict_key")
    val conflictKey: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "target_fingerprint")
    val targetFingerprint: String?,
    @ColumnInfo(name = "payload_json", defaultValue = "'{}'")
    val payloadJson: String = "{}",
    @ColumnInfo(name = "status", defaultValue = "active")
    val status: String = UserCorrectionStatus.ACTIVE,
    @ColumnInfo(name = "sync_status", defaultValue = "pending")
    val syncStatus: String = UserCorrectionSyncStatus.PENDING,
    @ColumnInfo(name = "failure_reason")
    val failureReason: String?,
    @ColumnInfo(name = "client_created_at")
    val clientCreatedAt: Instant,
    @ColumnInfo(name = "applied_at")
    val appliedAt: Instant?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
