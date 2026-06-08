package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIndexDirtySourceEntity
import com.becalm.android.data.local.db.entity.PendingSourceParticipantMirrorEntity
import com.becalm.android.data.local.db.entity.PersonMemorySemanticIndexEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.UnmatchedPersonInteractionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

public data class PersonIndexAggregateRow(
    val personId: String,
    val displayNameHint: String?,
    val primaryIdentityKey: String?,
    val eventCount: Int,
    val pendingCommitmentCount: Int,
    val channelSources: String?,
    val lastInteractionAt: Instant?,
    val lastInteractionSnippet: String?,
    val interactionText: String?,
)

public data class PersonIndexStaleLinkedSourceRow(
    val sourceType: String,
    val sourceEventId: String,
)

public data class UnmatchedPersonInteractionWithEmailBodyRow(
    val id: String,
    val userId: String,
    val sourceType: String,
    val sourceRef: String,
    val interactionKind: String,
    val title: String?,
    val snippet: String?,
    val suggestedLabel: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
    val emailFolder: String?,
    val emailBodyPlain: String?,
) {
    public fun toEntity(): UnmatchedPersonInteractionEntity =
        UnmatchedPersonInteractionEntity(
            id = id,
            userId = userId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            interactionKind = interactionKind,
            title = title,
            snippet = snippet,
            suggestedLabel = suggestedLabel,
            occurredAt = occurredAt,
            createdAt = createdAt,
        )
}

