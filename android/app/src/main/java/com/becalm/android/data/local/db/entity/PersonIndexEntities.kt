package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Local mirror of backend `persons`.
 *
 * This is the canonical person node. Local-only enrichment and repair tables may add
 * display data around it, but source/commitment relation rows should point here by
 * `person_id` instead of grouping directly by legacy `person_ref`.
 */
@Entity(
    tableName = "persons",
    indices = [
        Index(
            name = "idx_persons_user_updated",
            value = ["user_id", "updated_at"],
        ),
    ],
)
public data class PersonEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "primary_email")
    val primaryEmail: String?,
    @ColumnInfo(name = "primary_phone")
    val primaryPhone: String?,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
    @ColumnInfo(name = "archived_at")
    val archivedAt: Instant?,
)

/**
 * Stable identity anchor resolved from source data.
 *
 * Identity keys are deterministic strings such as `email:a@example.com`,
 * `phone:+821012341234`, or `name:kim-minsu`. Name-only keys are low-confidence hints;
 * email/phone/contact keys are the merge-safe anchors.
 */
@Entity(
    tableName = "person_identities",
    indices = [
        Index(
            name = "ux_person_identities_user_identity_key",
            value = ["user_id", "identity_key"],
            unique = true,
        ),
        Index(
            name = "ux_person_identities_user_identity",
            value = ["user_id", "identity_type", "normalized_value"],
            unique = true,
        ),
        Index(
            name = "idx_person_identities_user_person",
            value = ["user_id", "person_id"],
        ),
    ],
)
public data class PersonIdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "person_id")
    val personId: String,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "identity_type")
    val identityType: String,
    @ColumnInfo(name = "raw_value")
    val rawValue: String,
    @ColumnInfo(name = "display_name_hint")
    val displayNameHint: String?,
    @ColumnInfo(name = "identity_value", defaultValue = "''")
    val identityValue: String = rawValue,
    @ColumnInfo(name = "normalized_value", defaultValue = "''")
    val normalizedValue: String = identityKey.substringAfter(':', rawValue),
    @ColumnInfo(name = "display_name")
    val displayName: String? = displayNameHint,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "is_primary", defaultValue = "0")
    val isPrimary: Boolean = false,
    @ColumnInfo(name = "verified")
    val verified: Boolean,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Instant,
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Instant = lastSeenAt,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Instant = lastSeenAt,
)

/**
 * One person-linked interaction derived from raw events, calendar events, or commitments.
 */
@Entity(
    tableName = "person_interactions",
    indices = [
        Index(
            name = "ux_person_interactions_user_source_person",
            value = ["user_id", "source_type", "source_ref", "person_id", "interaction_kind"],
            unique = true,
        ),
        Index(
            name = "ux_person_interactions_user_key",
            value = ["user_id", "interaction_key"],
            unique = true,
        ),
        Index(
            name = "idx_person_interactions_user_person_time",
            value = ["user_id", "person_id", "occurred_at"],
        ),
        Index(
            name = "idx_person_interactions_user_person_occurred",
            value = ["user_id", "person_id", "occurred_at"],
        ),
        Index(
            name = "idx_person_interactions_user_time",
            value = ["user_id", "occurred_at"],
        ),
    ],
)
public data class PersonInteractionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "person_id")
    val personId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "interaction_kind")
    val interactionKind: String,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String? = sourceRef.removePrefix("raw:").takeIf { sourceRef.startsWith("raw:") },
    @ColumnInfo(name = "commitment_id")
    val commitmentId: String? = sourceRef.removePrefix("commitment:").takeIf { sourceRef.startsWith("commitment:") },
    @ColumnInfo(name = "interaction_key", defaultValue = "''")
    val interactionKey: String =
        "$userId:$personId:${sourceEventId ?: sourceRef}:${commitmentId.orEmpty()}:$interactionKind",
    @ColumnInfo(name = "interaction_type", defaultValue = "''")
    val interactionType: String = interactionKind,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "direction")
    val direction: String?,
    @ColumnInfo(name = "status")
    val status: String?,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "snippet")
    val snippet: String?,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Instant = occurredAt,
)

/**
 * Local mirror of backend `source_event_participants`.
 *
 * This is the source-level participant table for sender/recipient/caller/attendee/
 * mentioned/reference extraction. Person timeline indexing reads this table directly.
 * Unresolved or user-confirmable people are surfaced directly from this table.
 */
