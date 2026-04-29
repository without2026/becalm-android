package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.PersonAliasRuleEntity
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.local.db.entity.PersonIndexSourceStateEntity
import com.becalm.android.data.local.db.entity.PersonManualMatchEntity
import com.becalm.android.data.local.db.entity.SourcePersonCandidateEntity
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
    public suspend fun upsertIdentities(rows: List<PersonIdentityEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertInteractions(rows: List<PersonInteractionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertCandidates(rows: List<SourcePersonCandidateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertUnmatchedInteractions(rows: List<UnmatchedPersonInteractionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertManualMatch(row: PersonManualMatchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAliasRule(row: PersonAliasRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertSourceStates(rows: List<PersonIndexSourceStateEntity>)

    @Query("DELETE FROM person_interactions WHERE user_id = :userId")
    public suspend fun deleteInteractionsForUser(userId: String): Int

    @Query("DELETE FROM unmatched_person_interactions WHERE user_id = :userId")
    public suspend fun deleteUnmatchedInteractionsForUser(userId: String): Int

    @Query("DELETE FROM source_person_candidates WHERE user_id = :userId")
    public suspend fun deleteCandidatesForUser(userId: String): Int

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
        DELETE FROM person_index_source_state
        WHERE user_id = :userId
          AND source_type = :sourceType
          AND source_ref = :sourceRef
          AND interaction_kind = :interactionKind
        """,
    )
    public suspend fun deleteSourceState(
        userId: String,
        sourceType: String,
        sourceRef: String,
        interactionKind: String,
    ): Int

    @Query(
        """
        SELECT * FROM source_person_candidates
        WHERE user_id = :userId
        """,
    )
    public suspend fun findCandidatesForUser(userId: String): List<SourcePersonCandidateEntity>

    @Query(
        """
        SELECT * FROM person_manual_matches
        WHERE user_id = :userId
        """,
    )
    public suspend fun findManualMatchesForUser(userId: String): List<PersonManualMatchEntity>

    @Query(
        """
        SELECT * FROM person_alias_rules
        WHERE user_id = :userId
          AND enabled = 1
        ORDER BY LENGTH(normalized_alias) DESC
        """,
    )
    public suspend fun findEnabledAliasRulesForUser(userId: String): List<PersonAliasRuleEntity>

    @Query(
        """
        SELECT * FROM person_index_source_state
        WHERE user_id = :userId
        """,
    )
    public suspend fun findSourceStatesForUser(userId: String): List<PersonIndexSourceStateEntity>

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
