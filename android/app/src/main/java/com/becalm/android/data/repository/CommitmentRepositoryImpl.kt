package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.result.map
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentBatchRequestDto
import com.becalm.android.data.remote.dto.CommitmentBatchResponseDto
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.data.repository.internal.MAX_BATCH_SIZE
import com.becalm.android.data.repository.internal.toBatchItemDto
import com.becalm.android.data.repository.internal.toDomain
import com.becalm.android.data.repository.internal.toEntity
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentStateMachine
import com.becalm.android.domain.commitment.TransitionError
import com.becalm.android.domain.commitment.TransitionResult
import kotlinx.coroutines.CoroutineDispatcher
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
private const val TAG = "CommitmentRepository"

/**
 * HTTP header carrying a server-supplied retry hint in seconds on 429 responses
 * (api-contract.yml § rate-limiting). Shared by [toBecalmResult] and
 * [toBatchBecalmResult] so the literal lives in exactly one place.
 */
private const val HEADER_RETRY_AFTER: String = "Retry-After"

/** Legal action_state values per data-model.yml:134-139 / commitment-management.spec.yml CMT-005..007. */
private val ALLOWED_ACTION_STATES = setOf("pending", "reminded", "followed_up", "completed")

/**
 * Production implementation of [CommitmentRepository].
 */
