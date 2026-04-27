package com.becalm.android.data.repository

import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.CalendarEventDao
import com.becalm.android.data.local.db.entity.CalendarEventEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CalendarEventDto
import com.becalm.android.data.remote.dto.CalendarSyncResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// ─── Interface ───────────────────────────────────────────────────────────────

/**
 * Local cache and sync coordinator for calendar events.
 *
 * Reactive reads are backed by Room's invalidation tracker. Mutations flow
 * exclusively through [refreshSince] (pull from Railway) and [triggerServerSync]
 * (kick the server-side calendar-provider pull). Android never writes calendar
 * rows directly.
 */
public interface CalendarEventRepository {

    /**
     * Emits all events for [userId] whose [CalendarEventEntity.startAt] falls within
     * [[fromInstant], [toInstant]), re-emitting on every table change.
     */
    public fun observeForUser(
        userId: String,
        fromInstant: Instant,
        toInstant: Instant,
    ): Flow<List<CalendarEventEntity>>

    /**
     * Emits up to [limit] events for [userId] where [CalendarEventEntity.attendeesRaw]
     * contains [personRef], re-emitting on every table change.
     *
     * Filtering is performed in-memory after Room emits; [limit] is applied after the filter.
     */
    public fun observeForPerson(
        userId: String,
        personRef: String,
        limit: Int = 100,
    ): Flow<List<CalendarEventEntity>>

    /**
     * Pulls calendar events from Railway that have changed since [since], upserts them
     * into Room, and advances the cursor in [SyncCursorStore] under key `"calendar_events"`.
     *
     * When [since] is `null` the stored cursor (if any) is used as the resume token.
     * A missing cursor causes a full fetch. Capped at 5 pages per call to bound latency.
     *
     * @return [BecalmResult.Success] with [RefreshStats], or a typed failure on the first
     *   unrecoverable error.
     */
    public suspend fun refreshSince(
        userId: String,
        since: Instant?,
        rangeStart: Instant? = null,
        rangeEnd: Instant? = null,
    ): BecalmResult<RefreshStats>

    /**
     * Upserts [entities] into the local `calendar_events` table in a single transaction.
     *
     * Retained as a local persistence seam for tests and any future caller that already has
     * canonicalized calendar rows in memory. The current production path still prefers
     * [refreshSince] + [triggerServerSync].
     *
     * @return [BecalmResult.Success] with the count of rows written, or a typed failure
     *   on local I/O error.
     */
    public suspend fun insertLocalBatch(entities: List<CalendarEventEntity>): BecalmResult<Int>

    /**
     * Triggers a server-side calendar-provider sync (Google/Outlook → Supabase).
     *
     * Idempotent — multiple calls merge rather than duplicate. Callers (SP-26/SP-27
     * workers) invoke this before [refreshSince] to materialise new server rows.
     *
     * @return [BecalmResult.Success] with [CalendarSyncResponse], or a typed failure
     *   (including [BecalmError.RateLimited] when HTTP 429 is returned).
     */
    public suspend fun triggerServerSync(): BecalmResult<CalendarSyncResponse>

    /**
     * Deletes all cached events owned by [userId].
     *
     * @return [BecalmResult.Success] with the number of rows deleted.
     */
    public suspend fun deleteAllForUser(userId: String): BecalmResult<Int>

