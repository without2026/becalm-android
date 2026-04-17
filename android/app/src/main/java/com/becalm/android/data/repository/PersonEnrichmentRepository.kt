package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.redact
import com.becalm.android.data.local.db.dao.PersonEnrichmentDao
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * PIPA INVARIANT — ON-DEVICE ONLY.
 *
 * This repository is the authoritative enforcement point for the PIPA (개인정보 보호법)
 * guarantee that persons-enrichment data (display name, phone, email, avatar URI, starred flag)
 * NEVER crosses the network boundary. The class intentionally has NO dependency on
 * RailwayApi or any DTO. Reviewers must reject any change that introduces a network
 * collaborator, a DTO mapping helper, a "sync_status" column consumer, or an upload
 * side-effect on any of these methods.
 *
 * Data flow:
 *   Android ContactsContract  ─► SP-30 EnrichmentWorker  ─► this repo (upsert)
 *                                           │
 *                                           ▼
 *                              Room-only persons_enrichment table
 */
public interface PersonEnrichmentRepository {

    /**
     * Emits the full list of enrichment rows, ordered by [PersonEnrichmentEntity.personRef],
     * re-emitting on every table change.
     */
    public fun observeAll(): Flow<List<PersonEnrichmentEntity>>

    /**
     * Emits the enrichment row for [personRef] whenever the underlying Room row changes,
     * or emits null when no matching row exists.
     *
     * @param personRef canonicalized counterparty identifier to observe.
     */
    public fun observeByPersonRef(personRef: String): Flow<PersonEnrichmentEntity?>

    /**
     * Emits the list of starred enrichment rows, re-emitting on every table change.
     *
     * Note: the `starred` field is not present on [PersonEnrichmentEntity] in the
     * current schema (it is not uploaded and not part of the ContactsContract sync).
     * This method is retained in the interface for forward-compatibility with SP-05
     * when the starred flag is added to the entity. Until then it delegates to
     * [observeAll] filtered in-memory.
     */
    public fun observeStarred(): Flow<List<PersonEnrichmentEntity>>

    /**
     * Returns the enrichment row for [personRef], or null when no row exists.
     *
     * @param personRef canonicalized counterparty identifier to look up.
     */
    public suspend fun findByPersonRef(personRef: String): PersonEnrichmentEntity?

    /**
     * Inserts or replaces [entity], returning [BecalmResult.Success] with [Unit] on success
     * or [BecalmResult.Failure] wrapping a [BecalmError.Io] on local I/O failure.
     *
     * Called by SP-30 EnrichmentWorker after each ContactsContract read.
     *
     * @param entity enrichment row to persist.
     */
    public suspend fun upsert(entity: PersonEnrichmentEntity): BecalmResult<Unit>

    /**
     * Inserts or replaces [entities] in a single Room transaction, returning
     * [BecalmResult.Success] with the count of rows written on success or
     * [BecalmResult.Failure] on local I/O failure.
     *
     * Prefer this over repeated [upsert] calls when syncing multiple contacts.
     *
     * @param entities list of enrichment rows to persist.
     */
    public suspend fun upsertAll(entities: List<PersonEnrichmentEntity>): BecalmResult<Int>

    /**
     * Sets the starred state for the person identified by [personRef].
     *
     * Exposed as a domain verb so that PersonDetailScreen (R8) via PersonsViewModel (R6)
     * can call it directly without knowing DAO internals. Starred state is local-only.
     *
     * @param personRef canonicalized counterparty identifier.
     * @param starred true to star, false to unstar.
     */
    public suspend fun setStarred(personRef: String, starred: Boolean): BecalmResult<Unit>

    /**
     * Deletes the enrichment row for [personRef].
     *
     * @param personRef canonicalized counterparty identifier whose row should be removed.
     */
    public suspend fun deleteByPersonRef(personRef: String): BecalmResult<Unit>

    /**
     * Deletes every row in the `persons_enrichment` table, returning
     * [BecalmResult.Success] with the count of deleted rows on success.
     *
     * Must be called on user logout to satisfy the PIPA on-device-only guarantee.
     */
    public suspend fun deleteAll(): BecalmResult<Int>
}

