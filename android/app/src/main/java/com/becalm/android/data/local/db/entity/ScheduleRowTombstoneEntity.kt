package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/** User-owned tombstone for one visible calendar/meeting schedule row. */
@Entity(
    tableName = "schedule_row_tombstones",
    indices = [
        Index(value = ["user_id", "source_event_id"], unique = true),
        Index(value = ["user_id", "sync_status"]),
        Index(value = ["user_id", "source_type", "source_ref"]),
    ],
)
public data class ScheduleRowTombstoneEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "row_type")
    val rowType: String,

    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String,

    @ColumnInfo(name = "source_type")
    val sourceType: String?,

    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant,

    @ColumnInfo(name = "sync_status", defaultValue = "pending")
    val syncStatus: String = "pending",

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
