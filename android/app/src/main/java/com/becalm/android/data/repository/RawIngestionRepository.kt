package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// ─── Interface ────────────────────────────────────────────────────────────────

/**
 * Local write/read and remote sync operations for raw ingestion events.
 *
 * Local events are always written to Room first (local-first model). The sync
 * queue ([findPendingSync] / [markSynced] / [markFailed]) is consumed by
 * SP-29 UploadWorker. [uploadBatch] performs a single idempotent HTTP call.
 */
public interface RawIngestionRepository {

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a single event locally, generating a UUID v4 [RawIngestionEventEntity.clientEventId]
     * if the caller left it blank (SYNC-003).
     *
     * @return [BecalmResult.Success] with the entity's [RawIngestionEventEntity.id];
     *   if the event already exists (by userId + clientEventId) the existing id is returned
     *   without a duplicate insert (ING-013).
     */
    public suspend fun insertLocal(event: RawIngestionEventEntity): BecalmResult<String>

    /**
     * Inserts a batch of events in a single Room transaction. Each item receives the same
     * UUID-generation and dedup guarantees as [insertLocal].
     *
     * @return [BecalmResult.Success] with the list of ids parallel to [events].
     */
    public suspend fun insertLocalBatch(events: List<RawIngestionEventEntity>): BecalmResult<List<String>>

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Emits a live list of the most recent unassigned-counterparty events for [userId],
     * newest-first.
     *
     * Delegates directly to [RawIngestionEventDao.observeUnassignedRecent]; the former
     * merge-and-sort implementation was redundant because the per-personRef branch always
     * emitted empty (no entity carries a blank personRef). A future DAO query addition
     * will widen this to include per-person events behind a covered index scan.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeTimelineForUser(userId: String, limit: Int = 200): Flow<List<RawIngestionEventEntity>>

    /** Emits every event with a non-null `person_ref` for [userId], newest-first. */
    public fun observeAssignedForUser(userId: String): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits a live list of the most recent events for a specific [personRef] owned by [userId],
     * newest-first.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeForPerson(userId: String, personRef: String, limit: Int = 100): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits a live list of the most recent events for a specific [sourceType] owned by [userId],
     * newest-first.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeForSourceType(userId: String, sourceType: String, limit: Int = 100): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits the count of events for [userId] whose source is in [sourceTypes] and whose
     * timestamp is on or after [since].
     *
     * Used by cold-sync Stage 2 progress to project real local row counts from Room.
     */
    public fun observeCountForSourceTypesSince(
        userId: String,
        sourceTypes: List<String>,
        since: Instant,
    ): Flow<Int>

    /**
     * Returns the event matching [userId] + [clientEventId], or null if it does not exist locally.
     */
    public suspend fun findByClientEventId(userId: String, clientEventId: String): RawIngestionEventEntity?

    /**
     * Returns the event with the given primary-key [id] scoped to [userId], or null when
     * no row exists for that user.
     *
     * Although the primary key is globally unique, callers MUST pass the current user's
     * [userId] so that the DAO can reject cross-user reads: in multi-account or stale
     * back-stack scenarios one user's event UUID can end up in another user's navigation
     * stack, and we must never return another user's row.
     *
     * @param id The [RawIngestionEventEntity.id] UUID to look up.
     * @param userId The Supabase auth.users UUID of the requesting user.
     * @return The matching entity owned by [userId], or null.
     */
    public suspend fun findById(id: String, userId: String): RawIngestionEventEntity?

    // ── Sync queue ────────────────────────────────────────────────────────────

    /**
     * Returns up to [limit] events in "pending" sync state, ordered oldest-first.
     * Consumed by SP-29 UploadWorker.
     */
    public suspend fun findPendingSync(userId: String, limit: Int): List<RawIngestionEventEntity>

    /**
     * Marks the given event [ids] as "synced" in a single UPDATE.
     *
     * @return [BecalmResult.Success(Unit)] on success.
     */
    public suspend fun markSynced(ids: List<String>): BecalmResult<Unit>

    /**
     * Records a failed upload attempt for event [id]: sets status to "failed",
     * increments retry_count by 1, and records [lastAttemptAt].
     *
     * @return [BecalmResult.Success(Unit)] on success.
     */
    public suspend fun markFailed(id: String, lastAttemptAt: Instant): BecalmResult<Unit>

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Converts [events] to DTOs and POSTs them to Railway via an idempotent batch endpoint.
     * HTTP errors are mapped to typed [BecalmError] variants; transport retries are
     * delegated to OkHttp's RetryInterceptor (SP-29 handles worker-level backoff).
     *
     * @return [BecalmResult.Success] with the server's [BatchUploadResponse] on HTTP 200.
     */
    public suspend fun uploadBatch(events: List<RawIngestionEventEntity>): BecalmResult<BatchUploadResponse>

    // ── PIPA consent release / park ───────────────────────────────────────────