@Singleton
public class CommitmentRepositoryImpl @Inject constructor(
    private val dao: CommitmentDao,
    private val api: RailwayApi,
    private val cursorStore: SyncCursorStore,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CommitmentRepository {

    // ── Reactive reads ────────────────────────────────────────────────────────

    override fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>> =
        dao.observeAllForUser(userId)

    override fun observePendingForToday(userId: String, endOfTodayEpochMs: Long): Flow<List<CommitmentEntity>> =
        dao.observePendingForToday(userId, endOfTodayEpochMs)

    override fun observeAllForPerson(userId: String, personRef: String): Flow<List<CommitmentEntity>> =
        dao.observeAllForPerson(userId, personRef)

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

        repeat(REFRESH_PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat

            val response = when (val apiResult = safeApi("refreshSince network error on page $pageIndex") {
                api.getCommitments(
                    cursor = cursor,
                    limit = PAGE_LIMIT,
                    since = since?.toString(),
                    personRef = personRef,
                    direction = direction,
                    actionState = actionState,
                )
            }) {
                is BecalmResult.Failure -> return apiResult
                is BecalmResult.Success -> apiResult.value
            }

            val result = response.toBecalmResult { it }
            when (result) {
                is BecalmResult.Failure -> return result
                is BecalmResult.Success -> {
                    val page = result.value
                    // Merge server response with any locally-set lifecycle state so that a
                    // legacy backend that does not yet return last_edited_*/quote_disputed*/
                    // deleted_at/supersedes_commitment_id cannot silently wipe local edits
                    // or tombstones via a REPLACE upsert (stock Moshi cannot distinguish
                    // "field omitted" from "field explicitly null/false"). See the function
                    // KDoc on [toEntity] for the append-only merge rules.
                    val existingById: Map<String, CommitmentEntity> =
                        dao.findByIdsForMerge(userId, page.data.map { it.id })
                            .associateBy { it.id }
                    val entities = page.data.map { it.toEntity(userId, existingById[it.id]) }
                    dao.insertAll(entities)
                    totalFetched += page.data.size
                    totalUpserted += entities.size
                    lastHasMore = page.hasMore
                    lastCursor = page.cursor
                    cursor = page.cursor
                    cursorStore.setCursor(CURSOR_KEY, page.cursor)
                }
            }
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

    // ── State machine ─────────────────────────────────────────────────────────

    override suspend fun transitionState(
        id: String,
        event: CommitmentEvent,
    ): BecalmResult<CommitmentEntity> = withContext(ioDispatcher) {
        val entity = dao.findById(id)
            ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))

        when (val result = CommitmentStateMachine.transition(entity.commitmentState, event)) {
            is TransitionResult.Err -> BecalmResult.Failure(result.error.toBecalmError())
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
        // R3-01: All state transitions must go through CommitmentStateMachine.
        // Validate the action_state enum value, then (for transitions that imply a
        // lifecycle event — namely "completed" → MarkDone) run it through the state
        // machine so illegal lifecycle transitions are rejected before touching the DB.
        if (newState !in ALLOWED_ACTION_STATES) {
            return BecalmResult.Failure(
                BecalmError.Validation(
                    field = "actionState",
                    message = "Unknown action_state '$newState'; expected one of $ALLOWED_ACTION_STATES",
                )
            )
        }

        val entity = dao.findById(id)
            ?: return BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))

        val lifecycleEvent = actionStateToLifecycleEvent(newState)
        val nextLifecycleState = if (lifecycleEvent != null) {
            when (val result = CommitmentStateMachine.transition(entity.commitmentState, lifecycleEvent)) {
                is TransitionResult.Err -> return BecalmResult.Failure(
                    when (result.error) {
                        is TransitionError.IllegalTransition -> BecalmError.Validation(
                            field = "commitmentState",
                            message = "Illegal transition: ${result.error.from} + ${result.error.event::class.simpleName} (action_state='$newState')",
                        )
                        TransitionError.MissingSchedule -> BecalmError.Validation(
                            field = "at",
                            message = "Schedule event requires a non-null future instant",
                        )
                    }
                )
                is TransitionResult.Ok -> result.state
            }
        } else {
            entity.commitmentState
        }

        dao.updateActionState(id, newState, updatedAt)
        if (nextLifecycleState != entity.commitmentState) {
            dao.update(entity.copy(commitmentState = nextLifecycleState, updatedAt = updatedAt))
        }

        // Optimistic local write has landed. If the PATCH fails (IOException or non-2xx),
        // demote sync_status='pending' so UploadWorker.flushCommitments picks this row up
        // on the next run (CMT-005..007 + commitment-management.spec.yml invariant 3).
        val response = when (val apiResult = safeApi("updateActionState network error id=$id") {
            api.patchCommitment(id, request = PatchCommitmentRequest(newState))
        }) {
            is BecalmResult.Failure -> {
                dao.markPending(id)
                return apiResult
            }
            is BecalmResult.Success -> apiResult.value
        }

        // 2xx → flip to 'synced' (avoids flushCommitments re-upload); non-2xx → leave 'pending' so flush retries.
        val mapped = response.toBecalmResult { it }.map { }
        when (mapped) {
            is BecalmResult.Success -> dao.markSynced(listOf(id))
            is BecalmResult.Failure -> dao.markPending(id)
        }
        return mapped
    }

    /**
     * Maps an action_state target to the [CommitmentEvent] that should drive the
     * SP-36 lifecycle, or null when the action_state change has no lifecycle effect
     * (e.g. pending → reminded tracks follow-up intensity without changing lifecycle).
     *
     * Mapping (derived from commitment-management.spec.yml CMT-005/6/7):
     * - "completed" → [CommitmentEvent.MarkDone]
     * - "pending" / "reminded" / "followed_up" → no lifecycle change
     *   (DONE is terminal per CMT-007; reopen is not a supported transition.)
     */
    private fun actionStateToLifecycleEvent(actionState: String): CommitmentEvent? =
        when (actionState) {
            "completed" -> CommitmentEvent.MarkDone
            else        -> null
        }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    override suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity> =
        dao.findPendingSync(userId, limit)

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> =
        logger.daoOp(TAG, "markSynced failed") {
            dao.markSynced(ids)
        }

    override suspend fun markFailed(id: String): BecalmResult<Unit> =
        logger.daoOp(TAG, "markFailed failed id=$id") {
            dao.markFailed(id)
        }

    override suspend fun uploadBatch(
        pending: List<CommitmentEntity>,
    ): BecalmResult<CommitmentRepository.BatchResponse> {
        if (pending.isEmpty()) {
            return BecalmResult.Success(
                CommitmentRepository.BatchResponse(acknowledged = 0, failed = emptyList()),
            )
        }

        // Enforce the 100-item contract ceiling client-side rather than relying on HTTP 413.
        // SP-29 UploadWorker already slices at BATCH_SIZE=100, so this is a defence-in-depth
        // guard: if a future caller passes a larger list we fail fast instead of blowing
        // through the Railway max_batch_size constraint.
        if (pending.size > MAX_BATCH_SIZE) {
            return BecalmResult.Failure(
                BecalmError.Validation(
                    field = "pending",
                    message = "uploadBatch size=${pending.size} exceeds max $MAX_BATCH_SIZE",
                ),
            )
        }

        val request = CommitmentBatchRequestDto(commitments = pending.map { it.toBatchItemDto() })

        return withContext(ioDispatcher) {
            try {
                val response = api.uploadCommitmentsBatch(request = request)
                logger.d(TAG, "uploadBatch http=${response.code()} count=${pending.size}")
                response.toBatchBecalmResult()
            } catch (e: IOException) {
                logger.e(TAG, "uploadBatch network error count=${pending.size}", e)
                BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
            } catch (e: Exception) {
                // rethrowIfCancellation preserves structured-concurrency cancellation —
                // the catch-all below handles only genuine unexpected errors.
                e.rethrowIfCancellation()
                logger.e(TAG, "uploadBatch unexpected error count=${pending.size}", e)
                BecalmResult.Failure(BecalmError.Unknown(e))
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> =
        logger.daoOp(TAG, "deleteAllForUser failed userId=$userId") {
            dao.deleteAllForUser(userId)
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * refreshSince·updateActionState 두 곳의 동일한 `try { api() } catch (IOException)`
     * 블록을 통합한다. 두 호출지 모두 같은 예외 타입(IOException), 같은 로그 레벨(e),
     * 같은 에러 변환(Network(0, ...))을 쓰기 때문에 바이트-동일 패턴이 유지된다.
     * 만약 한 호출지라도 다른 예외를 잡거나 다른 BecalmError로 매핑해야 하면 이 helper를 쓰지 말고 inline 유지.
     */
    private suspend inline fun <T> safeApi(
        logMessage: String,
        block: () -> Response<T>,
    ): BecalmResult<Response<T>> = try {
        BecalmResult.Success(block())
    } catch (e: IOException) {
        logger.e(TAG, logMessage, e)
        BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
    }

    /**
     * transitionState의 TransitionError → BecalmError.Validation 매핑을 별도 확장으로 뽑았다.
     * 필드명과 메시지 문자열은 원본과 바이트-동일하게 보존한다.
     * updateActionState는 IllegalTransition 메시지에 `(action_state='$newState')` 컨텍스트를
     * 덧붙이기 때문에 이 helper를 쓰지 않고 inline을 유지한다(패턴이 다르면 inline).
     */
    private fun TransitionError.toBecalmError(): BecalmError = when (this) {
        is TransitionError.IllegalTransition -> BecalmError.Validation(
            field = "commitmentState",
            message = "Illegal transition: $from + ${event::class.simpleName}",
        )
        TransitionError.MissingSchedule -> BecalmError.Validation(
            field = "at",
            message = "Schedule event requires a non-null future instant",
        )
    }

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
                val retryAfter = headers()[HEADER_RETRY_AFTER]?.toLongOrNull()
                BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
            }
            in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(code, errorBody()?.string()))
            else -> BecalmResult.Failure(BecalmError.Network(code, message()))
        }
    }

    /**
     * HTTP status mapping for POST /v1/commitments:batch.
     *
     * Distinct from [toBecalmResult] because the batch endpoint contract declares 413 and
     * 422 with different semantics (api-contract.yml lines 200-208): 413 is a hard size
     * cap (client must chunk and retry), 422 is batch-level schema rejection. Both surface
     * as [BecalmError.Validation] with a distinguishing field to let the worker log the
     * cause without leaking details.
     */
    private fun Response<CommitmentBatchResponseDto>.toBatchBecalmResult(): BecalmResult<CommitmentRepository.BatchResponse> {
        if (isSuccessful) {
            val body = body()
            return if (body != null) {
                BecalmResult.Success(body.toDomain())
            } else {
                BecalmResult.Failure(BecalmError.ServerError(code(), "empty body"))
            }
        }
        return when (val code = code()) {
            401 -> BecalmResult.Failure(BecalmError.Unauthorized)
            413 -> BecalmResult.Failure(BecalmError.Validation(field = "batch_size", message = "batch too large"))
            422 -> {
                val msg = errorBody()?.string() ?: "validation error"
                BecalmResult.Failure(BecalmError.Validation(field = null, message = msg))
            }
            429 -> {
                val retryAfter = headers()[HEADER_RETRY_AFTER]?.toLongOrNull()
                BecalmResult.Failure(BecalmError.RateLimited(retryAfter))
            }
            in 500..599 -> BecalmResult.Failure(BecalmError.ServerError(code, errorBody()?.string()))
            else -> BecalmResult.Failure(BecalmError.Network(code, message() ?: "unknown error"))
        }
    }
}