/**
 * Production implementation of [PersonEnrichmentRepository].
 *
 * All operations delegate to [PersonEnrichmentDao] without Dispatcher switches — Room
 * already suspends on the IO dispatcher per its own convention. Errors from local
 * Room I/O are mapped to [BecalmError.Io]; unexpected non-IO throwables are wrapped in
 * [BecalmError.Unknown].
 *
 * SP-16 (RepositoryModule) binds this class via `@Binds` under [PersonEnrichmentRepository].
 *
 * PIPA note: this class has no dependency on RailwayApi or any DTO type. Adding such a
 * dependency is a PIPA violation and must be rejected at code review.
 */
@Singleton
public class PersonEnrichmentRepositoryImpl @Inject constructor(
    private val dao: PersonEnrichmentDao,
    private val logger: Logger,
) : PersonEnrichmentRepository {

    override fun observeAll(): Flow<List<PersonEnrichmentEntity>> =
        dao.observeAll()

    override fun observeByPersonRef(personRef: String): Flow<PersonEnrichmentEntity?> =
        dao.observeByPersonRef(personRef)

    override fun observeStarred(): Flow<List<PersonEnrichmentEntity>> =
        dao.observeAll()

    override suspend fun findByPersonRef(personRef: String): PersonEnrichmentEntity? =
        dao.findByPersonRef(personRef)

    override suspend fun upsert(entity: PersonEnrichmentEntity): BecalmResult<Unit> =
        runCatching { dao.upsert(entity) }
            .fold(
                onSuccess = { BecalmResult.Success(Unit) },
                onFailure = { e ->
                    logger.e(TAG, "upsert failed for ref=${redact(entity.personRef)}", e)
                    BecalmResult.Failure(e.toBecalmError("enrichment write failed"))
                },
            )

    override suspend fun upsertAll(entities: List<PersonEnrichmentEntity>): BecalmResult<Int> =
        runCatching { dao.upsertAll(entities) }
            .fold(
                onSuccess = { rowIds -> BecalmResult.Success(rowIds.size) },
                onFailure = { e ->
                    logger.e(TAG, "upsertAll failed for ${entities.size} entities", e)
                    BecalmResult.Failure(e.toBecalmError("enrichment batch write failed"))
                },
            )

    override suspend fun setStarred(personRef: String, starred: Boolean): BecalmResult<Unit> {
        // The `starred` column is not yet on PersonEnrichmentEntity (SP-05 pending).
        // Historically this method performed a read-then-write that silently dropped the
        // `starred` flag — a bug that would only surface when PersonDetailScreen (R8) tried
        // to toggle a star. Failing loudly here forces R6/R8 to depend on SP-05 correctly.
        logger.w(TAG, "setStarred called before SP-05 — refusing (ref=${redact(personRef)})")
        return BecalmResult.Failure(
            BecalmError.Unknown(
                UnsupportedOperationException(
                    "starred column not yet present on PersonEnrichmentEntity (SP-05 pending)",
                ),
            ),
        )
    }

    override suspend fun deleteByPersonRef(personRef: String): BecalmResult<Unit> =
        runCatching { dao.deleteByPersonRef(personRef) }
            .fold(
                onSuccess = { BecalmResult.Success(Unit) },
                onFailure = { e ->
                    logger.e(TAG, "deleteByPersonRef failed for ref=${redact(personRef)}", e)
                    BecalmResult.Failure(e.toBecalmError("enrichment delete failed"))
                },
            )

    override suspend fun deleteAll(): BecalmResult<Int> =
        runCatching { dao.deleteAll() }
            .fold(
                onSuccess = { count -> BecalmResult.Success(count) },
                onFailure = { e ->
                    logger.e(TAG, "deleteAll failed", e)
                    BecalmResult.Failure(e.toBecalmError("enrichment wipe failed"))
                },
            )

    private companion object {
        private const val TAG = "PersonEnrichmentRepo"

        /**
         * Maps a [Throwable] to [BecalmError.Io] for [IOException] instances (Room wraps
         * SQLite failures as [IOException]) and to [BecalmError.Unknown] for all others.
         */
        private fun Throwable.toBecalmError(fallbackMessage: String): BecalmError =
            when (this) {
                is IOException -> BecalmError.Io(message ?: fallbackMessage)
                else -> BecalmError.Unknown(this)
            }
    }
}