@Dao
public interface PersonIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertPersons(rows: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertIdentities(rows: List<PersonIdentityEntity>)

    @Query(
        """
        SELECT * FROM persons
        WHERE user_id = :userId
          AND id IN (:personIds)
        """,
    )
    public suspend fun findPersonsByIds(userId: String, personIds: List<String>): List<PersonEntity>

    @Query(
        """
        SELECT * FROM person_identities
        WHERE user_id = :userId
          AND id IN (:identityIds)
        """,
    )
    public suspend fun findIdentitiesByIds(userId: String, identityIds: List<String>): List<PersonIdentityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertSourceEventParticipants(rows: List<SourceEventParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertCommitmentParticipants(rows: List<CommitmentParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertInteractions(rows: List<PersonInteractionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertUnmatchedInteractions(rows: List<UnmatchedPersonInteractionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertDirtySources(rows: List<PersonIndexDirtySourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertPendingSourceParticipantMirrors(rows: List<PendingSourceParticipantMirrorEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertSemanticIndexes(rows: List<PersonMemorySemanticIndexEntity>)

    @Query("DELETE FROM person_interactions WHERE user_id = :userId")
    public suspend fun deleteInteractionsForUser(userId: String): Int

    @Query("DELETE FROM unmatched_person_interactions WHERE user_id = :userId")
    public suspend fun deleteUnmatchedInteractionsForUser(userId: String): Int

    @Query("DELETE FROM source_event_participants WHERE user_id = :userId")
    public suspend fun deleteSourceEventParticipantsForUser(userId: String): Int

    @Query("DELETE FROM commitment_participants WHERE user_id = :userId")
    public suspend fun deleteCommitmentParticipantsForUser(userId: String): Int

    @Query(
        """
        DELETE FROM commitment_participants
        WHERE user_id = :userId
          AND id NOT IN (:keepIds)
        """,
    )
    public suspend fun deleteCommitmentParticipantsForUserExcept(userId: String, keepIds: List<String>): Int

    @Query(
        """
        DELETE FROM person_interactions
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun deleteInteractionsForSource(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): Int

    @Query(
        """
        DELETE FROM person_interactions
        WHERE user_id = :userId
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun deleteInteractionsForSourceRefKind(
        userId: String,
        sourceRef: String,
        interactionKind: String,
    ): Int

    @Query(
        """
        SELECT * FROM person_interactions
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun findInteractionsForSource(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): List<PersonInteractionEntity>

    @Query(
        """
        SELECT * FROM person_interactions
        WHERE user_id = :userId
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun findInteractionsForSourceRefKind(
        userId: String,
        sourceRef: String,
        interactionKind: String,
    ): List<PersonInteractionEntity>

    @Query(
        """
        SELECT DISTINCT person_id FROM person_interactions
        WHERE user_id = :userId
        """,
    )
    public suspend fun findInteractionPersonIdsForUser(userId: String): List<String>

    @Query(
        """
        DELETE FROM unmatched_person_interactions
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun deleteUnmatchedInteractionsForSource(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): Int

    @Query(
        """
        DELETE FROM unmatched_person_interactions
        WHERE user_id = :userId
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun deleteUnmatchedInteractionsForSourceRefKind(
        userId: String,
        sourceRef: String,
        interactionKind: String,
    ): Int

    @Query(
        """
        DELETE FROM person_index_dirty_sources
        WHERE user_id = :userId
          AND id IN (:ids)
        """,
    )
    public suspend fun deleteDirtySourcesByIds(userId: String, ids: List<String>): Int

    @Query(
        """
        DELETE FROM pending_source_participant_mirrors
        WHERE user_id = :userId
          AND participant_id IN (:participantIds)
        """,
    )
    public suspend fun deletePendingSourceParticipantMirrors(userId: String, participantIds: List<String>): Int

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
        """,
    )
    public suspend fun findSourceEventParticipantsForUser(userId: String): List<SourceEventParticipantEntity>

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
          AND source_event_id IN (:sourceEventIds)
        """,
    )
    public suspend fun findSourceEventParticipantsForUserAndEventIds(
        userId: String,
        sourceEventIds: List<String>,
    ): List<SourceEventParticipantEntity>

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND (
                source_event_id IN (:sourceEventIds)
             OR source_ref IN (:sourceRefs)
             OR ('raw:' || source_event_id) IN (:sourceRefs)
          )
        """,
    )
    public suspend fun findSourceEventParticipantsForUserAndEventRefs(
        userId: String,
        sourceType: String,
        sourceEventIds: List<String>,
        sourceRefs: List<String>,
    ): List<SourceEventParticipantEntity>

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
          AND id = :participantId
        LIMIT 1
        """,
    )
    public suspend fun findSourceEventParticipantById(
        userId: String,
        participantId: String,
    ): SourceEventParticipantEntity?

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND source_ref IN (:sourceRefs)
          AND person_id IS NOT NULL
          AND relation_to_user IN ('counterparty', 'participant')
          AND resolution_status IN ('resolved', 'person_resolved')
        """,
    )
    public suspend fun findResolvedCounterpartyParticipantsForSourceRefs(
        userId: String,
        sourceType: String,
        sourceRefs: List<String>,
    ): List<SourceEventParticipantEntity>

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
        """,
    )
    public fun observeSourceEventParticipantsForUser(userId: String): Flow<List<SourceEventParticipantEntity>>

    @Query(
        """
        UPDATE source_event_participants
        SET
            person_id = :personId,
            identity_type = CASE
                WHEN identity_type IS NULL OR identity_type = '' OR identity_type = 'speaker_label' THEN :identityType
                ELSE identity_type
            END,
            normalized_value = CASE
                WHEN normalized_value IS NULL OR normalized_value = '' OR identity_type = 'speaker_label' THEN :normalizedValue
                ELSE normalized_value
            END,
            display_name_raw = CASE
                WHEN display_name_raw IS NULL OR display_name_raw = '' THEN :displayNameHint
                WHEN LOWER(REPLACE(REPLACE(display_name_raw, ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*' THEN :displayNameHint
                ELSE display_name_raw
            END,
            email_raw = CASE
                WHEN :identityType = 'email' AND (email_raw IS NULL OR email_raw = '') THEN :rawValue
                ELSE email_raw
            END,
            phone_raw = CASE
                WHEN :identityType = 'phone' AND (phone_raw IS NULL OR phone_raw = '') THEN :rawValue
                ELSE phone_raw
            END,
            resolution_status = 'resolved',
            confidence = CASE
                WHEN confidence < :confidence THEN :confidence
                ELSE confidence
            END
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND resolution_status IN ('unresolved', 'suggested_self')
          AND (
                source_ref = :sourceRef
             OR source_event_id = :sourceEventId
             OR ('raw:' || source_event_id) = :sourceRef
          )
        """,
    )
    public suspend fun resolveUnmatchedSourceEventParticipants(
        userId: String,
        sourceType: String,
        sourceRef: String,
        sourceEventId: String,
        personId: String,
        identityType: String,
        normalizedValue: String,
        rawValue: String,
        displayNameHint: String?,
        confidence: Double,
    ): Int

    @Query(
        """
        UPDATE source_event_participants
        SET relation_to_user = 'self',
            person_id = NULL,
            resolution_status = 'self_resolved',
            confidence = CASE
                WHEN confidence < :confidence THEN :confidence
                ELSE confidence
            END
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND resolution_status IN ('unresolved', 'suggested_self')
          AND (
                source_ref = :sourceRef
             OR source_event_id = :sourceEventId
             OR ('raw:' || source_event_id) = :sourceRef
          )
        """,
    )
    public suspend fun resolveUnmatchedSourceEventParticipantsAsSelf(
        userId: String,
        sourceType: String,
        sourceRef: String,
        sourceEventId: String,
        confidence: Double,
    ): Int

    @Query(
        """
        UPDATE source_event_participants
        SET relation_to_user = 'counterparty',
            person_id = NULL,
            resolution_status = 'unresolved',
            confidence = CASE
                WHEN confidence < :confidence THEN :confidence
                ELSE confidence
            END
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND resolution_status IN ('suggested_self', 'self_resolved')
          AND (
                source_ref = :sourceRef
             OR source_event_id = :sourceEventId
             OR ('raw:' || source_event_id) = :sourceRef
          )
        """,
    )
    public suspend fun rejectSelfSourceEventParticipants(
        userId: String,
        sourceType: String,
        sourceRef: String,
        sourceEventId: String,
        confidence: Double,
    ): Int

    @Query(
        """
        UPDATE source_event_participants
        SET
            person_id = :personId,
            relation_to_user = 'counterparty',
            identity_type = COALESCE(NULLIF(:identityType, ''), identity_type),
            normalized_value = COALESCE(NULLIF(:normalizedValue, ''), normalized_value),
            display_name_raw = COALESCE(NULLIF(:displayNameRaw, ''), display_name_raw),
            email_raw = CASE
                WHEN :identityType = 'email' AND :normalizedValue IS NOT NULL AND :normalizedValue != '' THEN :normalizedValue
                ELSE email_raw
            END,
            phone_raw = CASE
                WHEN :identityType = 'phone' AND :normalizedValue IS NOT NULL AND :normalizedValue != '' THEN :normalizedValue
                ELSE phone_raw
            END,
            resolution_status = 'resolved',
            confidence = CASE
                WHEN confidence < :confidence THEN :confidence
                ELSE confidence
            END
        WHERE user_id = :userId
          AND id = :participantId
        """,
    )
    public suspend fun reassignSourceEventParticipantById(
        userId: String,
        participantId: String,
        personId: String,
        identityType: String?,
        normalizedValue: String?,
        displayNameRaw: String?,
        confidence: Double,
    ): Int

    @Query(
        """
        UPDATE source_event_participants
        SET
            person_id = NULL,
            relation_to_user = 'counterparty',
            resolution_status = 'ignored',
            confidence = CASE
                WHEN confidence < :confidence THEN :confidence
                ELSE confidence
            END
        WHERE user_id = :userId
          AND id = :participantId
        """,
    )
    public suspend fun ignoreSourceEventParticipantById(
        userId: String,
        participantId: String,
        confidence: Double,
    ): Int

    @Query(
        """
        DELETE FROM commitment_participants
        WHERE user_id = :userId
          AND commitment_id IN (:commitmentIds)
        """,
    )
    public suspend fun deleteCommitmentParticipantsForCommitments(
        userId: String,
        commitmentIds: List<String>,
    ): Int

    @Query(
        """
        DELETE FROM person_interactions
        WHERE user_id = :userId
          AND source_event_id = :sourceEventId
        """,
    )
    public suspend fun deleteInteractionsForSourceEvent(
        userId: String,
        sourceEventId: String,
    ): Int

    @Query(
        """
        DELETE FROM person_interactions
        WHERE user_id = :userId
          AND commitment_id = :commitmentId
        """,
    )
    public suspend fun deleteInteractionsForCommitment(
        userId: String,
        commitmentId: String,
    ): Int

    @Query(
        """
        SELECT * FROM commitment_participants
        WHERE user_id = :userId
        """,
    )
    public suspend fun findCommitmentParticipantsForUser(userId: String): List<CommitmentParticipantEntity>

    @Query(
        """
        SELECT * FROM commitment_participants
        WHERE user_id = :userId
          AND commitment_id IN (:commitmentIds)
        """,
    )
    public suspend fun findCommitmentParticipantsForUserAndCommitmentIds(
        userId: String,
        commitmentIds: List<String>,
    ): List<CommitmentParticipantEntity>

    @Query(
        """
        SELECT * FROM person_index_dirty_sources
        WHERE user_id = :userId
        ORDER BY updated_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findDirtySourcesForUser(
        userId: String,
        limit: Int,
    ): List<PersonIndexDirtySourceEntity>

    @Query(
        """
        SELECT * FROM pending_source_participant_mirrors
        WHERE user_id = :userId
        ORDER BY updated_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findPendingSourceParticipantMirrors(
        userId: String,
        limit: Int,
    ): List<PendingSourceParticipantMirrorEntity>

    @Query(
        """
        SELECT DISTINCT i.source_type AS sourceType,
                        i.source_event_id AS sourceEventId
        FROM person_interactions i
        WHERE i.user_id = :userId
          AND i.interaction_kind = 'commitment'
          AND i.source_event_id IS NOT NULL
          AND TRIM(i.source_event_id) != ''
          AND NOT EXISTS (
              SELECT 1
              FROM source_event_participants sep
              WHERE sep.user_id = i.user_id
                AND sep.person_id = i.person_id
                AND sep.source_event_id = i.source_event_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM person_interactions source_i
              WHERE source_i.user_id = i.user_id
                AND source_i.person_id = i.person_id
                AND source_i.source_event_id = i.source_event_id
                AND source_i.interaction_kind != 'commitment'
          )
        LIMIT :limit
        """,
    )
    public suspend fun findStaleLinkedSourceProjectionRows(
        userId: String,
        limit: Int,
    ): List<PersonIndexStaleLinkedSourceRow>

    @Query(
        """
        SELECT DISTINCT i.source_type AS sourceType,
                        i.source_event_id AS sourceEventId
        FROM person_interactions i
        WHERE i.user_id = :userId
          AND i.interaction_kind != 'commitment'
          AND i.source_event_id IS NOT NULL
          AND TRIM(i.source_event_id) != ''
          AND NOT EXISTS (
              SELECT 1
              FROM raw_ingestion_events raw_by_id
              WHERE raw_by_id.user_id = i.user_id
                AND raw_by_id.id = i.source_event_id
          )
          AND NOT EXISTS (
              SELECT 1
              FROM source_event_participants sep
              JOIN raw_ingestion_events raw_by_ref
                ON raw_by_ref.user_id = sep.user_id
               AND raw_by_ref.source_type = sep.source_type
               AND raw_by_ref.source_ref = sep.source_ref
              WHERE sep.user_id = i.user_id
                AND sep.source_type = i.source_type
                AND sep.source_event_id = i.source_event_id
          )
        LIMIT :limit
        """,
    )
    public suspend fun findStaleRawSourceProjectionRows(
        userId: String,
        limit: Int,
    ): List<PersonIndexStaleLinkedSourceRow>

    @Query(
        """
        UPDATE pending_source_participant_mirrors
        SET retry_count = retry_count + 1,
            last_error = :lastError,
            updated_at = :updatedAt
        WHERE user_id = :userId
          AND participant_id = :participantId
        """,
    )
    public suspend fun markPendingSourceParticipantMirrorFailed(
        userId: String,
        participantId: String,
        lastError: String,
        updatedAt: Instant,
    ): Int

    @Query(
        """
        SELECT * FROM unmatched_person_interactions
        WHERE user_id = :userId
        ORDER BY occurred_at DESC
        LIMIT :limit
        """,
    )
    public fun observeUnmatchedInteractions(
        userId: String,
        limit: Int,
    ): Flow<List<UnmatchedPersonInteractionEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM unmatched_person_interactions
        WHERE user_id = :userId
        """,
    )
    public fun observeUnmatchedInteractionCount(userId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(DISTINCT source_ref) FROM unmatched_person_interactions AS unmatched
        WHERE unmatched.user_id = :userId
          AND unmatched.source_type IN ('meeting', 'message_screenshot')
          AND NOT EXISTS (
              SELECT 1
              FROM source_event_participants AS participant
              WHERE participant.user_id = unmatched.user_id
                AND participant.source_type = unmatched.source_type
                AND (
                    ('raw:' || participant.source_event_id) = unmatched.source_ref
                    OR participant.source_event_id = REPLACE(unmatched.source_ref, 'raw:', '')
                    OR participant.source_ref = unmatched.source_ref
                )
          )
        """,
    )
    public fun observeEvidenceImportUnmatchedInteractionCount(userId: String): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM source_event_participants
        WHERE user_id = :userId
          AND resolution_status IN ('unresolved', 'suggested_self')
        """,
    )
    public fun observeUnresolvedSourceEventParticipantCount(userId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM source_event_participants WHERE user_id = :userId")
    public suspend fun countSourceEventParticipantsForUser(userId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM source_event_participants
        WHERE user_id = :userId
          AND source_type = :sourceType
        """,
    )
    public suspend fun countSourceEventParticipantsForUserAndSourceType(
        userId: String,
        sourceType: String,
    ): Int

    @Query(
        """
        SELECT COUNT(DISTINCT COALESCE(NULLIF(source_event_id, ''), NULLIF(source_ref, ''), id))
        FROM source_event_participants
        WHERE user_id = :userId
          AND source_type IN ('meeting', 'message_screenshot')
          AND resolution_status IN ('unresolved', 'suggested_self')
          AND COALESCE(LOWER(relation_to_user), '') != 'self'
          AND NOT (
            LOWER(role) = 'mentioned'
            AND LOWER(relation_to_user) = 'referenced'
          )
          AND NOT (
            COALESCE(identity_type, '') = 'speaker_label'
            OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(normalized_value, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
            OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(display_name_raw, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
            OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(evidence, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
          )
          AND (
            NULLIF(TRIM(COALESCE(email_raw, '')), '') IS NOT NULL
            OR NULLIF(TRIM(COALESCE(phone_raw, '')), '') IS NOT NULL
            OR (
                COALESCE(identity_type, '') NOT IN ('organization', 'speaker_label')
                AND (
                    (
                        NULLIF(TRIM(COALESCE(display_name_raw, '')), '') IS NOT NULL
                        AND NOT LOWER(REPLACE(REPLACE(TRIM(display_name_raw), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                    )
                    OR (
                        NULLIF(TRIM(COALESCE(normalized_value, '')), '') IS NOT NULL
                        AND NOT LOWER(REPLACE(REPLACE(TRIM(normalized_value), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                    )
                )
            )
          )
          AND (
            source_type != 'meeting'
            OR NULLIF(TRIM(COALESCE(email_raw, '')), '') IS NOT NULL
            OR NULLIF(TRIM(COALESCE(phone_raw, '')), '') IS NOT NULL
            OR LOWER(relation_to_user) = 'counterparty'
            OR LOWER(role) = 'counterparty'
          )
        """,
    )
    public fun observeEvidenceImportUnresolvedSourceEventParticipantCount(userId: String): Flow<Int>

    @Query(
        """
        UPDATE source_event_participants
        SET resolution_status = 'ignored'
        WHERE user_id = :userId
          AND source_type IN ('meeting', 'message_screenshot')
          AND resolution_status IN ('unresolved', 'suggested_self')
          AND NOT (
            COALESCE(LOWER(relation_to_user), '') != 'self'
            AND NOT (
                LOWER(role) = 'mentioned'
                AND LOWER(relation_to_user) = 'referenced'
            )
            AND NOT (
                COALESCE(identity_type, '') = 'speaker_label'
                OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(normalized_value, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(display_name_raw, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(evidence, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
            )
            AND (
                NULLIF(TRIM(COALESCE(email_raw, '')), '') IS NOT NULL
                OR NULLIF(TRIM(COALESCE(phone_raw, '')), '') IS NOT NULL
                OR (
                    COALESCE(identity_type, '') NOT IN ('organization', 'speaker_label')
                    AND (
                        (
                            NULLIF(TRIM(COALESCE(display_name_raw, '')), '') IS NOT NULL
                            AND NOT LOWER(REPLACE(REPLACE(TRIM(display_name_raw), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                        )
                        OR (
                            NULLIF(TRIM(COALESCE(normalized_value, '')), '') IS NOT NULL
                            AND NOT LOWER(REPLACE(REPLACE(TRIM(normalized_value), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                        )
                    )
                )
            )
            AND (
                source_type != 'meeting'
                OR NULLIF(TRIM(COALESCE(email_raw, '')), '') IS NOT NULL
                OR NULLIF(TRIM(COALESCE(phone_raw, '')), '') IS NOT NULL
                OR LOWER(relation_to_user) = 'counterparty'
                OR LOWER(role) = 'counterparty'
            )
          )
        """,
    )
    public suspend fun ignoreNonReviewableEvidenceImportParticipants(userId: String): Int

    @Query(
        """
        DELETE FROM unmatched_person_interactions
        WHERE user_id = :userId
          AND source_type IN ('meeting', 'message_screenshot')
          AND EXISTS (
              SELECT 1
              FROM source_event_participants AS participant
              WHERE participant.user_id = unmatched_person_interactions.user_id
                AND participant.source_type = unmatched_person_interactions.source_type
                AND (
                    ('raw:' || participant.source_event_id) = unmatched_person_interactions.source_ref
                    OR participant.source_event_id = REPLACE(unmatched_person_interactions.source_ref, 'raw:', '')
                    OR participant.source_ref = unmatched_person_interactions.source_ref
                )
          )
          AND NOT EXISTS (
              SELECT 1
              FROM source_event_participants AS participant
              WHERE participant.user_id = unmatched_person_interactions.user_id
                AND participant.source_type = unmatched_person_interactions.source_type
                AND (
                    ('raw:' || participant.source_event_id) = unmatched_person_interactions.source_ref
                    OR participant.source_event_id = REPLACE(unmatched_person_interactions.source_ref, 'raw:', '')
                    OR participant.source_ref = unmatched_person_interactions.source_ref
                )
                AND participant.resolution_status IN ('unresolved', 'suggested_self')
                AND COALESCE(LOWER(participant.relation_to_user), '') != 'self'
                AND NOT (
                    LOWER(participant.role) = 'mentioned'
                    AND LOWER(participant.relation_to_user) = 'referenced'
                )
                AND NOT (
                    COALESCE(participant.identity_type, '') = 'speaker_label'
                    OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(participant.normalized_value, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                    OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(participant.display_name_raw, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                    OR LOWER(REPLACE(REPLACE(TRIM(COALESCE(participant.evidence, '')), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                )
                AND (
                    NULLIF(TRIM(COALESCE(participant.email_raw, '')), '') IS NOT NULL
                    OR NULLIF(TRIM(COALESCE(participant.phone_raw, '')), '') IS NOT NULL
                    OR (
                        COALESCE(participant.identity_type, '') NOT IN ('organization', 'speaker_label')
                        AND (
                            (
                                NULLIF(TRIM(COALESCE(participant.display_name_raw, '')), '') IS NOT NULL
                                AND NOT LOWER(REPLACE(REPLACE(TRIM(participant.display_name_raw), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                            )
                            OR (
                                NULLIF(TRIM(COALESCE(participant.normalized_value, '')), '') IS NOT NULL
                                AND NOT LOWER(REPLACE(REPLACE(TRIM(participant.normalized_value), ' ', '_'), '-', '_')) GLOB 'speaker_[0-9]*'
                            )
                        )
                    )
                )
                AND (
                    participant.source_type != 'meeting'
                    OR NULLIF(TRIM(COALESCE(participant.email_raw, '')), '') IS NOT NULL
                    OR NULLIF(TRIM(COALESCE(participant.phone_raw, '')), '') IS NOT NULL
                    OR LOWER(participant.relation_to_user) = 'counterparty'
                    OR LOWER(participant.role) = 'counterparty'
                )
          )
        """,
    )
    public suspend fun deleteEvidenceImportUnmatchedWithoutReviewableParticipants(userId: String): Int

    @Query(
        """
        SELECT * FROM unmatched_person_interactions
        WHERE user_id = :userId
        ORDER BY occurred_at DESC
        LIMIT :limit
        """,
    )
    public suspend fun findUnmatchedInteractions(
        userId: String,
        limit: Int,
    ): List<UnmatchedPersonInteractionEntity>

    @Query(
        """
        SELECT
            unmatched.id AS id,
            unmatched.user_id AS userId,
            unmatched.source_type AS sourceType,
            unmatched.source_ref AS sourceRef,
            unmatched.interaction_kind AS interactionKind,
            COALESCE(
                NULLIF(TRIM(unmatched.title), ''),
                NULLIF(TRIM(raw.event_title), ''),
                (
                    SELECT anchor.title
                    FROM source_event_anchors anchor
                    WHERE anchor.user_id = unmatched.user_id
                      AND anchor.source_type = unmatched.source_type
                      AND NULLIF(TRIM(COALESCE(anchor.title, '')), '') IS NOT NULL
                      AND (
                          anchor.source_event_id = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR anchor.local_raw_event_id = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR anchor.source_ref = unmatched.source_ref
                          OR anchor.source_ref = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR ('raw:' || anchor.source_event_id) = unmatched.source_ref
                          OR ('raw:' || anchor.local_raw_event_id) = unmatched.source_ref
                      )
                    ORDER BY anchor.updated_at DESC
                    LIMIT 1
                ),
                (
                    SELECT COALESCE(c.source_event_title, c.title)
                    FROM commitments c
                    WHERE c.user_id = unmatched.user_id
                      AND c.source_type = unmatched.source_type
                      AND c.deleted_at IS NULL
                      AND NULLIF(TRIM(COALESCE(c.source_event_title, c.title, '')), '') IS NOT NULL
                      AND (
                          c.source_event_id = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR ('raw:' || c.source_event_id) = unmatched.source_ref
                          OR c.source_ref = unmatched.source_ref
                          OR c.source_ref = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                      )
                    ORDER BY c.source_event_occurred_at DESC, c.created_at DESC
                    LIMIT 1
                )
            ) AS title,
            COALESCE(
                NULLIF(TRIM(unmatched.snippet), ''),
                NULLIF(TRIM(raw.event_snippet), ''),
                (
                    SELECT anchor.snippet
                    FROM source_event_anchors anchor
                    WHERE anchor.user_id = unmatched.user_id
                      AND anchor.source_type = unmatched.source_type
                      AND NULLIF(TRIM(COALESCE(anchor.snippet, '')), '') IS NOT NULL
                      AND (
                          anchor.source_event_id = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR anchor.local_raw_event_id = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR anchor.source_ref = unmatched.source_ref
                          OR anchor.source_ref = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR ('raw:' || anchor.source_event_id) = unmatched.source_ref
                          OR ('raw:' || anchor.local_raw_event_id) = unmatched.source_ref
                      )
                    ORDER BY anchor.updated_at DESC
                    LIMIT 1
                ),
                (
                    SELECT c.quote
                    FROM commitments c
                    WHERE c.user_id = unmatched.user_id
                      AND c.source_type = unmatched.source_type
                      AND c.deleted_at IS NULL
                      AND NULLIF(TRIM(COALESCE(c.quote, '')), '') IS NOT NULL
                      AND (
                          c.source_event_id = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                          OR ('raw:' || c.source_event_id) = unmatched.source_ref
                          OR c.source_ref = unmatched.source_ref
                          OR c.source_ref = CASE
                              WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
                              ELSE unmatched.source_ref
                          END
                      )
                    ORDER BY c.source_event_occurred_at DESC, c.created_at DESC
                    LIMIT 1
                )
            ) AS snippet,
            unmatched.suggested_label AS suggestedLabel,
            unmatched.occurred_at AS occurredAt,
            unmatched.created_at AS createdAt,
            raw.folder AS emailFolder,
            SUBSTR(email_body.body_plain, 1, 4000) AS emailBodyPlain
        FROM unmatched_person_interactions unmatched
        LEFT JOIN raw_ingestion_events raw
          ON raw.user_id = unmatched.user_id
         AND raw.id = CASE
             WHEN unmatched.source_ref LIKE 'raw:%' THEN SUBSTR(unmatched.source_ref, 5)
             ELSE unmatched.source_ref
         END
        LEFT JOIN email_body
          ON email_body.raw_event_id = raw.id
        WHERE unmatched.user_id = :userId
        ORDER BY unmatched.occurred_at DESC
        LIMIT :limit
        """,
    )
    public fun observeUnmatchedInteractionsWithEmailBodies(
        userId: String,
        limit: Int,
    ): Flow<List<UnmatchedPersonInteractionWithEmailBodyRow>>

    @Query(
        """
        SELECT COUNT(*) FROM unmatched_person_interactions
        WHERE user_id = :userId
        """,
    )
    public suspend fun countUnmatchedInteractions(userId: String): Int

    @Query(
        """
        SELECT
            i.person_id AS personId,
            COALESCE(
                (
                    SELECT sep.display_name_raw
                    FROM source_event_participants sep
                    WHERE sep.user_id = i.user_id
                      AND sep.person_id = i.person_id
                      AND sep.display_name_raw IS NOT NULL
                      AND TRIM(sep.display_name_raw) != ''
                      AND sep.display_name_raw NOT LIKE '%@%'
                      AND sep.display_name_raw NOT IN ('담당자', '담당자님', '고객', '고객님')
                    ORDER BY sep.confidence DESC, sep.created_at DESC
                    LIMIT 1
                ),
                (
                    SELECT p.display_name
                    FROM persons p
                    WHERE p.user_id = i.user_id
                      AND p.id = i.person_id
                      AND p.archived_at IS NULL
                      AND p.display_name IS NOT NULL
                      AND TRIM(p.display_name) != ''
                      AND p.display_name NOT LIKE '%@%'
                      AND p.display_name NOT IN ('담당자', '담당자님', '고객', '고객님')
                    LIMIT 1
                ),
                MAX(idn.display_name_hint)
            ) AS displayNameHint,
            MIN(idn.identity_key) AS primaryIdentityKey,
            COUNT(DISTINCT CASE WHEN i.interaction_kind != 'commitment' THEN i.id ELSE NULL END) AS eventCount,
            COUNT(
                DISTINCT CASE
                    WHEN i.interaction_kind = 'commitment'
                     AND COALESCE(LOWER(i.role), '') != 'decision'
                     AND COALESCE(LOWER(i.status), '') NOT IN ('completed', 'cancelled')
                    THEN i.id
                    ELSE NULL
                END
            ) AS pendingCommitmentCount,
            GROUP_CONCAT(DISTINCT i.source_type) AS channelSources,
            MAX(i.occurred_at) AS lastInteractionAt,
            MAX(
                CASE
                    WHEN i.occurred_at = latest.latest_at THEN COALESCE(i.snippet, i.title)
                    ELSE NULL
                END
            ) AS lastInteractionSnippet,
            GROUP_CONCAT(COALESCE(i.title, '') || ' ' || COALESCE(i.snippet, ''), ' ') AS interactionText
        FROM person_interactions i
        JOIN (
            SELECT user_id, person_id, MAX(occurred_at) AS latest_at
            FROM person_interactions
            WHERE user_id = :userId
            GROUP BY user_id, person_id
        ) latest
          ON latest.user_id = i.user_id
         AND latest.person_id = i.person_id
        LEFT JOIN person_identities idn
          ON idn.user_id = i.user_id
         AND idn.person_id = i.person_id
        WHERE i.user_id = :userId
        GROUP BY i.person_id
        ORDER BY lastInteractionAt DESC, personId ASC
        LIMIT :limit
        """,
    )
    public fun observeAggregates(userId: String, limit: Int): Flow<List<PersonIndexAggregateRow>>

    @Query(
        """
        SELECT * FROM person_identities
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY verified DESC, confidence DESC, identity_type ASC
        """,
    )
    public fun observeIdentitiesForPerson(userId: String, personId: String): Flow<List<PersonIdentityEntity>>

    @Query(
        """
        SELECT * FROM person_identities
        WHERE user_id = :userId
        ORDER BY verified DESC, confidence DESC, identity_type ASC
        """,
    )
    public fun observeIdentitiesForUser(userId: String): Flow<List<PersonIdentityEntity>>

    @Query(
        """
        SELECT * FROM person_identities
        WHERE user_id = :userId
        ORDER BY verified DESC, confidence DESC, last_seen_at DESC
        """,
    )
    public suspend fun findIdentitiesForUser(userId: String): List<PersonIdentityEntity>

    @Query(
        """
        SELECT * FROM person_memory_semantic_index
        WHERE user_id = :userId
        ORDER BY updated_at DESC
        """,
    )
    public fun observeSemanticIndexesForUser(userId: String): Flow<List<PersonMemorySemanticIndexEntity>>

    @Query(
        """
        SELECT * FROM person_memory_semantic_index
        WHERE user_id = :userId
          AND person_id = :personId
        """,
    )
    public suspend fun findSemanticIndexForPerson(userId: String, personId: String): PersonMemorySemanticIndexEntity?

    @Query(
        """
        SELECT * FROM persons
        WHERE user_id = :userId
          AND id = :personId
          AND archived_at IS NULL
        """,
    )
    public suspend fun findPersonForMemory(userId: String, personId: String): PersonEntity?

    @Query(
        """
        SELECT p.*
        FROM persons p
        JOIN person_identities i
          ON i.user_id = p.user_id
         AND i.person_id = p.id
        WHERE p.user_id = :userId
          AND p.archived_at IS NULL
          AND i.identity_type = :identityType
          AND i.normalized_value = :normalizedValue
        LIMIT 1
        """,
    )
    public suspend fun findPersonForIdentity(
        userId: String,
        identityType: String,
        normalizedValue: String,
    ): PersonEntity?

    @Query(
        """
        SELECT * FROM person_identities
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY verified DESC, confidence DESC, identity_type ASC
        """,
    )
    public suspend fun findIdentitiesForMemory(userId: String, personId: String): List<PersonIdentityEntity>

    @Query(
        """
        SELECT * FROM source_event_participants
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY created_at DESC
        LIMIT :limit
        """,
    )
    public suspend fun findSourceEventParticipantsForMemory(
        userId: String,
        personId: String,
        limit: Int,
    ): List<SourceEventParticipantEntity>

    @Query(
        """
        SELECT * FROM commitment_participants
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY created_at DESC
        LIMIT :limit
        """,
    )
    public suspend fun findCommitmentParticipantsForMemory(
        userId: String,
        personId: String,
        limit: Int,
    ): List<CommitmentParticipantEntity>

    @Query(
        """
        SELECT * FROM person_interactions
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY occurred_at DESC
        LIMIT :limit
        """,
    )
    public suspend fun findInteractionsForMemory(
        userId: String,
        personId: String,
        limit: Int,
    ): List<PersonInteractionEntity>

    @Query(
        """
        SELECT * FROM person_interactions
        WHERE user_id = :userId
          AND person_id = :personId
        ORDER BY occurred_at DESC
        LIMIT :limit
        """,
    )
    public fun observeInteractionsForPerson(
        userId: String,
        personId: String,
        limit: Int,
    ): Flow<List<PersonInteractionEntity>>
}
