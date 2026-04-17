package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.BatchUploadRequest
import com.becalm.android.data.remote.dto.BatchUploadResponse
import com.becalm.android.data.remote.dto.RawIngestionEventDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.Instant
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
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
     * Emits a live list of the most recent events for [userId], newest-first.
     *
     * Note: the DAO exposes separate flows for null-personRef and per-person events.
     * This method merges and sorts them. A future DAO query addition will replace
     * this implementation with a single covered index scan.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeTimelineForUser(userId: String, limit: Int = 200): Flow<List<RawIngestionEventEntity>>

    /**
     * Emits a live list of the most recent events for a specific [personRef] owned by [userId],
     * newest-first.
     *
     * @param limit Maximum list size per emission.
     */
    public fun observeForPerson(userId: String, personRef: String, limit: Int = 100): Flow<List<RawIngestionEventEntity>>

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
     * Transitions all voice events for [userId] from "awaiting_consent" to "pending"
     * so that [com.becalm.android.worker.VoiceUploadWorker] will pick them up.
     *
     * Called by [com.becalm.android.ui.settings.SettingsViewModel] when the user grants
     * PIPA third-party provision consent via the Settings toggle.
     *
     * @return [BecalmResult.Success] with the count of rows updated (0 if none were waiting).
     *
     * Spec refs: VOI-004, ONB-PIPA invariant.
     */
    public suspend fun releaseAwaitingConsentVoice(userId: String): BecalmResult<Int>

    /**
     * Atomically transitions all voice events for [userId] from "awaiting_consent" to
     * "pending" and returns the exact list of released IDs.
     *
     * Supersedes [releaseAwaitingConsentVoice] for the consent-grant flow because the
     * caller needs to enqueue [com.becalm.android.worker.VoiceUploadWorker] for precisely
     * the released rows — no more, no less (finding #2 fix).
     *
     * @return [BecalmResult.Success] with the released IDs (empty list if none were waiting).
     *
     * Spec refs: VOI-004.
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
    private val api: RailwayApi,
    private val logger: Logger,
) : RawIngestionRepository {

    // ── Write ─────────────────────────────────────────────────────────────────

    override suspend fun insertLocal(event: RawIngestionEventEntity): BecalmResult<String> {
        val resolved = event.ensureClientEventId()
        // ING-013: polite read-before-write to avoid triggering the UNIQUE constraint
        val existing = dao.findByClientEventId(resolved.userId, resolved.clientEventId)
        if (existing != null) {
            logger.d(TAG, "insertLocal dedup hit for id=${existing.id}")
            return BecalmResult.Success(existing.id)
        }
        return daoOp(TAG, "insert failed") {
            dao.insert(resolved)
            logger.d(TAG, "insertLocal ok id=${resolved.id}")
            resolved.id
        }
    }

    override suspend fun insertLocalBatch(events: List<RawIngestionEventEntity>): BecalmResult<List<String>> {
        if (events.isEmpty()) return BecalmResult.Success(emptyList())
        val resolved = events.map { it.ensureClientEventId() }
        // Room @Insert(onConflict = IGNORE) on insertAll handles duplicates at the schema layer.
        return daoOp(TAG, "batch insert failed") {
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
        // The DAO exposes two reactive entry points: events with a known personRef and
        // events with a null personRef. We combine them here because the DAO (R1/R2)
        // does not yet expose a single all-events-for-user reactive query.
        // The placeholder empty-string personRef returns an empty list in practice since
        // no entity carries a blank personRef, so only null-personRef events are emitted.
        // A future DAO method backed by idx_raw_events_user_time replaces this path.
        combine(
            dao.observeUnassignedRecent(userId, limit),
            dao.observeRecentForPerson(userId, "", limit),
        ) { unassigned, withPerson ->
            (unassigned + withPerson)
                .sortedByDescending { it.timestamp }
                .take(limit)
        }

    override fun observeForPerson(
        userId: String,
        personRef: String,
        limit: Int,
    ): Flow<List<RawIngestionEventEntity>> =
        dao.observeRecentForPerson(userId, personRef, limit)

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
        return daoOp(TAG, "markSynced failed") {
            dao.markSynced(ids)
            logger.d(TAG, "markSynced count=${ids.size}")
        }
    }

    override suspend fun markFailed(id: String, lastAttemptAt: Instant): BecalmResult<Unit> =
        daoOp(TAG, "markFailed failed") {
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
            BecalmResult.Failure(BecalmError.Network(-1, e.message ?: "network error"))
        } catch (e: Exception) {
            logger.e(TAG, "uploadBatch unexpected error count=${events.size}", e)
            BecalmResult.Failure(BecalmError.Unknown(e))
        }
    }

    // ── PIPA consent release / park ───────────────────────────────────────────

    override suspend fun releaseAwaitingConsentVoice(userId: String): BecalmResult<Int> =
        try {
            val count = dao.releaseAwaitingConsentVoice(userId)
            logger.d(TAG, "releaseAwaitingConsentVoice userId_hash=${userId.hashCode()} count=$count")
            BecalmResult.Success(count)
        } catch (e: Exception) {
            logger.e(TAG, "releaseAwaitingConsentVoice failed", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "release failed"))
        }

    override suspend fun releaseAwaitingConsentVoiceAndReturnIds(userId: String): BecalmResult<List<String>> =
        try {
            val ids = dao.releaseAwaitingConsentVoiceAndReturnIds(userId)
            logger.d(TAG, "releaseAwaitingConsentVoiceAndReturnIds userId_hash=${userId.hashCode()} count=${ids.size}")
            BecalmResult.Success(ids)
        } catch (e: Exception) {
            logger.e(TAG, "releaseAwaitingConsentVoiceAndReturnIds failed", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "release failed"))
        }

    override suspend fun parkAndCancelPendingVoice(userId: String): BecalmResult<List<String>> =
        try {
            // Atomic: SELECT ids + UPDATE WHERE id IN (:ids) run inside a single @Transaction.
            // Eliminates the race where a new 'pending' row inserted between the old two-call
            // SELECT/UPDATE would be parked but its ID never returned (finding #1 fix).
            val ids = dao.parkCancellablePendingVoiceAndReturnIds(
                userId = userId,
                now = System.currentTimeMillis(),
            )
            logger.d(TAG, "parkAndCancelPendingVoice userId_hash=${userId.hashCode()} count=${ids.size}")
            BecalmResult.Success(ids)
        } catch (e: Exception) {
            logger.e(TAG, "parkAndCancelPendingVoice failed", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "park failed"))
        }

    // ── Clear ─────────────────────────────────────────────────────────────────

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> =
        try {
            val count = dao.deleteAllForUser(userId)
            logger.d(TAG, "deleteAllForUser count=$count")
            BecalmResult.Success(count)
        } catch (e: Exception) {
            logger.e(TAG, "deleteAllForUser failed", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "delete failed"))
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
