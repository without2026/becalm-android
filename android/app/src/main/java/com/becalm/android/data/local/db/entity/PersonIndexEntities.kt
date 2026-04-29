package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

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
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "verified")
    val verified: Boolean,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Instant,
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
            name = "idx_person_interactions_user_person_time",
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
)

/**
 * Source row that looked person-relevant but did not have a resolvable person anchor.
 *
 * These rows power manual repair UI. Once the user matches a row, the exact source tuple is
 * stored in [PersonManualMatchEntity] and the next index rebuild converts it into a normal
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
 * User-confirmed exact mapping from a source row to a canonical person.
 */
@Entity(
    tableName = "person_manual_matches",
    indices = [
        Index(
            name = "ux_person_manual_matches_user_source",
            value = ["user_id", "source_type", "source_ref", "interaction_kind"],
            unique = true,
        ),
        Index(
            name = "idx_person_manual_matches_user_person",
            value = ["user_id", "matched_person_id"],
        ),
    ],
)
public data class PersonManualMatchEntity(
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
    @ColumnInfo(name = "matched_person_id")
    val matchedPersonId: String,
    @ColumnInfo(name = "matched_identity_key")
    val matchedIdentityKey: String,
    @ColumnInfo(name = "nickname")
    val nickname: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

/**
 * User-taught alias that can resolve future rows without requiring another manual match.
 */
@Entity(
    tableName = "person_alias_rules",
    indices = [
        Index(
            name = "ux_person_alias_rules_user_alias_scope",
            value = ["user_id", "normalized_alias", "source_scope"],
            unique = true,
        ),
        Index(
            name = "idx_person_alias_rules_user_person",
            value = ["user_id", "person_id"],
        ),
    ],
)
public data class PersonAliasRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "alias")
    val alias: String,
    @ColumnInfo(name = "normalized_alias")
    val normalizedAlias: String,
    @ColumnInfo(name = "person_id")
    val personId: String,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "source_scope")
    val sourceScope: String?,
    @ColumnInfo(name = "enabled")
    val enabled: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

/**
 * Fingerprint of a source-derived person-index row from the last successful index pass.
 *
 * The index worker uses this table to avoid tearing down and rebuilding every person
 * interaction on each source sync. A row is reprocessed only when its source payload, or the
 * resolver state that can affect matching (manual matches / alias rules), changes.
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
 * Audit table for participant candidates emitted by AI or deterministic source parsing.
 *
 * The current first-pass worker writes deterministic candidates from source rows. Backend
 * Gemini `person_candidates` can be inserted into the same table without changing the
 * resolver contract.
 */
@Entity(
    tableName = "source_person_candidates",
    indices = [
        Index(
            name = "ux_source_person_candidates_user_source_candidate",
            value = ["user_id", "source_type", "source_ref", "candidate_ref"],
            unique = true,
        ),
        Index(
            name = "idx_source_person_candidates_user_source",
            value = ["user_id", "source_type", "source_ref"],
        ),
    ],
)
public data class SourcePersonCandidateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "source_type")
    val sourceType: String,
    @ColumnInfo(name = "source_ref")
    val sourceRef: String,
    @ColumnInfo(name = "candidate_ref")
    val candidateRef: String,
    @ColumnInfo(name = "role")
    val role: String,
    @ColumnInfo(name = "name")
    val name: String?,
    @ColumnInfo(name = "email")
    val email: String?,
    @ColumnInfo(name = "phone")
    val phone: String?,
    @ColumnInfo(name = "organization")
    val organization: String?,
    @ColumnInfo(name = "evidence")
    val evidence: String?,
    @ColumnInfo(name = "confidence")
    val confidence: Double,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
)
