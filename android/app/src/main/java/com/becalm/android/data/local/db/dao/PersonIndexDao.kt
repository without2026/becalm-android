package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIndexDirtySourceEntity
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
)

@Dao
public interface PersonIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertPersons(rows: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertIdentities(rows: List<PersonIdentityEntity>)

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
        """,
    )
    public fun observeSourceEventParticipantsForUser(userId: String): Flow<List<SourceEventParticipantEntity>>

    @Query(
        """
        UPDATE source_event_participants
        SET
            person_id = :personId,
            identity_type = CASE
                WHEN identity_type IS NULL OR identity_type = '' THEN :identityType
                ELSE identity_type
            END,
            normalized_value = CASE
                WHEN normalized_value IS NULL OR normalized_value = '' THEN :normalizedValue
                ELSE normalized_value
            END,
            display_name_raw = COALESCE(display_name_raw, :displayNameHint),
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
          AND resolution_status = 'unresolved'
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
        SELECT COUNT(*) FROM source_event_participants
        WHERE user_id = :userId
          AND resolution_status = 'unresolved'
        """,
    )
    public fun observeUnresolvedSourceEventParticipantCount(userId: String): Flow<Int>

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
        SELECT COUNT(*) FROM unmatched_person_interactions
        WHERE user_id = :userId
        """,
    )
    public suspend fun countUnmatchedInteractions(userId: String): Int

    @Query(
        """
        SELECT
            i.person_id AS personId,
            MAX(idn.display_name_hint) AS displayNameHint,
            MIN(idn.identity_key) AS primaryIdentityKey,
            SUM(CASE WHEN i.interaction_kind != 'commitment' THEN 1 ELSE 0 END) AS eventCount,
            SUM(
                CASE
                    WHEN i.interaction_kind = 'commitment'
                     AND COALESCE(LOWER(i.role), '') != 'decision'
                     AND COALESCE(LOWER(i.status), '') NOT IN ('completed', 'cancelled')
                    THEN 1
                    ELSE 0
                END
            ) AS pendingCommitmentCount,
            GROUP_CONCAT(DISTINCT i.source_type) AS channelSources,
            MAX(i.occurred_at) AS lastInteractionAt,
            MAX(
                CASE
                    WHEN i.occurred_at = latest.latest_at THEN COALESCE(i.snippet, i.title)
                    ELSE NULL
                END
            ) AS lastInteractionSnippet
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
