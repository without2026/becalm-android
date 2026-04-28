package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.data.local.db.dao.EmailBodyDao
import com.becalm.android.data.local.db.entity.EmailBodyEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── Interface ────────────────────────────────────────────────────────────────

/**
 * Repository facade over [EmailBodyDao] for the on-device-only `email_body` store.
 *
 * ## PIPA invariant — Room-only (EMAIL-006)
 * Per `.spec/email-pipeline.spec.yml:58-64` (and re-stated at line 79 of the same
 * file plus `.spec/data-ingestion.spec.yml:152`), `EmailBody` is the canonical
 * on-device store for email bodies and **MUST NEVER cross the device boundary**.
 * Railway/Supabase only receive `raw_ingestion_events.event_snippet` (≤200 chars)
 * via [RawIngestionRepository.uploadBatch]; `body_plain`, `body_html`,
 * `attachments_meta`, `raw_headers`, `from_address`, and `to_addresses` are
 * physically prevented from reaching any network DTO by this Repository's design.
 *
 * **No DTO mapper.** This repository must not expose `body_plain`, `body_html`,
 * `attachments_meta`, or `raw_headers` to any wire layer. The interface
 * intentionally returns only [EmailBodyEntity] (a Room `@Entity` type) and never
 * imports from `com.becalm.android.data.remote.dto.*` — enforced at compile time
 * by [com.becalm.android.data.remote.dto.DtoInvariantTest] and asserted on the
 * source text of this file.
 *
 * ## Lifecycle ownership
 * Insert and parse-failure transitions go through this Repository so that future
 * EMAIL-007 / EXTRACT-EMAIL-005 callers depend on a stable interface even when
 * the underlying DAO surface changes. The 30-day retention sweep
 * (`RetentionSweepWorker`, `feat/worker/retention`) intentionally talks directly
 * to the DAO — `deleteOlderThanForSynced` is NOT exposed here so the surface area
 * stays minimal and only lifecycle methods that have a Room+wire boundary
 * implication live on this contract.
 *
 * Spec refs: EMAIL-006, EMAIL-007, `data-ingestion.spec.yml:152`,
 * `.spec/contracts/data-model.yml:327-328 § email_body.room_only`.
 */
public interface EmailBodyRepository {

    /**
     * Persists [entity] via [EmailBodyDao.insert]. The DAO uses
     * `OnConflictStrategy.REPLACE` keyed on the `UNIQUE(raw_event_id)` index, so
     * re-polling the same email yields a true upsert (one row per parent
     * `raw_ingestion_events` row).
     *
     * @param entity The email body row to persist.
     */
    public suspend fun insert(entity: EmailBodyEntity)

    /**
     * Returns the body row associated with [rawEventId], or null when no body has
     * been captured for that parent event.
     *
     * @param rawEventId Foreign-key value pointing at
     *   [com.becalm.android.data.local.db.entity.RawIngestionEventEntity.id].
     */
    public suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity?

    /**
     * Returns the body row for an already-ingested provider message, scoped through
     * its parent raw event so cross-user and cross-provider rows never collide.
     */
    public suspend fun findByProviderMessage(
        userId: String,
        sourceType: String,
        folder: String,
        providerMessageId: String,
    ): EmailBodyEntity?

    /**
     * Marks the body row identified by [id] as unparseable: sets `parse_failed = 1`
     * and clears `body_plain`. Per EMAIL-007 a partially parsed body must never be
     * left visible — clearing in the same statement is the contract.
     *
     * @param id Primary key of the row to mark.
     */
    public suspend fun markParseFailed(id: String)
}

// ─── Implementation ───────────────────────────────────────────────────────────

/**
 * Production implementation of [EmailBodyRepository].
 *
 * All DAO calls are dispatched on [IoDispatcher] so that callers running on the
 * Main dispatcher (test / future UI consumers) never block on Room. The room-only
 * invariant is preserved because no DTO type or `data.remote.*` import is reachable
 * from this class — a scan asserted by `DtoInvariantTest`.
 */
@Singleton
public class EmailBodyRepositoryImpl @Inject constructor(
    private val dao: EmailBodyDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EmailBodyRepository {

    override suspend fun insert(entity: EmailBodyEntity) {
        withContext(ioDispatcher) {
            dao.insert(entity)
        }
    }

    override suspend fun getByRawEventId(rawEventId: String): EmailBodyEntity? =
        withContext(ioDispatcher) {
            dao.getByRawEventId(rawEventId)
        }

    override suspend fun findByProviderMessage(
        userId: String,
        sourceType: String,
        folder: String,
        providerMessageId: String,
    ): EmailBodyEntity? =
        withContext(ioDispatcher) {
            dao.findByProviderMessage(
                userId = userId,
                sourceType = sourceType,
                folder = folder,
                providerMessageId = providerMessageId,
            )
        }

    override suspend fun markParseFailed(id: String) {
        withContext(ioDispatcher) {
            dao.markParseFailed(id)
        }
    }
}
