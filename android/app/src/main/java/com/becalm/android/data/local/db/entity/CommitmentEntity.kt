package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

public object CommitmentItemType {
    public const val ACTION: String = "action"
    public const val SCHEDULE: String = "schedule"
    public const val DECISION: String = "decision"
}

public object CommitmentScheduleStatus {
    public const val CONFIRMED: String = "confirmed"
    public const val CHANGED: String = "changed"
    public const val POSTPONED: String = "postponed"
    public const val CANCELLED: String = "cancelled"
    public const val FOLLOW_UP: String = "follow_up"
}

public object CommitmentDecisionStatus {
    public const val APPROVED: String = "approved"
    public const val REJECTED: String = "rejected"
    public const val CHOSEN: String = "chosen"
    public const val DEFERRED: String = "deferred"
    public const val ONGOING: String = "ongoing"
}

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
 *   Logical foreign key: `user_id → auth.users.id` (many-to-one, ON DELETE CASCADE)
 *   per `.spec/contracts/data-model.yml` (relationships, lines 258-261). The constraint
 *   is enforced at the Railway/Supabase layer; it is intentionally NOT declared as a
 *   Room `@ForeignKey` here because `auth.users` is managed by Supabase Auth and has
 *   no corresponding Room entity on-device (data-model.yml migration_notes, line 269).
 * @property itemType Trackable-item discriminator. Valid values: "action" | "schedule" | "decision".
 * @property direction Commitment direction from the authenticated user's perspective.
 *   Valid values: "give" (user owes counterparty) | "take" (counterparty owes user).
 *   Null for non-action rows.
 * @property scheduleStatus Schedule change subtype. Non-null only when [itemType] is "schedule".
 * @property decisionStatus Decision subtype. Non-null only when [itemType] is "decision".
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
 * @property dueAt Optional deadline timestamp (UTC epoch milliseconds via Room
 *   converter; ISO-8601 timestamptz on the wire). Null when no due date was extracted
 *   or set. Replaces the pre-v4 `due_date` column per data-model.yml:132-144 and
 *   data-model.yml:471 (v3→v4 migration). Render in Asia/Seoul at the UI layer.
 * @property dueHint Optional verbatim due-date expression captured from the source
 *   (e.g. "다음주", "월말"). Preserved regardless of whether [dueAt] could be resolved
 *   to an absolute instant — see VOI-003. Null when the LLM did not surface a hint.
 * @property dueIsApproximate True when [dueAt] was inferred from a fuzzy hint rather
 *   than an explicit calendar reference. UI renders a `~` prefix on the D-N badge in
 *   this case. Default: false (not approximate).
 * @property actionState User's follow-through action state.
 *   Valid values: "pending" | "reminded" | "followed_up" | "completed".
 *   Default: "pending".
 * @property sourceType Source type of the originating raw ingestion event.
 *   Valid values: "voice" | "call_recording" | "gmail" | "outlook_mail" | "naver_imap" |
 *   "daum_imap" | "google_calendar" | "outlook_calendar" | "manual".
 *   Inherited verbatim from [RawIngestionEventEntity.sourceType] per VOI-001
 *   (voice-pipeline.spec.yml:18); "manual" is reserved for user-created commitments
 *   via CommitmentCreateSheet (MAN-001..006) which have no raw ingestion row.
 * @property sourceRef Source-system reference linking back to the originating raw event.
 *   Null when no stable external reference exists.
 * @property confidence LLM confidence score for this extraction, in [0.0, 1.0].
 *   Higher values indicate greater extraction confidence. Default: 0.0.
 * @property commitmentState **Dead column** retained for Room schema parity only.
 *   Historically held the SP-36 legacy lifecycle state (DRAFT / CONFIRMED / SCHEDULED /
 *   DONE / DISMISSED); as of Wave 4 the spec-aligned lifecycle lives on
 *   [actionState] and is consumed via
 *   [com.becalm.android.domain.commitment.CommitmentState]. No production code reads
 *   or writes this field beyond Room serialization — a future
 *   `db-commitment-drop-commitment-state-column` migration will drop the underlying
 *   TEXT column entirely. Typed as [CommitmentLifecycleLegacy] purely so Room can
 *   still decode rows written by pre-Wave-4 app versions.
 * @property syncStatus Room-only tracking column for Railway upload state.
 *   Valid values: "pending" | "synced" | "failed". Never uploaded to Supabase.
 *   Default: "pending".
 * @property createdAt Server-assigned creation timestamp. Set by Railway on first insert.
 * @property updatedAt Server-assigned last-update timestamp. Refreshed on every Railway
 *   PATCH and mirrored locally by [CommitmentDao.updateActionState].
 * @property lastEditedBy Supabase auth.users UUID of the user who most recently edited
 *   this commitment's mutable fields (title / description / due_at / person_ref /
 *   direction). Null when the row has never been user-edited — i.e. values are
 *   exactly as the LLM extractor or manual-create flow first produced them. Populated
 *   by the Stage-5 edit UI (EDIT-001..008) and by MAN-001..006 manual-create flows.
 *   Mirrors `.spec/contracts/data-model.yml:188-210` last_edited_by column. The Room
 *   converter maps nullable TEXT ↔ String?.
 * @property lastEditedAt Timestamp of the most recent user edit. Paired with
 *   [lastEditedBy]: both are null together for untouched rows, both are non-null
 *   together after the first edit. Stored as UTC epoch milliseconds via the Room
 *   [Instant] converter; rendered in Asia/Seoul at the UI layer. Context: EDIT-003
 *   edit-history banner and MAN-006 audit trail.
 * @property quoteDisputed True when the owning user has flagged [quote] as misquoted
 *   (EDIT-005 dispute flow). Defaults to `false` for every row including legacy
 *   backfills — the v4→v5 migration sets `DEFAULT 0`. Once true, the UI surfaces a
 *   visible "disputed" marker on CommitmentCard and the upload path tags the payload
 *   so Railway analytics can track false-positive extractions.
 * @property quoteDisputedAt Timestamp at which the dispute was first raised. Null
 *   while [quoteDisputed] is false; non-null once the flag flips. Never reset once
 *   set (dispute history is append-only per EDIT-005 spec). Stored as UTC epoch
 *   milliseconds via the Room [Instant] converter.
 * @property deletedAt Soft-delete marker timestamp (EDIT-006). **Every SELECT query
 *   in [CommitmentDao] MUST include `AND deleted_at IS NULL`** — this is a MUST-level
 *   invariant from `.spec/contracts/data-model.yml:204-205` that protects the
 *   user-confirmed promise "deleted commitments stay deleted". Hard deletes are
 *   reserved for `DELETE FROM commitments WHERE user_id = :userId` sign-out purge
 *   (which has no deleted_at filter because the intent is total wipe). Null means
 *   the row is live.
 * @property supersedesCommitmentId When non-null, points at the UUID of the prior
 *   commitment row that this row replaced via the EDIT-007 supersede flow
 *   ("I actually meant this other thing"). Both rows stay in the table — the
 *   superseded row is soft-deleted via [deletedAt], and this field preserves the
 *   lineage link for the audit trail. Self-referential foreign key to
 *   [CommitmentEntity.id]; intentionally **not declared as a Room `@ForeignKey`**
 *   (see plan appendix: Room's validator emits a circular-reference warning on
 *   self-references, and the enforcement lives at the Railway/Supabase layer
 *   with `ON DELETE SET NULL`). Application-level invariant: when writing this
 *   field, callers must confirm the referenced row exists and belongs to the
 *   same user_id. See `.spec/contracts/data-model.yml:188-210`.
 */
