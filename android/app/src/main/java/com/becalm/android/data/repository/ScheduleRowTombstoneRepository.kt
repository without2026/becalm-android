package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.ScheduleRowTombstoneDao
import com.becalm.android.data.local.db.entity.ScheduleRowTombstoneEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.ScheduleRowTombstoneRequestDto
import com.becalm.android.domain.schedule.ScheduleRowRef
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import retrofit2.Response

public interface ScheduleRowTombstoneRepository {
    public suspend fun tombstone(userId: String, rowRef: ScheduleRowRef): BecalmResult<Unit>

    public suspend fun findPendingSync(userId: String, limit: Int): List<ScheduleRowTombstoneEntity>

    public suspend fun upload(entity: ScheduleRowTombstoneEntity): BecalmResult<Unit>

    public suspend fun markSynced(ids: List<String>): BecalmResult<Unit>

    public suspend fun markFailed(id: String): BecalmResult<Unit>
}

public object NoopScheduleRowTombstoneRepository : ScheduleRowTombstoneRepository {
    override suspend fun tombstone(userId: String, rowRef: ScheduleRowRef): BecalmResult<Unit> =
        BecalmResult.Success(Unit)

    override suspend fun findPendingSync(userId: String, limit: Int): List<ScheduleRowTombstoneEntity> =
        emptyList()

    override suspend fun upload(entity: ScheduleRowTombstoneEntity): BecalmResult<Unit> =
        BecalmResult.Success(Unit)

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> =
        BecalmResult.Success(Unit)

    override suspend fun markFailed(id: String): BecalmResult<Unit> =
        BecalmResult.Success(Unit)
}

private const val TAG = "ScheduleRowTombstoneRepo"

@Singleton
public class ScheduleRowTombstoneRepositoryImpl @Inject constructor(
    private val dao: ScheduleRowTombstoneDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScheduleRowTombstoneRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    override suspend fun tombstone(userId: String, rowRef: ScheduleRowRef): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            val now = Clock.System.now()
            val entity = rowRef.toEntity(userId = userId, now = now)
            try {
                dao.upsert(entity)
            } catch (e: Exception) {
                logger.e(TAG, "local tombstone write failed", e)
                return@withContext BecalmResult.Failure(BecalmError.Io(e.message ?: "tombstone write failed"))
            }

            when (val uploadResult = upload(entity)) {
                is BecalmResult.Success -> markSynced(listOf(entity.id))
                is BecalmResult.Failure -> logger.w(TAG, "remote tombstone pending retry: ${uploadResult.error}")
            }
            BecalmResult.Success(Unit)
        }

    override suspend fun findPendingSync(userId: String, limit: Int): List<ScheduleRowTombstoneEntity> =
        dao.findPendingSync(userId, limit)

    override suspend fun upload(entity: ScheduleRowTombstoneEntity): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            val response = try {
                api.upsertScheduleRowTombstone(
                    request = ScheduleRowTombstoneRequestDto(
                        rowType = entity.rowType,
                        sourceEventId = entity.sourceEventId,
                        sourceType = entity.sourceType,
                        sourceRef = entity.sourceRef,
                        deletedAt = entity.deletedAt,
                    ),
                )
            } catch (e: IOException) {
                logger.w(TAG, "tombstone upload network error")
                return@withContext BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
            } catch (e: Exception) {
                logger.e(TAG, "tombstone upload unexpected error", e)
                return@withContext BecalmResult.Failure(BecalmError.Unknown(e))
            }
            if (!response.isSuccessful) {
                logger.w(TAG, "tombstone upload HTTP ${response.code()}")
                return@withContext BecalmResult.Failure(response.toError())
            }
            BecalmResult.Success(Unit)
        }

    override suspend fun markSynced(ids: List<String>): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            if (ids.isEmpty()) return@withContext BecalmResult.Success(Unit)
            try {
                dao.markSynced(ids, Clock.System.now())
                BecalmResult.Success(Unit)
            } catch (e: Exception) {
                logger.e(TAG, "markSynced failed", e)
                BecalmResult.Failure(BecalmError.Io(e.message ?: "markSynced failed"))
            }
        }

    override suspend fun markFailed(id: String): BecalmResult<Unit> =
        withContext(ioDispatcher) {
            try {
                dao.markFailed(id, Clock.System.now())
                BecalmResult.Success(Unit)
            } catch (e: Exception) {
                logger.e(TAG, "markFailed failed", e)
                BecalmResult.Failure(BecalmError.Io(e.message ?: "markFailed failed"))
            }
        }

    private fun ScheduleRowRef.toEntity(userId: String, now: kotlinx.datetime.Instant): ScheduleRowTombstoneEntity =
        when (this) {
            is ScheduleRowRef.CalendarEvent -> ScheduleRowTombstoneEntity(
                id = "$userId:$id",
                userId = userId,
                rowType = "calendar_event",
                sourceEventId = id,
                sourceType = sourceType,
                sourceRef = sourceRef,
                deletedAt = now,
                createdAt = now,
                updatedAt = now,
            )
            is ScheduleRowRef.Meeting -> ScheduleRowTombstoneEntity(
                id = "$userId:$id",
                userId = userId,
                rowType = "meeting",
                sourceEventId = id,
                sourceType = sourceType,
                sourceRef = sourceRef,
                deletedAt = now,
                createdAt = now,
                updatedAt = now,
            )
            is ScheduleRowRef.Commitment -> error("commitment row uses CommitmentRepository.softDelete")
        }

    private fun <T> Response<T>.toError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("schedule_row_tombstones")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers().get("Retry-After")?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }
}
