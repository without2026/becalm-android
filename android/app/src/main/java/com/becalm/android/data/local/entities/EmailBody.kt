package com.becalm.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

// spec: ING-006..ING-008 — email body Room-ONLY entity
// Invariant: EmailBody is NEVER uploaded to Railway or Supabase.
// Gmail/Outlook OAuth tokens and IMAP passwords are stored in Android Keystore,
// never transmitted to Railway.

@Entity(
    tableName = "email_bodies",
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
data class EmailBody(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "raw_ingestion_id")
    val rawIngestionId: String,

    // Full email body text — local only, never uploaded
    @ColumnInfo(name = "body_text")
    val bodyText: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
