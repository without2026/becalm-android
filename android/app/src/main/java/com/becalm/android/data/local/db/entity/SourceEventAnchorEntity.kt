package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.datetime.Instant

/**
 * Local read model that normalizes all source-event identities used by UI joins.
 *
 * Backend-owned sources such as Gmail and Google Calendar are anchored by the server
 * `source_events.id`. Android-owned sources such as IMAP, call recordings, and meetings
 * may first be anchored by the local `raw_ingestion_events.id`. Projection code should
 * read through this table instead of assuming those ids are interchangeable.
 */
@Entity(
    tableName = "source_event_anchors",
    indices = [
        Index(
            name = "ux_source_event_anchors_user_source_event",
            value = ["user_id", "source_type", "source_event_id"],
            unique = true,
        ),
        Index(
            name = "idx_source_event_anchors_user_local_raw",
            value = ["user_id", "local_raw_event_id"],
            unique = true,
        ),
        Index(
            name = "idx_source_event_anchors_user_source_ref",
            value = ["user_id", "source_type", "source_ref"],
        ),
        Index(
            name = "idx_source_event_anchors_user_provider_event",
            value = ["user_id", "provider_event_id"],
        ),
        Index(
            name = "idx_source_event_anchors_user_occurred",
            value = ["user_id", "occurred_at"],
        ),
    ],
)
public data class SourceEventAnchorEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_origin")
    val sourceOrigin: String,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String?,
    @ColumnInfo(name = "local_raw_event_id")
    val localRawEventId: String?,
    @ColumnInfo(name = "source_connection_id")
    val sourceConnectionId: String?,
    @ColumnInfo(name = "source_account_key")
    val sourceAccountKey: String?,
    @ColumnInfo(name = "provider_event_id")
    val providerEventId: String?,
    @ColumnInfo(name = "conversation_ref")
    val conversationRef: String?,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "snippet")
    val snippet: String?,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

public object SourceEventAnchorOrigin {
    public const val BACKEND: String = "backend"
    public const val ANDROID_LOCAL: String = "android_local"
}

public fun stableSourceEventAnchorId(
    userId: String,
    sourceType: String,
    sourceEventId: String?,
    localRawEventId: String?,
    sourceRef: String?,
    providerEventId: String?,
): String {
    val stablePart = listOf(sourceEventId, localRawEventId, sourceRef, providerEventId)
        .firstOrNull { !it.isNullOrBlank() }
        ?: "unknown"
    return UUID.nameUUIDFromBytes(
        "source-event-anchor:$userId:$sourceType:$stablePart".toByteArray(StandardCharsets.UTF_8),
    ).toString()
}