    /**
     * Atomically transitions all voice events for [userId] from "awaiting_consent" to
     * "pending" and returns the exact list of released IDs.
     *
     * The caller needs to enqueue [com.becalm.android.worker.VoiceUploadWorker] for
     * precisely the released rows — no more, no less (finding #2 fix).
     *
     * @return [BecalmResult.Success] with the released IDs (empty list if none were waiting).
     *
     * Spec refs: VOI-004, ONB-PIPA invariant.
     */
    public suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): BecalmResult<List<String>>

    /**
     * Returns the IDs of all voice events for [userId] that are in a cancellable state
     * ("pending" or "awaiting_consent"), then parks them all as "awaiting_consent".
     *
     * Called when the user withdraws PIPA consent so that the caller can cancel the
     * corresponding WorkManager unique-work entries by ID before any queued job proceeds
     * past the consent check (finding #1 fix).
     *
     * @return [BecalmResult.Success] with the IDs that were parked (empty when none).
     *
     * Spec refs: VOI-004.
     */
    public suspend fun parkAndCancelPendingVoice(userId: String): BecalmResult<List<String>>

    // ── Clear ─────────────────────────────────────────────────────────────────

    /**
     * Hard-deletes all local events for [userId]. Called on sign-out for scoped PIPA wipe;
     * full database wipe uses `Database.clearAllTables()` in [AuthRepository].
     *
     * @return [BecalmResult.Success] with the number of rows deleted.
     */
    public suspend fun deleteAllForUser(userId: String): BecalmResult<Int>
}

// ─── Implementation ───────────────────────────────────────────────────────────

private const val TAG = "RawIngestionRepository"

/**
 * Production implementation of [RawIngestionRepository].
 *
 * All DAO calls are already dispatched on Room's internal IO executor; no
 * Dispatcher switching is performed here.
 */
