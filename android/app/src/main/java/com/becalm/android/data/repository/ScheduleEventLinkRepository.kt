package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.daoOp
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.ScheduleEventLinkDao
import com.becalm.android.data.local.db.entity.ScheduleEventLinkEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ScheduleEventLinkDto
import com.becalm.android.data.remote.dto.ScheduleEventLinkStatusPatchDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import retrofit2.Response

public interface ScheduleEventLinkRepository {
    public suspend fun refreshSince(
        userId: String,
        since: Instant?,
        status: String? = null,
    ): BecalmResult<RefreshStats>

    public suspend fun updateStatus(userId: String, id: String, status: String): BecalmResult<ScheduleEventLinkEntity>

    public fun observePendingReview(userId: String): Flow<List<ScheduleEventLinkEntity>>

    public fun observeForTodayRange(
        userId: String,
        rangeStart: Instant,
        rangeEnd: Instant,
        calendarEventIds: List<String>,
        commitmentIds: List<String>,
    ): Flow<List<ScheduleEventLinkEntity>>

    public fun observeForProjectionRefs(
        userId: String,
        commitmentIds: List<String>,
        rawEventIds: List<String>,
        calendarEventIds: List<String>,
    ): Flow<List<ScheduleEventLinkEntity>>

    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

@Singleton
public class ScheduleEventLinkRepositoryImpl @Inject constructor(
    private val dao: ScheduleEventLinkDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScheduleEventLinkRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        dao: ScheduleEventLinkDao,
        api: RailwayApi,
        logger: Logger,
    ) : this(
        dao = dao,
        apiProvider = Provider { api },
        logger = logger,
    )

    override suspend fun refreshSince(
        userId: String,
        since: Instant?,
        status: String?,
    ): BecalmResult<ScheduleEventLinkRepository.RefreshStats> = withContext(ioDispatcher) {
        var cursor: String? = null
        var totalFetched = 0
        var totalUpserted = 0
        var lastHasMore = false
        var lastCursor: String? = null

        repeat(REFRESH_PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat
            val response = try {
                api.getScheduleEventLinks(
                    cursor = cursor,
                    limit = PAGE_LIMIT,
                    since = since?.toString(),
                    status = status,
                )
            } catch (e: IOException) {
                logger.e(TAG, "refreshSince network error on page $pageIndex", e)
                return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
            } catch (e: Exception) {
                logger.e(TAG, "refreshSince unexpected error on page $pageIndex", e)
                return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
            }

            if (!response.isSuccessful) {
                logger.w(TAG, "refreshSince HTTP ${response.code()} on page $pageIndex")
                return@withContext BecalmResult.Failure(response.toRefreshError())
            }
            val body = response.body()
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null body on page $pageIndex")),
                )
            val entities = body.data.map { it.toEntity(userId) }
            if (entities.isNotEmpty()) {
                dao.insertAll(entities)
            }
            totalFetched += body.data.size
            totalUpserted += entities.size
            lastHasMore = body.hasMore
            lastCursor = body.cursor
            cursor = body.cursor
        }

        BecalmResult.Success(
            ScheduleEventLinkRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = lastHasMore,
                nextCursor = lastCursor,
            ),
        )
    }

    override suspend fun updateStatus(
        userId: String,
        id: String,
        status: String,
    ): BecalmResult<ScheduleEventLinkEntity> = withContext(ioDispatcher) {
        val response = try {
            api.patchScheduleEventLink(id = id, request = ScheduleEventLinkStatusPatchDto(status))
        } catch (e: IOException) {
            logger.e(TAG, "updateStatus network error", e)
            return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (e: Exception) {
            logger.e(TAG, "updateStatus unexpected error", e)
            return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
        }
        if (!response.isSuccessful) {
            return@withContext BecalmResult.Failure(response.toRefreshError())
        }
        val dto = response.body()?.data
            ?: return@withContext BecalmResult.Failure(BecalmError.Unknown(IllegalStateException("null body")))
        val entity = dto.toEntity(userId)
        when (
            val localWrite = logger.daoOp(TAG, "updateStatus local upsert failed") {
                dao.insertAll(listOf(entity))
            }
        ) {
            is BecalmResult.Success -> Unit
            is BecalmResult.Failure -> return@withContext localWrite
        }
        BecalmResult.Success(entity)
    }

    override fun observePendingReview(userId: String): Flow<List<ScheduleEventLinkEntity>> =
        dao.observePendingReview(userId)

    override fun observeForTodayRange(
        userId: String,
        rangeStart: Instant,
        rangeEnd: Instant,
        calendarEventIds: List<String>,
        commitmentIds: List<String>,
    ): Flow<List<ScheduleEventLinkEntity>> =
        dao.observeForTodayRange(
            userId = userId,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            calendarEventIds = calendarEventIds.nonEmptySqlList(),
            commitmentIds = commitmentIds.nonEmptySqlList(),
        )

    override fun observeForProjectionRefs(
        userId: String,
        commitmentIds: List<String>,
        rawEventIds: List<String>,
        calendarEventIds: List<String>,
    ): Flow<List<ScheduleEventLinkEntity>> =
        dao.observeForProjectionRefs(
            userId = userId,
            commitmentIds = commitmentIds.nonEmptySqlList(),
            rawEventIds = rawEventIds.nonEmptySqlList(),
            calendarEventIds = calendarEventIds.nonEmptySqlList(),
        )

    private fun ScheduleEventLinkDto.toEntity(userId: String): ScheduleEventLinkEntity =
        ScheduleEventLinkEntity(
            id = id,
            userId = userId,
            calendarEventId = calendarEventId,
            calendarSourceType = calendarSourceType,
            calendarSourceRef = calendarSourceRef,
            sourceType = sourceType,
            sourceRef = sourceRef,
            rawEventId = rawEventId,
            commitmentId = commitmentId,
            relationType = relationType,
            status = status,
            confidence = confidence.coerceIn(0.0, 1.0),
            proposedStartAt = proposedStartAt,
            proposedEndAt = proposedEndAt,
            proposedTitle = proposedTitle,
            evidence = evidence,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun List<String>.nonEmptySqlList(): List<String> =
        if (isEmpty()) listOf("__none__") else this

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("schedule_event_links")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "ScheduleEventLinkRepo"
        private const val PAGE_LIMIT = 100
        private const val REFRESH_PAGE_CAP = 10
    }
}
