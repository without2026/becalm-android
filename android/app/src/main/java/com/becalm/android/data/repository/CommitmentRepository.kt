package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.map
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentDto
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentStateMachine
import com.becalm.android.domain.commitment.TransitionError
import com.becalm.android.domain.commitment.TransitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ─── Cursor key ──────────────────────────────────────────────────────────────

private const val CURSOR_KEY = "commitments_cursor"
private const val PAGE_LIMIT = 50
private const val PAGE_CAP = 5
private const val TAG = "CommitmentRepository"

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Manages the local Room cache of commitments and synchronises with Railway.
 *
 * Reactive reads delegate to [CommitmentDao] (Room re-emits on every matching write).
 * Remote operations delegate to [RailwayApi] and write results back to Room.
 */
public interface CommitmentRepository {

    // ── Reactive reads (local Room) ──────────────────────────────────────────

    /** Emits commitments for [userId] filtered by [actionState], re-emits on change. */
    public fun observeByActionState(userId: String, actionState: String): Flow<List<CommitmentEntity>>

    /**
     * Emits pending commitments for [userId] that are undated or due on/before [todayIso].
     *
     * @param todayIso ISO-8601 date string, e.g. "2026-04-16".
     */
    public fun observePendingForToday(userId: String, todayIso: String): Flow<List<CommitmentEntity>>

    /** Emits all commitments for [userId] linked to [personRef], re-emits on change. */
    public fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>>

    /** Emits the count of non-completed commitments for [userId] linked to [personRef]. */
    public fun observeOpenCountForPerson(userId: String, personRef: String): Flow<Int>

    /** Emits the commitment with [id], or null when absent; re-emits on change. */
    public fun observeById(id: String): Flow<CommitmentEntity?>

    // ── One-shot reads ───────────────────────────────────────────────────────

    /** Returns the commitment with [id], or null when no matching row exists. */
    public suspend fun findById(id: String): CommitmentEntity?

    // ── Remote refresh ───────────────────────────────────────────────────────

    /**
     * Delta-syncs commitments from Railway, upserts results into Room, and persists the cursor.
     *
     * Iterates pages until `hasMore=false` or [PAGE_CAP] pages are consumed. The persisted
     * cursor key is `"commitments_cursor"`.
     *
     * @param since When non-null, only commitments updated after this instant are returned.
     */
    public suspend fun refreshSince(
        userId: String,
        since: Instant?,
        personRef: String? = null,
        direction: String? = null,
        actionState: String? = null,
    ): BecalmResult<RefreshStats>

    /**
     * Fetches a single commitment by [id] from Railway and upserts it into Room.
     *
     * @return The upserted [CommitmentEntity] on success, or a typed failure.
     */
    public suspend fun fetchSingle(id: String): BecalmResult<CommitmentEntity>

    // ── State machine (CMT-005..007 / SP-36) ────────────────────────────────

    /**
     * Loads the commitment with [id], applies [event] via [CommitmentStateMachine], and
     * persists the resulting [com.becalm.android.domain.commitment.CommitmentState] to Room.
     *
     * Error mapping:
     * - Row absent                           → [BecalmError.NotFound]
     * - [TransitionError.IllegalTransition]  → [BecalmError.Validation]
     * - [TransitionError.MissingSchedule]    → [BecalmError.Validation]
     *
     * @param id    UUID of the commitment to transition.
     * @param event The [CommitmentEvent] to apply.
     * @return The updated [CommitmentEntity] on success, or a typed failure.
     */
    public suspend fun transitionState(id: String, event: CommitmentEvent): BecalmResult<CommitmentEntity>

    /**
     * Optimistically writes [newState] to Room, then PATCHes Railway.
     *
     * On a non-2xx response the local write is NOT reverted; the SP-29 UploadWorker will
     * retry via `sync_status='pending'` semantics.
     */
    public suspend fun updateActionState(
        id: String,
        newState: String,
        updatedAt: Instant,
    ): BecalmResult<Unit>

    // ── Sync helpers (SP-29 worker) ──────────────────────────────────────────

