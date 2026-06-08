package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.datetime.Instant

@Entity(
    tableName = "person_action_sync_state",
    primaryKeys = ["user_id", "surface_key", "status"],
    indices = [
        Index(value = ["user_id", "updated_at"], name = "idx_person_action_sync_state_user_updated"),
    ],
)
public data class PersonActionSyncStateEntity(
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "surface_key")
    val surfaceKey: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "server_watermark")
    val serverWatermark: Instant,
    @ColumnInfo(name = "recompute_state")
    val recomputeState: String?,
    @ColumnInfo(name = "capacity_state")
    val capacityState: String?,
    @ColumnInfo(name = "capacity_backlog_lag_seconds")
    val capacityBacklogLagSeconds: Int? = null,
    @ColumnInfo(name = "capacity_last_caught_up_at")
    val capacityLastCaughtUpAt: Instant? = null,
    @ColumnInfo(name = "capacity_incident_id")
    val capacityIncidentId: String? = null,
    @ColumnInfo(name = "recovery_actions_json")
    val recoveryActionsJson: String? = null,
    @ColumnInfo(name = "empty_state_json")
    val emptyStateJson: String? = null,
    @ColumnInfo(name = "server_timing_json")
    val serverTimingJson: String? = null,
    @ColumnInfo(name = "server_timing_total_ms")
    val serverTimingTotalMs: Double? = null,
    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
