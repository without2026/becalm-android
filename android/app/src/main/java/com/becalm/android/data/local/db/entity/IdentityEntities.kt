package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(
    tableName = "self_identity_anchors",
    indices = [
        Index(
            value = ["user_id", "status", "anchor_type", "normalized_value"],
            name = "idx_self_identity_anchors_user_status_value",
        ),
        Index(
            value = ["user_id", "source_connection_id"],
            name = "idx_self_identity_anchors_user_source_connection",
        ),
    ],
)
public data class SelfIdentityAnchorEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "anchor_type")
    val anchorType: String,
    @ColumnInfo(name = "normalized_value")
    val normalizedValue: String,
    @ColumnInfo(name = "display_value")
    val displayValue: String?,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "scope")
    val scope: String,
    @ColumnInfo(name = "source_connection_id")
    val sourceConnectionId: String?,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String?,
    @ColumnInfo(name = "trust")
    val trust: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant?,
)

@Entity(
    tableName = "source_connections",
    indices = [
        Index(
            value = ["user_id", "provider", "capability", "account_identifier"],
            name = "idx_source_connections_user_provider_account",
        ),
        Index(
            value = ["user_id", "ownership", "status"],
            name = "idx_source_connections_user_ownership_status",
        ),
    ],
)
public data class SourceConnectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "provider")
    val provider: String,
    @ColumnInfo(name = "capability")
    val capability: String,
    @ColumnInfo(name = "account_identifier")
    val accountIdentifier: String?,
    @ColumnInfo(name = "account_display_name")
    val accountDisplayName: String?,
    @ColumnInfo(name = "ownership")
    val ownership: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "linked_self_anchor_id")
    val linkedSelfAnchorId: String?,
    @ColumnInfo(name = "last_sync_at")
    val lastSyncAt: Instant?,
    @ColumnInfo(name = "last_error")
    val lastError: String?,
)