    /** Returns up to [limit] commitments for [userId] with `sync_status='pending'`. */
    public suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity>

    /** Marks the commitments identified by [ids] as `sync_status='synced'`. */
    public suspend fun markSynced(ids: List<String>): BecalmResult<Unit>

    // ── Cleanup ──────────────────────────────────────────────────────────────

    /**
     * Deletes all commitment rows for [userId].
     *
     * @return [BecalmResult.Success] with the deleted-row count on success.
     */
    public suspend fun deleteAllForUser(userId: String): BecalmResult<Int>

    // ── Supporting types ─────────────────────────────────────────────────────

    /**
     * Aggregate statistics returned by [refreshSince].
     *
     * @property fetched Total DTOs received across all pages.
     * @property upserted Total entities written to Room (equal to [fetched] on success).
     * @property hasMore True when the server indicated more pages beyond the safety cap.
     * @property nextCursor The final cursor persisted to [SyncCursorStore], or null.
     */
    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

// ─── Implementation ──────────────────────────────────────────────────────────

/**
 * Production implementation of [CommitmentRepository].
 */
@Singleton
public class CommitmentRepositoryImpl @Inject constructor(
    private val dao: CommitmentDao,
    private val api: RailwayApi,
    private val cursorStore: SyncCursorStore,
    private val logger: Logger,
) : CommitmentRepository {

    // ── Reactive reads ────────────────────────────────────────────────────────

    override fun observeByActionState(userId: String, actionState: String): Flow<List<CommitmentEntity>> =
        dao.observeByActionState(userId, actionState)

    override fun observePendingForToday(userId: String, todayIso: String): Flow<List<CommitmentEntity>> =
        dao.observePendingForToday(userId, todayIso)

    override fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>> =
        dao.observeAllForPerson(userId, personRef)

    override fun observeOpenCountForPerson(userId: String, personRef: String): Flow<Int> =
        dao.observeOpenCountForPerson(userId, personRef)

    override fun observeById(id: String): Flow<CommitmentEntity?> =
        dao.observeById(id)

    // ── One-shot reads ────────────────────────────────────────────────────────

    override suspend fun findById(id: String): CommitmentEntity? =
        dao.findById(id)

    // ── Remote refresh ────────────────────────────────────────────────────────

    override suspend fun refreshSince(
        userId: String,
        since: Instant?,
        personRef: String?,
        direction: String?,
        actionState: String?,
    ): BecalmResult<CommitmentRepository.RefreshStats> {
        var cursor: String? = null
        var totalFetched = 0
        var totalUpserted = 0
        var lastHasMore = false
        var lastCursor: String? = null

        repeat(PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat

            val response = try {
                api.getCommitments(
                    cursor = cursor,
                    limit = PAGE_LIMIT,
                    since = since?.toString(),
                    personRef = personRef,
                    direction = direction,
                    actionState = actionState,
                )
            } catch (e: IOException) {
                logger.e(TAG, "refreshSince network error on page $pageIndex", e)
                return BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
            }

            val result = response.toBecalmResult { it }
            when (result) {
                is BecalmResult.Failure -> return result
                is BecalmResult.Success -> {
                    val page = result.value
                    val entities = page.data.map { it.toEntity(userId) }
                    dao.insertAll(entities)
                    totalFetched += page.data.size
                    totalUpserted += entities.size
                    lastHasMore = page.hasMore
                    lastCursor = page.cursor
                    cursor = page.cursor
                    cursorStore.setCursor(CURSOR_KEY, page.cursor)
                }
            }

            if (!lastHasMore) return@repeat
        }

        return BecalmResult.Success(
            CommitmentRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = lastHasMore,
                nextCursor = lastCursor,
            )
        )
    }

    override suspend fun fetchSingle(id: String): BecalmResult<CommitmentEntity> {
        val response = try {
            api.getCommitment(id)
        } catch (e: IOException) {
            logger.e(TAG, "fetchSingle network error id=$id", e)
            return BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        }

        return response.toBecalmResult { it.data }.let { result ->
            when (result) {
                is BecalmResult.Failure -> result
                is BecalmResult.Success -> {
                    val entity = result.value.toEntity(result.value.userId)
                    dao.insert(entity)
                    BecalmResult.Success(entity)
                }
            }
        }
    }