@Entity(
    tableName = "commitments",
    indices = [
        // idx_commitments_user_action_due — supports CommitmentManagementScreen queries
        Index(value = ["user_id", "item_type", "action_state", "due_at"], name = "idx_commitments_user_action_due"),
        // Supports SyncWorker pending-sync batch reads
        Index(value = ["user_id", "sync_status"]),
        // idx_commitments_user_person_due — supports PersonDetailScreen and /v1/persons/{id}/commitments
        Index(value = ["user_id", "person_ref", "due_at"], name = "idx_commitments_user_person_due"),
        // idx_commitments_user_deleted — backs the `AND deleted_at IS NULL` filter applied to
        // every per-user SELECT query. Covers the common "live rows for this user" scan so the
        // planner avoids a full-table sweep on large histories. Spec: data-model.yml:219-225.
        Index(value = ["user_id", "deleted_at"], name = "idx_commitments_user_deleted"),
        // idx_commitments_supersedes — supports reverse-lookup queries ("what rows supersede X?")
        // and the EDIT-007 audit-trail render. Rarely written, but the index is cheap given
        // how sparse the column will be (most rows are null). Spec: data-model.yml:219-225.
        Index(value = ["supersedes_commitment_id"], name = "idx_commitments_supersedes"),
    ],
)
public data class CommitmentEntity(

    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "item_type", defaultValue = "action")
    val itemType: String = CommitmentItemType.ACTION,

    @ColumnInfo(name = "direction")
    val direction: String?,

    @ColumnInfo(name = "schedule_status")
    val scheduleStatus: String? = null,

    @ColumnInfo(name = "decision_status")
    val decisionStatus: String? = null,

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

    @ColumnInfo(name = "due_at")
    val dueAt: Instant?,

    @ColumnInfo(name = "due_hint")
    val dueHint: String?,

    @ColumnInfo(name = "due_is_approximate", defaultValue = "0")
    val dueIsApproximate: Boolean = false,

    @ColumnInfo(name = "action_state", defaultValue = "pending")
    val actionState: String = "pending",

    @ColumnInfo(name = "source_type")
    val sourceType: String,

    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,

    @ColumnInfo(name = "confidence", defaultValue = "0.0")
    val confidence: Double = 0.0,

    @ColumnInfo(name = "commitment_state", defaultValue = "DRAFT")
    val commitmentState: CommitmentLifecycleLegacy = CommitmentLifecycleLegacy.DRAFT,

    @ColumnInfo(name = "sync_status", defaultValue = "pending")
    val syncStatus: String = "pending",

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,

    @ColumnInfo(name = "last_edited_by")
    val lastEditedBy: String? = null,

    @ColumnInfo(name = "last_edited_at")
    val lastEditedAt: Instant? = null,

    @ColumnInfo(name = "quote_disputed", defaultValue = "0")
    val quoteDisputed: Boolean = false,

    @ColumnInfo(name = "quote_disputed_at")
    val quoteDisputedAt: Instant? = null,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: Instant? = null,

    @ColumnInfo(name = "supersedes_commitment_id")
    val supersedesCommitmentId: String? = null,
)