@Singleton
public class RawIngestionRepositoryImpl @Inject constructor(
    private val dao: RawIngestionEventDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
) : RawIngestionRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        dao: RawIngestionEventDao,
        api: RailwayApi,
        logger: Logger,
    ) : this(
        dao = dao,
        apiProvider = Provider { api },
        logger = logger,
    )

    // ── Write ─────────────────────────────────────────────────────────────────

    override suspend fun insertLocal(event: RawIngestionEventEntity): BecalmResult<String> {
        val resolved = event.ensureClientEventId()
        // ING-013: polite read-before-write to avoid triggering the UNIQUE constraint
        val existing = dao.findByClientEventId(resolved.userId, resolved.clientEventId)
        if (existing != null) {
            logger.d(TAG, "insertLocal dedup hit for id=${existing.id}")
            return BecalmResult.Success(existing.id)
        }
        return logger.daoOp(TAG, "insert failed") {
            dao.insert(resolved)
            logger.d(TAG, "insertLocal ok id=${resolved.id}")
            resolved.id
        }
    }

    override suspend fun insertLocalBatch(events: List<RawIngestionEventEntity>): BecalmResult<List<String>> {
        if (events.isEmpty()) return BecalmResult.Success(emptyList())
        val resolved = events.map { it.ensureClientEventId() }
        // Room @Insert(onConflict = IGNORE) on insertAll handles duplicates at the schema layer.
        return logger.daoOp(TAG, "batch insert failed") {
            dao.insertAll(resolved)
            logger.d(TAG, "insertLocalBatch ok count=${resolved.size}")
            resolved.map { it.id }
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    override fun observeTimelineForUser(
        userId: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>> =
        // The placeholder empty-string personRef branch always returned an empty list
        // (no entity carries a blank personRef), so combining it was a no-op. This
        // direct delegation is behavior-identical and removes needless flow plumbing.
        // A future DAO method backed by idx_raw_events_user_time will replace this path.
        dao.observeUnassignedRecent(userId, limit)

    override fun observeAssignedForUser(userId: String): Flow<List<RawIngestionEventEntity>> =
        dao.observeAssignedForUser(userId)

    override fun observeForPerson(
        userId: String,
        personRef: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>> =
        dao.observeRecentForPerson(userId, personRef, limit)

    override fun observeForSourceType(
        userId: String,
        sourceType: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>> =
        dao.observeRecentForSourceType(userId, sourceType, limit)

    override fun observeCountForSourceTypesSince(
        userId: String,
        sourceTypes: List<String>,
        since: Instant,
    ): Flow<Int> =
        dao.observeCountForSourceTypesSince(userId, sourceTypes, since)

    override suspend fun findByClientEventId(
        userId: String,
        clientEventId: String,
    ): RawIngestionEventEntity? =
        dao.findByClientEventId(userId, clientEventId)

    override suspend fun findById(id: String, userId: String): RawIngestionEventEntity? =
        dao.findById(id = id, userId = userId)

    // ── Sync queue ────────────────────────────────────────────────────────────

    override suspend fun findPendingSync(
        userId: String,
        limit: Int,
    ): List<RawIngestionEventEntity> =
        dao.findPendingForUpload(userId, limit)

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> {
        if (ids.isEmpty()) return BecalmResult.Success(Unit)
        return logger.daoOp(TAG, "markSynced failed") {
            dao.markSynced(ids)
            logger.d(TAG, "markSynced count=${ids.size}")
        }
    }

    override suspend fun markFailed(id: String, lastAttemptAt: Instant): BecalmResult<Unit> =
        logger.daoOp(TAG, "markFailed failed") {
            dao.markFailed(id = id, retryIncrement = 1, now = lastAttemptAt)
            logger.d(TAG, "markFailed id=$id")
        }

    // ── Upload ────────────────────────────────────────────────────────────────

    override suspend fun uploadBatch(events: List<RawIngestionEventEntity>): BecalmResult<BatchUploadResponse> {
        val dtos = events.map { it.toDto() }
        val request = BatchUploadRequest(events = dtos)
        return try {
            val response = api.batchUploadRawEvents(request = request)
            logger.d(TAG, "uploadBatch http=${response.code()} count=${events.size}")
            response.toBecalmResult()
        } catch (e: IOException) {
            logger.e(TAG, "uploadBatch network error count=${events.size}", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (e: Exception) {
            logger.e(TAG, "uploadBatch unexpected error count=${events.size}", e)
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    // ── PIPA consent release / park ───────────────────────────────────────────

    override suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): BecalmResult<List<String>> =
        logger.daoOp(TAG, "releaseAwaitingConsentVoiceAndReturnIds failed") {
            val ids = dao.releaseAwaitingConsentVoiceAndReturnIds(userId)
            logger.d(TAG, "releaseAwaitingConsentVoiceAndReturnIds userId_hash=${userId.hashCode()} count=${ids.size}")
            ids
        }

    override suspend fun parkAndCancelPendingVoice(userId: String): BecalmResult<List<String>> =
        logger.daoOp(TAG, "parkAndCancelPendingVoice failed") {
            // Atomic: SELECT ids + UPDATE WHERE id IN (:ids) run inside a single @Transaction.
            // Eliminates the race where a new 'pending' row inserted between the old two-call
            // SELECT/UPDATE would be parked but its ID never returned (finding #1 fix).
            val ids = dao.parkCancellablePendingVoiceAndReturnIds(
                userId = userId,
                now = System.currentTimeMillis(),
            )
            logger.d(TAG, "parkAndCancelPendingVoice userId_hash=${userId.hashCode()} count=${ids.size}")
            ids
        }

    // ── Clear ─────────────────────────────────────────────────────────────────

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> =
        logger.daoOp(TAG, "deleteAllForUser failed") {
            val count = dao.deleteAllForUser(userId)
            logger.d(TAG, "deleteAllForUser count=$count")
            count
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a copy of this entity with a UUID v4 clientEventId if the caller
     * left it blank — SYNC-003 requires a server-side idempotency key to be present.
     */
    private fun RawIngestionEventEntity.ensureClientEventId(): RawIngestionEventEntity =
        if (clientEventId.isBlank()) copy(clientEventId = UUID.randomUUID().toString()) else this

    private fun RawIngestionEventEntity.toDto(): RawIngestionEventDto =
        RawIngestionEventDto(
            id = id,
            clientEventId = clientEventId,
            userId = userId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personRef = personRef,
            eventTitle = eventTitle,
            eventSnippet = eventSnippet,
            durationSeconds = durationSeconds,
            location = location,
            // EMAIL-001 direction hint (`.spec/email-pipeline.spec.yml:15-18`) — INBOX|SENT for
            // email source_types, null elsewhere. Must be propagated so Railway can drive the
            // server-side person_ref derivation from the same raw-event metadata the client saw.
            folder = folder,
            commitmentsExtractedCount = commitmentsExtractedCount,
            timestamp = timestamp,
        )

    /**
     * Maps a Retrofit [Response] to a [BecalmResult], applying the error semantics
     * documented in api-contract.yml for the ingestion batch endpoint.
     */
    private fun Response<BatchUploadResponse>.toBecalmResult(): BecalmResult<BatchUploadResponse> {
        val body = body()
        if (isSuccessful && body != null) return BecalmResult.Success(body)
        return when (code()) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            413 -> BecalmResult.Failure(BecalmError.Validation(field = null, message = "batch too large"))
            422 -> {
                val msg = errorBody()?.string() ?: "validation error"
                BecalmResult.Failure(BecalmError.Validation(field = null, message = msg))
            }
            429 -> {
                val retryAfter = headers()["Retry-After"]?.toLongOrNull()
                BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
            }
            in 500..599 -> {
                val errBody = errorBody()?.string()
                BecalmResult.Failure(BecalmError.ServerError(code(), errBody))
            }
            else -> {
                val msg = errorBody()?.string() ?: message()
                BecalmResult.Failure(BecalmError.Network(code(), msg ?: "unknown error"))
            }
        }
    }
}