@Entity(
    tableName = "source_event_participants",
    indices = [
        Index(
            name = "idx_source_event_participants_user_event",
            value = ["user_id", "source_event_id"],
        ),
        Index(
            name = "idx_source_event_participants_user_person",
            value = ["user_id", "person_id", "created_at"],
        ),
        Index(
            name = "idx_source_event_participants_unresolved",
            value = ["user_id", "resolution_status", "created_at"],
        ),
    ],
)
public data class SourceEventParticipantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "source_event_id")
    val sourceEventId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String?,
    @ColumnInfo(name = "person_id")
    val personId: String?,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "relation_to_user")
    val relationToUser: String,
    @ColumnInfo(name = "identity_type")
    val identityType: String?,
    @ColumnInfo(name = "normalized_value")
    val normalizedValue: String?,
    @ColumnInfo(name = "display_name_raw")
    val displayNameRaw: String?,
    @ColumnInfo(name = "email_raw")
    val emailRaw: String?,
    @ColumnInfo(name = "phone_raw")
    val phoneRaw: String?,
    @ColumnInfo(name = "organization_raw")
    val organizationRaw: String?,
    @ColumnInfo(name = "evidence")
    val evidence: String?,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "resolution_status")
    val resolutionStatus: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)

/**
 * Local mirror of backend `commitment_participants`.
 */
@Entity(
    tableName = "commitment_participants",
    indices = [
        Index(
            name = "ux_commitment_participants_user_commitment_person_role",
            value = ["user_id", "commitment_id", "person_id", "role"],
            unique = true,
        ),
        Index(
            name = "idx_commitment_participants_user_person",
            value = ["user_id", "person_id", "created_at"],
        ),
    ],
)
public data class CommitmentParticipantEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "commitment_id")
    val commitmentId: String,
    @ColumnInfo(name = "person_id")
    val personId: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "evidence")
    val evidence: String?,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)

/**
 * Source row that looked person-relevant but did not have a resolvable person anchor.
 *
 * These rows power manual repair UI. Once the user matches a row, the matching source
 * participant is resolved and the next index rebuild converts it into a normal
 * [PersonInteractionEntity].
 */
@Entity(
    tableName = "unmatched_person_interactions",
    indices = [
        Index(
            name = "ux_unmatched_person_interactions_user_source",
            value = ["user_id", "source_type", "source_ref", "interaction_kind"],
            unique = true,
        ),
        Index(
            name = "idx_unmatched_person_interactions_user_time",
            value = ["user_id", "occurred_at"],
        ),
    ],
)
public data class UnmatchedPersonInteractionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "interaction_kind")
    val interactionKind: String,
    @ColumnInfo(name = "title")
    val title: String?,
    @ColumnInfo(name = "snippet")
    val snippet: String?,
    @ColumnInfo(name = "suggested_label")
    val suggestedLabel: String?,
    @ColumnInfo(name = "occurred_at")
    val occurredAt: Instant,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)

/**
 * Fingerprint of a source-derived person-index row from the last successful index pass.
 *
 * The index worker uses this table to avoid tearing down and rebuilding every person
 * interaction on each source sync. A row is reprocessed only when its source payload or
 * suppression state changes.
 */
@Entity(
    tableName = "person_index_source_state",
    indices = [
        Index(
            name = "ux_person_index_source_state_user_source",
            value = ["user_id", "source_type", "source_ref", "interaction_kind"],
            unique = true,
        ),
        Index(
            name = "idx_person_index_source_state_user_updated",
            value = ["user_id", "updated_at"],
        ),
    ],
)
public data class PersonIndexSourceStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "interaction_kind")
    val interactionKind: String,
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

/**
 * Room-only queue of source graph keys that need person-index projection refresh.
 *
 * The source/commitment relation tables remain canonical. This table only records which
 * source event or commitment changed so [com.becalm.android.worker.PersonInteractionIndexWorker]
 * can rebuild a small projection slice instead of scanning every source row on each sync.
 */
@Entity(
    tableName = "person_index_dirty_sources",
    indices = [
        Index(
            name = "ux_person_index_dirty_sources_user_source",
            value = ["user_id", "source_type", "source_ref", "interaction_kind"],
            unique = true,
        ),
        Index(
            name = "idx_person_index_dirty_sources_user_updated",
            value = ["user_id", "updated_at"],
        ),
    ],
)
public data class PersonIndexDirtySourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "interaction_kind")
    val interactionKind: String,
    @ColumnInfo(name = "reason")
    val reason: String?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
