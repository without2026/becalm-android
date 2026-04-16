package com.becalm.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.entities.PersonEnrichment
import kotlinx.coroutines.flow.Flow

// spec: ENR-001..ENR-008 — PIPA: contact data on-device only

@Dao
interface PersonEnrichmentDao {

    // spec: ENR-003 — UPSERT latest contact info by person_ref
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(enrichment: PersonEnrichment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(enrichments: List<PersonEnrichment>)

    @Query("SELECT * FROM persons_enrichment WHERE person_ref = :personRef")
    suspend fun getByPersonRef(personRef: String): PersonEnrichment?

    @Query("SELECT * FROM persons_enrichment WHERE person_ref IN (:personRefs)")
    suspend fun getByPersonRefs(personRefs: List<String>): List<PersonEnrichment>

    // spec: ENR-008 — observe enrichment for PersonCard real-time display updates
    @Query("SELECT * FROM persons_enrichment WHERE person_ref = :personRef")
    fun observeByPersonRef(personRef: String): Flow<PersonEnrichment?>

    // spec: data-model — deleted on logout (PIPA compliance: contacts data cleared when user logs out)
    @Query("DELETE FROM persons_enrichment")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM persons_enrichment")
    suspend fun count(): Int
}
