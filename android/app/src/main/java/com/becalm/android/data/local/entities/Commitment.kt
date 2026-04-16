package com.becalm.android.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// spec: data-model — commitments Room entity
// Mirrors Supabase commitments table.
// sync_status is Room-only tracking column.

@Entity(tableName = "commitments")
data class Commitment(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    // spec: CMT-001, CMT-002 — give = user committed to counterparty; take = counterparty committed to user
    @ColumnInfo(name = "direction")
    val direction: String, // enum: give | take

    @ColumnInfo(name = "counterparty_raw")
    val counterpartyRaw: String? = null,

    // spec: data-model — canonicalized counterparty identifier; NULL if unidentifiable
    @ColumnInfo(name = "person_ref")
    val personRef: String? = null,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    // spec: data-model — verbatim text fragment; legally sensitive — app never summarizes or modifies
    @ColumnInfo(name = "quote")
    val quote: String,

    // Denormalized event_title of source raw_ingestion_event — for CommitmentCard display
    @ColumnInfo(name = "source_event_title")
    val sourceEventTitle: String? = null,

    // spec: data-model — timestamp of source event (not extraction time)
    @ColumnInfo(name = "source_event_occurred_at")
    val sourceEventOccurredAt: Long,

    @ColumnInfo(name = "due_date")
    val dueDate: String? = null, // ISO date: "yyyy-MM-dd"

    // spec: CMT-005..CMT-007 — user follow-through tracking
    // enum: pending | reminded | followed_up | completed
    @ColumnInfo(name = "action_state")
    val actionState: String = ActionState.PENDING,

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,

    // LLM extraction confidence [0.0, 1.0]
    @ColumnInfo(name = "confidence")
    val confidence: Float = 0.0f,

    // Room-only sync tracking
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    object ActionState {
        const val PENDING = "pending"
        const val REMINDED = "reminded"
        const val FOLLOWED_UP = "followed_up"
        const val COMPLETED = "completed"
    }

    object SyncStatus {
        const val PENDING = "pending"
        const val SYNCED = "synced"
        const val FAILED = "failed"
        const val QUARANTINED = "quarantined"
    }

    object Direction {
        const val GIVE = "give"
        const val TAKE = "take"
    }
}
