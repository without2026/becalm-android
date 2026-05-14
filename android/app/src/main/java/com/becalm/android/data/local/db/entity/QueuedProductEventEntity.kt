package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

@Entity(
    tableName = "queued_product_events",
    indices = [
        Index(name = "idx_queued_product_events_created", value = ["created_at"]),
        Index(name = "ux_queued_product_events_event_id", value = ["event_id"], unique = true),
    ],
)
public data class QueuedProductEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "event_name")
    val eventName: String,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "properties_json")
    val propertiesJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
