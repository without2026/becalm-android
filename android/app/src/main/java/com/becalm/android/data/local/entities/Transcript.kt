package com.becalm.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

// spec: VOI-001 — STT transcript Room-ONLY entity
// Invariant: Transcript is NEVER uploaded to Railway or Supabase.

@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = RawIngestionEvent::class,
            parentColumns = ["client_event_id"],
            childColumns = ["raw_ingestion_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("raw_ingestion_id")]
)
data class Transcript(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "raw_ingestion_id")
    val rawIngestionId: String,

    // Full STT transcript text — local only, never uploaded
    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