    /**
     * Aggregated outcome of a single [refreshSince] call.
     *
     * @property fetched    Total DTOs received across all pages in this call.
     * @property upserted   Total rows written to Room.
     * @property hasMore    True when the server signalled additional pages beyond the page cap.
     * @property nextCursor Cursor to resume from on the next call; `null` when fully caught up.
     */
    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

// ─── Implementation ──────────────────────────────────────────────────────────

private const val TAG = "CalendarEventRepository"
private const val CURSOR_KEY = "calendar_events"
private val EPOCH_START: Instant = Instant.fromEpochMilliseconds(0)
private val EPOCH_END: Instant = Instant.fromEpochMilliseconds(Long.MAX_VALUE)

/**
 * Production implementation of [CalendarEventRepository].
 */
@Singleton
public class CalendarEventRepositoryImpl @Inject constructor(
    private val dao: CalendarEventDao,
    private val api: RailwayApi,
    private val cursorStore: SyncCursorStore,
    private val logger: Logger,
) : CalendarEventRepository {

    override fun observeForUser(
        userId: String,
        fromInstant: Instant,
        toInstant: Instant,
    ): Flow<List<CalendarEventEntity>> =
        dao.observeInRange(userId, fromInstant, toInstant)

    override fun observeForPerson(
        userId: String,
        personRef: String,
        limit: Int,
    ): Flow<List<CalendarEventEntity>> =
        // Full-range scan; filter in-memory because no attendee index exists in the DAO.
        dao.observeInRange(userId, EPOCH_START, EPOCH_END).map { events ->
            events.filter { it.attendeesRaw?.contains(personRef) == true }.take(limit)
        }

    override suspend fun refreshSince(
        userId: String,
        since: Instant?,
        rangeStart: Instant?,
        rangeEnd: Instant?,
    ): BecalmResult<CalendarEventRepository.RefreshStats> = safeApi(
        ioLogMessage = "refreshSince network error",
        unexpectedLogMessage = "refreshSince unexpected error",
    ) {
        // Resume from the persisted cursor; override start-time via `since` when supplied.
        var cursor: String? = cursorStore.observeCursor(CURSOR_KEY).first()
        val sinceStr: String? = since?.toString()

        var totalFetched = 0
        var totalUpserted = 0
        var serverHasMore = false
        var lastCursor: String? = cursor

        for (page in 1..REFRESH_PAGE_CAP) {
            val outcome = when (val r = fetchAndPersistPage(userId, cursor, sinceStr, page, rangeStart, rangeEnd)) {
                is BecalmResult.Success -> r.value
                is BecalmResult.Failure -> return@safeApi BecalmResult.Failure(r.error)
            }
            totalFetched += outcome.fetched
            totalUpserted += outcome.upserted
            lastCursor = outcome.cursor
            serverHasMore = outcome.hasMore

            if (!outcome.hasMore) break
            cursor = outcome.cursor
        }

        logger.d(TAG, "refreshSince done fetched=$totalFetched upserted=$totalUpserted hasMore=$serverHasMore")
        BecalmResult.Success(
            CalendarEventRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = serverHasMore,
                nextCursor = lastCursor,
            ),
        )
    }

