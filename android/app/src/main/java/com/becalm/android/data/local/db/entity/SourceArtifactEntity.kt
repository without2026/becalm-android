package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

public const val SOURCE_ARTIFACT_TYPE_MARKDOWN_ORIGINAL: String = "markdown_original"

/**
 * Room-only metadata for app-private source archive files.
 *
 * The original content lives under `Context.filesDir`; this row stores the relative
 * path and integrity metadata. It must never be mirrored into a network DTO.
 */
@Entity(
    tableName = "source_artifacts",
    indices = [
        Index(
            name = "ux_source_artifacts_user_source_type",
            value = ["user_id", "source_type", "source_ref", "artifact_type"],
            unique = true,
        ),
        Index(
            name = "idx_source_artifacts_user_occurred",
            value = ["user_id", "occurred_at"],
        ),
        Index(
            name = "idx_source_artifacts_raw_event",
            value = ["raw_event_id"],
        ),
    ],
)
public data class SourceArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "raw_event_id")
    val rawEventId: String?,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,
    @ColumnInfo(name = "artifact_type")
    val artifactType: String,
    @ColumnInfo(name = "local_path")
    val localPath: String,
    @ColumnInfo(name = "sha256")
    val sha256: String,
    @ColumnInfo(name = "byte_size")
    val byteSize: Long,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
