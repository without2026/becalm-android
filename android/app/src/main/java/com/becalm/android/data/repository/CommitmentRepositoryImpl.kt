package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.result.map
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import androidx.room.withTransaction
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.BeCalmDatabase
import com.becalm.android.data.local.db.dao.CommitmentDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentBatchRequestDto
import com.becalm.android.data.remote.dto.CommitmentBatchResponseDto
import com.becalm.android.data.remote.dto.CommitmentPatchDto
import com.becalm.android.data.remote.dto.PatchCommitmentRequest
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.repository.internal.MAX_BATCH_SIZE
import com.becalm.android.data.repository.internal.toBatchItemDto
import com.becalm.android.data.repository.internal.toDomain
import com.becalm.android.data.repository.internal.toEntity
import com.becalm.android.domain.commitment.CommitmentEditPatch
import com.becalm.android.domain.commitment.CommitmentEvent
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.CommitmentStateMachine
import com.becalm.android.domain.commitment.ManualCommitmentInput
import com.becalm.android.domain.commitment.TransitionError
import com.becalm.android.domain.commitment.TransitionResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import retrofit2.Response
import java.io.IOException
import java.util.UUID
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

/** Legal action_state values per data-model.yml:199-208 / commitment-management.spec.yml CMT-005..012. */
private val ALLOWED_ACTION_STATES: Set<String> =
    CommitmentState.entries.map { it.wireValue }.toSet()

/**
 * Production implementation of [CommitmentRepository].
 */
