package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * Room entity mirroring the `commitments` Supabase table (data-model.yml).
 *
 * Railway is the authoritative writer. Android reads commitments from Railway via
 * GET /v1/commitments and upserts them locally with [OnConflictStrategy.REPLACE].
 * The only field Android ever PATCHes back is [actionState].
 *
 * Type converters for [Instant] (↔ Long epoch milliseconds) and [LocalDate]
 * (↔ ISO String) are registered on the database class in SP-13. This entity
 * declares the Kotlin types directly; Room resolves them at compile time via
 * the database-level `@TypeConverters` annotation.
 *
 * @property id Supabase-assigned UUID primary key.
 * @property userId Supabase auth.users UUID of the owning user.
 * @property direction Commitment direction from the authenticated user's perspective.
 *   Valid values: "give" (user owes counterparty) | "take" (counterparty owes user).
 * @property counterpartyRaw Raw uncanonized counterparty identifier as extracted
 *   from the source event (phone number, email, or display name). Null when absent.
 * @property personRef Canonicalized counterparty identifier following the precedence
 *   rule: E.164 phone > lowercase email > normalized display name. Null when the
 *   counterparty is not identifiable.
 * @property title Short commitment title as extracted by the LLM pipeline.
 * @property description Optional longer description. Null when absent.
 * @property quote Verbatim text fragment from the source event from which this
 *   commitment was extracted. Legally sensitive — treated as an evidentiary record.
 *   Never summarized or modified by the app.
 * @property sourceEventTitle Denormalized event title of the originating
 *   [RawIngestionEventEntity]. For display in CommitmentCard. Null when the source
 *   event had no title.
 * @property sourceEventOccurredAt Timestamp of the source event (not extraction time).
 *   Required for attribution display.
 * @property dueDate Optional deadline date (date only, no time component). Null when
 *   no due date was extracted or set.
 * @property actionState User's follow-through action state.
 *   Valid values: "pending" | "reminded" | "followed_up" | "completed".
 *   Default: "pending".
 * @property sourceType Source type of the originating raw ingestion event.
 *   Valid values: "voice" | "gmail" | "outlook_mail" | "naver_imap" | "daum_imap" |
 *   "google_calendar" | "outlook_calendar".
 * @property sourceRef Source-system reference linking back to the originating raw event.
 *   Null when no stable external reference exists.
 * @property confidence LLM confidence score for this extraction, in [0.0, 1.0].
 *   Higher values indicate greater extraction confidence. Default: 0.0.
 * @property syncStatus Room-only tracking column for Railway upload state.
 *   Valid values: "pending" | "synced" | "failed". Never uploaded to Supabase.
 *   Default: "pending".
 * @property createdAt Server-assigned creation timestamp. Set by Railway on first insert.
 * @property updatedAt Server-assigned last-update timestamp. Refreshed on every Railway
 *   PATCH and mirrored locally by [CommitmentDao.updateActionState].
 */
@Entity(
    tableName = "commitments",
    indices = [
        // idx_commitments_user_action_due — supports CommitmentManagementScreen queries
        Index(value = ["user_id", "action_state", "due_date"], name = "idx_commitments_user_action_due"),
        // Supports SyncWorker pending-sync batch reads
        Index(value = ["user_id", "sync_status"]),
        // idx_commitments_user_person_due — supports PersonDetailScreen and /v1/persons/{id}/commitments
        Index(value = ["user_id", "person_ref", "due_date"], name = "idx_commitments_user_person_due"),
    ],
)
public data class CommitmentEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "direction")
    val direction: String,

    @ColumnInfo(name = "counterparty_raw")
    val counterpartyRaw: String?,

    @ColumnInfo(name = "person_ref")
    val personRef: String?,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "quote")
    val quote: String,

    @ColumnInfo(name = "source_event_title")
    val sourceEventTitle: String?,

    @ColumnInfo(name = "source_event_occurred_at")
    val sourceEventOccurredAt: Instant,

    @ColumnInfo(name = "due_date")
    val dueDate: LocalDate?,

    @ColumnInfo(name = "action_state", defaultValue = "pending")
    val actionState: String = "pending",

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,

    @ColumnInfo(name = "confidence", defaultValue = "0.0")
    val confidence: Double = 0.0,

    @ColumnInfo(name = "sync_status", defaultValue = "pending")
    val syncStatus: String = "pending",

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