    // ── State machine ─────────────────────────────────────────────────────────

    override suspend fun transitionState(
        id: String,
        event: CommitmentEvent,
    ): BecalmResult<CommitmentEntity> = withContext(Dispatchers.IO) {
        val entity = dao.findById(id)
            ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))

        when (val result = CommitmentStateMachine.transition(entity.commitmentState, event)) {
            is TransitionResult.Err -> BecalmResult.Failure(
                when (result.error) {
                    is TransitionError.IllegalTransition ->
                        BecalmError.Validation(
                            field = "commitmentState",
                            message = "Illegal transition: ${result.error.from} + ${result.error.event::class.simpleName}",
                        )
                    TransitionError.MissingSchedule ->
                        BecalmError.Validation(
                            field = "at",
                            message = "Schedule event requires a non-null future instant",
                        )
                }
            )
            is TransitionResult.Ok -> {
                val updated = entity.copy(commitmentState = result.state)
                dao.update(updated)
                BecalmResult.Success(updated)
            }
        }
    }

    override suspend fun updateActionState(
        id: String,
        newState: String,
        updatedAt: Instant,
    ): BecalmResult<Unit> {
        dao.updateActionState(id, newState, updatedAt)

        val response = try {
            api.patchCommitment(id, request = PatchCommitmentRequest(newState))
        } catch (e: IOException) {
            logger.e(TAG, "updateActionState network error id=$id", e)
            return BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        }

        return response.toBecalmResult { it }.map { }
    }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    override suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity> =
        dao.findPendingSync(userId, limit)

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> = try {
        dao.markSynced(ids)
        BecalmResult.Success(Unit)
    } catch (e: Exception) {
        logger.e(TAG, "markSynced failed", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "markSynced failed"))
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> = try {
        val count = dao.deleteAllForUser(userId)
        BecalmResult.Success(count)
    } catch (e: Exception) {
        logger.e(TAG, "deleteAllForUser failed userId=$userId", e)
        BecalmResult.Failure(BecalmError.Io(e.message ?: "deleteAllForUser failed"))
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Maps a non-null [Response] body through [extract], returning a typed [BecalmResult].
     *
     * HTTP status mapping:
     * - 2xx → [BecalmResult.Success] with the extracted value
     * - 401 → [BecalmError.Unauthorized]
     * - 404 → [BecalmError.NotFound]
     * - 422 → [BecalmError.Validation]
     * - 429 → [BecalmError.RateLimited]
     * - 5xx → [BecalmError.ServerError]
     * - other non-2xx → [BecalmError.Network]
     */
    private fun <T, R> Response<T>.toBecalmResult(extract: (T) -> R): BecalmResult<R> {
        if (isSuccessful) {
            val body = body()
            return if (body != null) {
                BecalmResult.Success(extract(body))
            } else {
                BecalmResult.Failure(BecalmError.ServerError(code(), "empty body"))
            }
        }
        return when (val code = code()) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            404 -> BecalmResult.Failure(BecalmError.NotFound("commitment"))
            422 -> BecalmResult.Failure(BecalmError.Validation(null, "unprocessable entity (HTTP 422)"))
            429 -> {
                val retryAfter = headers()["Retry-After"]?.toLongOrNull()
                BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
            }
            in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(code, errorBody()?.string()))
            else -> BecalmResult.Failure(BecalmError.Network(code, message()))
        }
    }
}

// ─── DTO → Entity mapping ─────────────────────────────────────────────────────

private fun CommitmentDto.toEntity(userId: String): CommitmentEntity =
    CommitmentEntity(
        id = id,
        userId = userId,
        direction = direction,
        counterpartyRaw = counterpartyRaw,
        personRef = personRef,
        title = title,
        description = description,
        quote = quote,
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueDate = dueDate,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = sourceRef,
        confidence = confidence,
        syncStatus = syncStatus ?: "synced",
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
