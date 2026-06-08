package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.datetime.Instant

public object ManualMemoryOutboxSyncStatus {
    public const val PENDING: String = "pending"
    public const val FAILED: String = "failed"
}

@Entity(
    tableName = "manual_memory_outbox",
    primaryKeys = ["user_id", "client_memory_id"],
    indices = [
        Index(
            name = "idx_manual_memory_outbox_user_sync_updated",
            value = ["user_id", "sync_status", "updated_at"],
        ),
        Index(
            name = "idx_manual_memory_outbox_user_commitment",
            value = ["user_id", "commitment_id"],
        ),
    ],
)
public data class ManualMemoryOutboxEntity(
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "client_memory_id")
    val clientMemoryId: String,
    @ColumnInfo(name = "person_id")
    val personId: String,
    @ColumnInfo(name = "commitment_id")
    val commitmentId: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "person_display_name")
    val personDisplayName: String,
    @ColumnInfo(name = "origin_channel")
    val originChannel: String,
    @ColumnInfo(name = "memory_kind")
    val memoryKind: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "due_at")
    val dueAt: Instant?,
    @ColumnInfo(name = "due_hint")
    val dueHint: String?,
    @ColumnInfo(name = "payload_hash")
    val payloadHash: String,
    @ColumnInfo(name = "sync_status", defaultValue = "pending")
    val syncStatus: String = ManualMemoryOutboxSyncStatus.PENDING,
    @ColumnInfo(name = "retry_count", defaultValue = "0")
    val retryCount: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