@Singleton
public class CommitmentRepositoryImpl @Inject constructor(
    private val dao: CommitmentDao,
    private val api: RailwayApi,
    private val cursorStore: SyncCursorStore,
    private val userPrefsStore: UserPrefsStore,
    private val database: BeCalmDatabase,
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

    override fun observeById(id: String): Flow<CommitmentEntity?> =
        dao.observeById(id)

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

        val current = CommitmentState.fromWire(entity.actionState)
        when (val result = CommitmentStateMachine.transition(current, event)) {
            is TransitionResult.Err -> BecalmResult.Failure(result.error.toBecalmError())
            is TransitionResult.Ok -> {
                val updated = entity.copy(
                    actionState = result.state.wireValue,
                    updatedAt = Clock.System.now(),
                    syncStatus = "pending",
                )
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
        // Validate the wire value against the spec enum. The spec-aligned lifecycle
        // now lives on the action_state column directly; transitionState() is the
        // canonical entry point for user actions, so we no longer bridge into the
        // legacy commitment_state column here (see CommitmentEntity.commitmentState
        // KDoc — it is a dead column as of Wave 4).
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
        // Retained lookup guards against a caller racing updateActionState against a
        // local deletion — we fail loudly rather than silently no-op the DAO update.
        @Suppress("UNUSED_VARIABLE") val guard = entity

        dao.updateActionState(id, newState, updatedAt)

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

    // ── Edit / dispute / soft-delete / supersede ─────────────────────────────

    override suspend fun editCommitment(
        id: String,
        patch: CommitmentEditPatch,
    ): BecalmResult<Unit> = withContext(ioDispatcher) {
        val actorId = resolveActorId()
            ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
        val editedAt = Clock.System.now()

        val rows = dao.applyEdit(
            id = id,
            title = patch.title,
            dueAt = patch.dueAt,
            dueHint = patch.dueHint,
            approx = patch.dueIsApproximate,
            personRef = patch.personRef,
            direction = patch.direction,
            actorId = actorId,
            editedAt = editedAt,
        )
        if (rows == 0) {
            return@withContext BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))
        }

        // Best-effort server PATCH. A 2xx flips sync_status='synced'; anything
        // else (non-2xx or IOException) leaves 'pending' so UploadWorker retries.
        bestEffortPatch(
            id = id,
            dto = CommitmentPatchDto(
                title = patch.title,
                dueAt = patch.dueAt,
                dueHint = patch.dueHint,
                dueIsApproximate = patch.dueIsApproximate,
                personRef = patch.personRef,
                direction = patch.direction,
                lastEditedBy = actorId,
                lastEditedAt = editedAt,
            ),
            logTag = "editCommitment",
        )
        BecalmResult.Success(Unit)
    }

    override suspend fun markQuoteDisputed(id: String): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            val actorId = resolveActorId()
                ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
            val at = Clock.System.now()
            val rows = dao.markQuoteDisputed(id = id, actor = actorId, at = at)
            if (rows == 0) {
                return@withContext BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))
            }
            bestEffortPatch(
                id = id,
                dto = CommitmentPatchDto(
                    quoteDisputed = true,
                    quoteDisputedAt = at,
                    lastEditedBy = actorId,
                    lastEditedAt = at,
                ),
                logTag = "markQuoteDisputed",
            )
            BecalmResult.Success(Unit)
        }

    override suspend fun clearQuoteDispute(id: String): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            val actorId = resolveActorId()
                ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
            val at = Clock.System.now()
            val rows = dao.clearQuoteDispute(id = id, actor = actorId, at = at)
            if (rows == 0) {
                return@withContext BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))
            }
            bestEffortPatch(
                id = id,
                dto = CommitmentPatchDto(
                    quoteDisputed = false,
                    lastEditedBy = actorId,
                    lastEditedAt = at,
                ),
                logTag = "clearQuoteDispute",
            )
            BecalmResult.Success(Unit)
        }

    override suspend fun softDelete(id: String): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            val actorId = resolveActorId()
                ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
            val at = Clock.System.now()
            val rows = dao.softDelete(id = id, actor = actorId, at = at)
            if (rows == 0) {
                return@withContext BecalmResult.Failure(BecalmError.NotFound("commitment/$id"))
            }
            bestEffortPatch(
                id = id,
                dto = CommitmentPatchDto(
                    deletedAt = at,
                    lastEditedBy = actorId,
                    lastEditedAt = at,
                ),
                logTag = "softDelete",
            )
            BecalmResult.Success(Unit)
        }

    override suspend fun supersede(
        oldId: String,
        newRow: CommitmentEntity,
    ): BecalmResult<String> = withContext(ioDispatcher) {
        val actorId = resolveActorId()
            ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
        val editedAt = Clock.System.now()

        // Atomic: INSERT new row + soft-delete old row. Room's withTransaction wraps
        // both DAO calls in a single SQLite transaction so a crash between them
        // cannot leave the table with either a dangling supersede child (new row
        // inserted but old row not tombstoned) or an orphaned tombstone (old row
        // tombstoned but new row not inserted).
        val result: BecalmResult<String> = try {
            database.withTransaction {
                dao.insert(newRow)
                val deletedRows = dao.softDelete(id = oldId, actor = actorId, at = editedAt)
                if (deletedRows == 0) {
                    BecalmResult.Failure(BecalmError.NotFound("commitment/$oldId"))
                } else {
                    BecalmResult.Success(newRow.id)
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "supersede transaction failed oldId=$oldId", e)
            BecalmResult.Failure(BecalmError.Io(e.message ?: "transaction failed"))
        }
        if (result is BecalmResult.Failure) return@withContext result

        // Best-effort uploads. POST the new row via batch endpoint and PATCH the
        // old row's tombstone. Both failures are absorbed into the retry queue.
        runCatching {
            api.uploadCommitmentsBatch(
                request = CommitmentBatchRequestDto(commitments = listOf(newRow.toBatchItemDto())),
            )
        }.onFailure { e ->
            if (e is IOException) {
                logger.w(TAG, "supersede batch upload IOException newId=${newRow.id}")
            } else {
                logger.e(TAG, "supersede batch upload failed newId=${newRow.id}", e)
            }
        }
        bestEffortPatch(
            id = oldId,
            dto = CommitmentPatchDto(
                deletedAt = editedAt,
                lastEditedBy = actorId,
                lastEditedAt = editedAt,
            ),
            logTag = "supersede/tombstoneOld",
        )
        result
    }

    // ── Manual create (MAN-001..006) ──────────────────────────────────────────

    override suspend fun saveManualCommitment(
        input: ManualCommitmentInput,
        supersedeOf: String?,
    ): BecalmResult<String> = withContext(ioDispatcher) {
        val actorId = resolveActorId()
            ?: return@withContext BecalmResult.Failure(BecalmError.Unauthorized)
        val now = Clock.System.now()
        val newId = UUID.randomUUID().toString()

        // Build the manual row. Contract pinned by `.spec/manual-commitment.spec.yml`
        // invariants:
        //   - source_type = 'manual' (spec MAN-003).
        //   - confidence  = 1.0 (user-entered; spec MAN-003).
        //   - source_ref + source_event_title are NULL; source_event_occurred_at
        //     equals created_at (the user's save moment), NOT an LLM-extracted
        //     event timestamp (spec MAN-003 + invariant 2).
        //   - last_edited_by/at are populated immediately so the audit trail
        //     mirrors the LLM-extracted rows (EDIT-008 parity).
        //   - supersedes_commitment_id carries `supersedeOf` when present.
        // GREP GUARD: this path MUST NEVER insert into raw_ingestion_events
        // (`rawIngestionEventDao.insert` / `rawEventDao.insert` etc.). Manual
        // commitments have no backing raw event by spec MAN-003 invariant 4.
        val newRow = CommitmentEntity(
            id = newId,
            userId = actorId,
            direction = input.direction,
            counterpartyRaw = null,
            personRef = input.personRef,
            title = input.title,
            description = null,
            quote = input.quote,
            sourceEventTitle = null,
            sourceEventOccurredAt = now,
            dueAt = input.dueAt,
            dueHint = input.dueHint,
            dueIsApproximate = input.dueIsApproximate,
            actionState = "pending",
            sourceType = SourceType.MANUAL,
            sourceRef = null,
            confidence = 1.0,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "pending",
            createdAt = now,
            updatedAt = now,
            lastEditedBy = actorId,
            lastEditedAt = now,
            quoteDisputed = false,
            quoteDisputedAt = null,
            deletedAt = null,
            supersedesCommitmentId = supersedeOf,
        )

        if (supersedeOf != null) {
            // EDIT-007 reuses this path: supersede() wraps INSERT + softDelete
            // in a single Room transaction + best-effort upload of the new row.
            return@withContext supersede(supersedeOf, newRow)
        }

        // Plain manual create — local INSERT then best-effort batch POST.
        try {
            dao.insert(newRow)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.e(TAG, "saveManualCommitment dao.insert failed id=$newId", e)
            return@withContext BecalmResult.Failure(BecalmError.Io(e.message ?: "dao insert failed"))
        }

        // Best-effort upload. Failure is absorbed into the standard
        // `sync_status='pending'` retry queue (UploadWorker drains it).
        runCatching {
            api.uploadCommitmentsBatch(
                request = CommitmentBatchRequestDto(commitments = listOf(newRow.toBatchItemDto())),
            )
        }.onSuccess { response ->
            if (response.isSuccessful) {
                dao.markSynced(listOf(newId))
            } else {
                logger.w(
                    TAG,
                    "saveManualCommitment upload non-2xx id=$newId http=${response.code()} — leaving pending",
                )
            }
        }.onFailure { e ->
            if (e is IOException) {
                logger.w(TAG, "saveManualCommitment upload IOException id=$newId — leaving pending")
            } else {
                e.rethrowIfCancellation()
                logger.e(TAG, "saveManualCommitment upload failed id=$newId", e)
            }
        }
        BecalmResult.Success(newId)
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
     * Resolves the acting Supabase user id from [UserPrefsStore]. Returns `null`
     * when the user is signed out or the persisted id is blank — callers must
     * map that to [BecalmError.Unauthorized] and short-circuit.
     *
     * The EDIT-003/005/006/007 audit columns (`last_edited_by`) and the
     * supersede tombstone MUST carry a real user id per
     * `.spec/commitment-edit.spec.yml` invariant 3 (audit trail). We refuse to
     * write a synthetic "unknown" actor — that would silently corrupt the
     * audit log which PIPA 제36조 정정권 (correction right) audits rely on.
     */
    private suspend fun resolveActorId(): String? =
        userPrefsStore.observeCurrentUserId().firstOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Best-effort `PATCH /v1/commitments/{id}` for the Stage-5 edit /
     * dispute / soft-delete / supersede flows.
     *
     * The local DAO write has already landed (caller checks rows>0). This
     * helper is purely about the server mirror:
     * - 2xx → flip `sync_status='synced'` so the UploadWorker does not
     *   re-upload on the next run.
     * - Non-2xx, IOException, or any other exception → leave `sync_status='pending'`
     *   so [UploadWorker.flushCommitments] retries via [uploadBatch] on the
     *   next trigger. This matches the EDIT-003 / EDIT-006 invariant 2
     *   ("offline edits must eventually reach the server").
     *
     * Does NOT propagate failures back up — the caller's contract is
     * "local write landed; server catch-up is async". Returns [Unit] to
     * underline that intent at call sites.
     *
     * @param id     UUID of the commitment row being mirrored.
     * @param dto    Partial patch body carrying only the mutated columns.
     * @param logTag Short label used to disambiguate log lines (which flow
     *               triggered this mirror).
     */
    private suspend fun bestEffortPatch(
        id: String,
        dto: CommitmentPatchDto,
        logTag: String,
    ) {
        try {
            val response = api.updateCommitment(id = id, request = dto)
            if (response.isSuccessful) {
                dao.markSynced(listOf(id))
            } else {
                logger.w(TAG, "$logTag PATCH non-2xx id=$id http=${response.code()} — leaving pending")
            }
        } catch (e: IOException) {
            logger.w(TAG, "$logTag PATCH IOException id=$id msg=${e.message} — leaving pending")
        } catch (e: Exception) {
            // Preserve structured-concurrency cancellation; anything else is
            // best-effort and absorbed into the retry queue.
            e.rethrowIfCancellation()
            logger.e(TAG, "$logTag PATCH unexpected error id=$id — leaving pending", e)
        }
    }

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
            field = "actionState",
            message = "Illegal transition: $from + ${event::class.simpleName}",
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
