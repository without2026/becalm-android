package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.result.map
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentDto
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
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
private const val MAX_PAGES = REFRESH_PAGE_CAP
private const val TAG = "CommitmentRepository"

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
) : CommitmentRepository {

    // ── Reactive reads ────────────────────────────────────────────────────────

    override fun observeAllForUser(userId: String): Flow<List<CommitmentEntity>> =
        dao.observeAllForUser(userId)

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

        repeat(MAX_PAGES) { pageIndex ->
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
        val response = when (val apiResult = safeApi("fetchSingle network error id=$id") { api.getCommitment(id) }) {
            is BecalmResult.Failure -> return apiResult
            is BecalmResult.Success -> apiResult.value
        }

        return when (val result = response.toBecalmResult { it.data }) {
            is BecalmResult.Failure -> result
            is BecalmResult.Success -> {
                val entity = result.value.toEntity(result.value.userId)
                dao.insert(entity)
                BecalmResult.Success(entity)
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

        val lifecycleEvent = actionStateToLifecycleEvent(newState, entity.commitmentState)
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

        val response = when (val apiResult = safeApi("updateActionState network error id=$id") {
            api.patchCommitment(id, request = PatchCommitmentRequest(newState))
        }) {
            is BecalmResult.Failure -> return apiResult
            is BecalmResult.Success -> apiResult.value
        }

        return response.toBecalmResult { it }.map { }
    }

    /**
     * Maps an action_state target to the [CommitmentEvent] that should drive the
     * SP-36 lifecycle, or null when the action_state change has no lifecycle effect
     * (e.g. pending → reminded tracks follow-up intensity without changing lifecycle).
     *
     * Mapping (derived from commitment-management.spec.yml CMT-005/6/7):
     * - "completed" → [CommitmentEvent.MarkDone]
     * - "pending"   → [CommitmentEvent.ReopenFromDone] when currently DONE; otherwise no-op
     * - "reminded" / "followed_up" → no lifecycle change
     */
    private fun actionStateToLifecycleEvent(
        actionState: String,
        current: CommitmentState,
    ): CommitmentEvent? = when (actionState) {
        "completed" -> CommitmentEvent.MarkDone
        "pending"   -> if (current == CommitmentState.DONE) CommitmentEvent.ReopenFromDone else null
        else        -> null
    }

    // ── Sync helpers ──────────────────────────────────────────────────────────

    override suspend fun findPendingSync(userId: String, limit: Int): List<CommitmentEntity> =
        dao.findPendingSync(userId, limit)

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> =
        logger.daoOp(TAG, "markSynced failed") {
            dao.markSynced(ids)
        }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> =
        logger.daoOp(TAG, "deleteAllForUser failed userId=$userId", fallbackMessage = "deleteAllForUser failed") {
            dao.deleteAllForUser(userId)
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * refreshSince·fetchSingle·updateActionState 세 곳의 동일한 `try { api() } catch (IOException)`
     * 블록을 통합한다. 세 호출지 모두 같은 예외 타입(IOException), 같은 로그 레벨(e),
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
