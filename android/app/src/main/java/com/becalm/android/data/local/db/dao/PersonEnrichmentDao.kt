package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Room DAO for the `persons_enrichment` table.
 *
 * **PIPA INVARIANT — ON-DEVICE ONLY.**
 * All operations in this DAO read from and write to a table that is NEVER uploaded
 * to Railway or Supabase. Contact enrichment data is stored exclusively on the
 * device in compliance with the Korean Personal Information Protection Act
 * (PIPA / 개인정보 보호법). Do NOT add any method that serialises or transmits
 * rows from this table to a remote endpoint.
 *
 * **Upsert semantics.**
 * `EnrichmentWorker` calls [upsert] or [upsertAll] on every sync run.
 * [OnConflictStrategy.REPLACE] ensures the most recent ContactsContract snapshot
 * replaces any stale row for the same [PersonEnrichmentEntity.personRef].
 *
 * **Logout.**
 * [deleteAll] must be called when the user signs out to wipe all contact data from
 * the device database before the Room file is closed or handed to the next session.
 */
@Dao
public interface PersonEnrichmentDao {

    /**
     * Inserts or replaces a single [PersonEnrichmentEntity].
     *
     * Uses [OnConflictStrategy.REPLACE] so that each `EnrichmentWorker` run
     * atomically refreshes stale contact metadata for the given [personRef].
     *
     * @return the row ID of the inserted or replaced row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: PersonEnrichmentEntity): Long

    /**
     * Inserts or replaces a batch of [PersonEnrichmentEntity] rows in a single
     * transaction.
     *
     * Prefer this over repeated [upsert] calls when syncing multiple contacts to
     * avoid per-row transaction overhead.
     *
     * @return a list of row IDs in the same order as [entities].
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsertAll(entities: List<PersonEnrichmentEntity>): List<Long>

    /**
     * Returns the enrichment row for the given [personRef], or null if no row exists.
     *
     * Suitable for one-shot lookups during PersonDetailScreen ViewModel init.
     *
     * @param personRef the canonicalized counterparty identifier to look up.
     */
    @Query("SELECT * FROM persons_enrichment WHERE person_ref = :personRef LIMIT 1")
    public suspend fun findByPersonRef(personRef: String): PersonEnrichmentEntity?

    /**
     * Returns a cold [Flow] that emits the enrichment row for [personRef] whenever
     * the underlying Room row changes, or emits null if no matching row exists.
     *
     * Collect from a ViewModel coroutine scope tied to the UI lifecycle to receive
     * live enrichment updates (e.g. after a background `EnrichmentWorker` run).
     *
     * @param personRef the canonicalized counterparty identifier to observe.
     */
    @Query("SELECT * FROM persons_enrichment WHERE person_ref = :personRef LIMIT 1")
    public fun observeByPersonRef(personRef: String): Flow<PersonEnrichmentEntity?>

    /**
     * Returns a cold [Flow] that emits the full list of enrichment rows ordered by
     * [PersonEnrichmentEntity.personRef], re-emitting on every table change.
     *
     * Used by PersonsScreen to join display names into the virtual persons list
     * derived from `raw_ingestion_events` and `commitments`.
     */
    @Query("SELECT * FROM persons_enrichment ORDER BY person_ref ASC")
    public fun observeAll(): Flow<List<PersonEnrichmentEntity>>

    /**
     * Returns a one-shot snapshot of every enrichment row.
     *
     * Use this for bulk reads that do not need reactivity (e.g., building an
     * in-memory lookup map before processing a batch of raw ingestion events).
     */
    @Query("SELECT * FROM persons_enrichment ORDER BY person_ref ASC")
    public suspend fun findAllSnapshot(): List<PersonEnrichmentEntity>

    /**
     * Returns up to [limit] enrichment rows whose [PersonEnrichmentEntity.lastSyncedAt]
     * is strictly earlier than [beforeInstant], ordered by staleness (oldest first).
     *
     * `EnrichmentWorker` calls this to identify contacts that need incremental
     * re-enrichment without re-scanning the entire ContactsContract on every run.
     *
     * @param beforeInstant exclusive upper bound on [PersonEnrichmentEntity.lastSyncedAt];
     *   rows synced at or after this instant are excluded.
     * @param limit maximum number of stale rows to return in a single worker batch.
     */
    @Query(
        """
        SELECT * FROM persons_enrichment
        WHERE last_synced_at < :beforeInstant
        ORDER BY last_synced_at ASC
        LIMIT :limit
        """,
    )
    public suspend fun findStale(beforeInstant: Instant, limit: Int): List<PersonEnrichmentEntity>

    /**
     * Deletes the enrichment row for the given [personRef].
     *
     * @param personRef the canonicalized counterparty identifier whose row should
     *   be removed.
     * @return the number of rows deleted (0 if no row existed, 1 on success).
     */
    @Query("DELETE FROM persons_enrichment WHERE person_ref = :personRef")
    public suspend fun deleteByPersonRef(personRef: String): Int

    /**
     * Deletes every row in `persons_enrichment`.
     *
     * **Must be called on user logout** to wipe all on-device contact enrichment
     * data before the session ends. This is a PIPA compliance requirement —
     * contact data must not persist beyond the authenticated session that
     * collected it without explicit user consent for extended retention.
     *
     * @return the number of rows deleted.
     */
    @Query("DELETE FROM persons_enrichment")
    public suspend fun deleteAll(): Int
}