    /**
     * Single-page fetch + persist unit for [refreshSince].
     *
     * Reads one page from [RailwayApi.getCalendarEvents], writes entities via [CalendarEventDao.insertAll],
     * and advances the [SyncCursorStore] cursor on successful persistence. Returns a [PageOutcome]
     * describing counts + server paging hints, or a typed [BecalmError] on HTTP / null-body failure.
     *
     * Log strings preserved byte-identical with the former inlined loop body.
     */
    private suspend fun fetchAndPersistPage(
        userId: String,
        cursor: String?,
        sinceStr: String?,
        page: Int,
        rangeStart: Instant?,
        rangeEnd: Instant?,
    ): BecalmResult<PageOutcome> {
        val response = api.getCalendarEvents(
            cursor = cursor,
            since = sinceStr,
        )
        if (!response.isSuccessful) {
            logger.w(TAG, "refreshSince HTTP ${response.code()} on page $page")
            return BecalmResult.Failure(response.toError())
        }
        val body = response.body()
            ?: return BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("null body on page $page")),
            )

        val filtered = body.data.filter { dto ->
            val meetsStart = rangeStart == null || dto.startAt >= rangeStart
            val meetsEnd = rangeEnd == null || dto.startAt < rangeEnd
            meetsStart && meetsEnd
        }
        val entities = filtered.map { it.toEntity(userId) }
        dao.insertAll(entities)

        // Persist the cursor immediately after each page is durably written.
        // If the process is killed mid-refresh, the next run resumes from the
        // last successfully upserted page instead of re-fetching from scratch.
        cursorStore.setCursor(CURSOR_KEY, body.cursor)

        return BecalmResult.Success(
            PageOutcome(
                fetched = filtered.size,
                upserted = entities.size,
                cursor = body.cursor,
                hasMore = body.hasMore,
            ),
        )
    }

    /** Per-page result for [fetchAndPersistPage]. */
    private data class PageOutcome(
        val fetched: Int,
        val upserted: Int,
        val cursor: String?,
        val hasMore: Boolean,
    )

    override suspend fun insertLocalBatch(entities: List<CalendarEventEntity>): BecalmResult<Int> {
        if (entities.isEmpty()) return BecalmResult.Success(0)
        return logger.daoOp(TAG, "insertLocalBatch failed") {
            val rowIds = dao.insertAll(entities)
            logger.d(TAG, "insertLocalBatch wrote=${rowIds.size}")
            rowIds.size
        }
    }

    override suspend fun triggerServerSync(): BecalmResult<CalendarSyncResponse> = safeApi(
        ioLogMessage = "triggerServerSync network error",
        unexpectedLogMessage = "triggerServerSync unexpected error",
    ) {
        val response = api.syncCalendarEvents()
        if (!response.isSuccessful) {
            logger.w(TAG, "triggerServerSync HTTP ${response.code()}")
            return@safeApi BecalmResult.Failure(response.toError())
        }
        val body = response.body()
            ?: return@safeApi BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("null body")),
            )
        logger.d(TAG, "triggerServerSync synced=${body.synced}")
        BecalmResult.Success(body)
    }

    override suspend fun deleteAllForUser(userId: String): BecalmResult<Int> =
        logger.daoOp(TAG, "deleteAllForUser failed userId=$userId") {
            val count = dao.deleteAllForUser(userId)
            logger.d(TAG, "deleteAllForUser userId=$userId deleted=$count")
            count
        }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * refreshSince·triggerServerSync 두 곳의 동일한
     * `catch (IOException) → Network(0, e.message ?: "IO"); catch (Throwable) → Unknown(e)`
     * 블록을 통합한다. 두 호출지 모두 같은 예외 타입 쌍, 같은 로그 레벨(e),
     * 같은 에러 변환을 사용하기 때문에 바이트-동일 패턴이 유지된다.
     * [ioLogMessage]·[unexpectedLogMessage]는 원본 인라인 블록의 문자열을 그대로
     * 보존한다(예: "refreshSince network error" vs "triggerServerSync network error").
     * [block]은 이미 BecalmResult를 돌려주므로(성공 분기가 Failure를 섞어 돌려보낼 수 있음),
     * 이 helper는 오직 예외 → BecalmResult.Failure 변환만 담당한다. 다른 예외 타입을
     * 잡아야 하거나 다른 BecalmError로 매핑해야 하면 이 helper를 쓰지 말고 inline 유지.
     */
    private suspend inline fun <T> safeApi(
        ioLogMessage: String,
        unexpectedLogMessage: String,
        block: () -> BecalmResult<T>,
    ): BecalmResult<T> = try {
        block()
    } catch (e: IOException) {
        logger.e(TAG, ioLogMessage, e)
        BecalmResult.Failure(BecalmError.Network(0, e.message ?: "IO"))
    } catch (e: Throwable) {
        logger.e(TAG, unexpectedLogMessage, e)
        BecalmResult.Failure(BecalmError.Unknown(e))
    }

    /** Maps a non-2xx [Response] to the appropriate [BecalmError] subtype. */
    private fun <T> Response<T>.toError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("calendar_events")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers().get("Retry-After")?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    /** Converts a wire DTO to a Room entity, stamping [userId] from the auth context. */
    private fun CalendarEventDto.toEntity(userId: String): CalendarEventEntity =
        CalendarEventEntity(
            id = id,
            userId = userId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            title = title,
            startAt = startAt,
            endAt = endAt,
            attendeesRaw = attendeesRaw,
            syncStatus = syncStatus ?: "synced",
        )
}
