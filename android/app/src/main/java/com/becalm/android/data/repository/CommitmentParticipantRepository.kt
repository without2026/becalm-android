package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.CommitmentParticipantEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.CommitmentParticipantDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import retrofit2.Response

public interface CommitmentParticipantRepository {
    public suspend fun refreshSince(
        userId: String,
        since: Instant? = null,
        personId: String? = null,
        commitmentId: String? = null,
    ): BecalmResult<RefreshStats>

    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

@Singleton
public class CommitmentParticipantRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CommitmentParticipantRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        personIndexDao: PersonIndexDao,
        api: RailwayApi,
        logger: Logger,
    ) : this(
        personIndexDao = personIndexDao,
        apiProvider = Provider { api },
        logger = logger,
    )

    override suspend fun refreshSince(
        userId: String,
        since: Instant?,
        personId: String?,
        commitmentId: String?,
    ): BecalmResult<CommitmentParticipantRepository.RefreshStats> = withContext(ioDispatcher) {
        var cursor: String? = null
        var totalFetched = 0
        var totalUpserted = 0
        var lastHasMore = false
        var lastCursor: String? = null

        repeat(REFRESH_PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat

            val response = try {
                api.getCommitmentParticipants(
                    cursor = cursor,
                    limit = PAGE_LIMIT,
                    since = since?.toString(),
                    personId = personId,
                    commitmentId = commitmentId,
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
            val participants = body.data.map { it.toEntity(userId) }
            if (participants.isNotEmpty()) {
                personIndexDao.upsertCommitmentParticipants(participants)
            }

            totalFetched += body.data.size
            totalUpserted += participants.size
            lastHasMore = body.hasMore
            lastCursor = body.cursor
            cursor = body.cursor
        }

        logger.d(
            TAG,
            "refreshSince done fetched=$totalFetched upserted=$totalUpserted",
        )
        BecalmResult.Success(
            CommitmentParticipantRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = lastHasMore,
                nextCursor = lastCursor,
            ),
        )
    }

    private fun CommitmentParticipantDto.toEntity(userId: String): CommitmentParticipantEntity =
        CommitmentParticipantEntity(
            id = id,
            userId = userId,
            commitmentId = commitmentId,
            personId = personId,
            role = role,
            evidence = evidence,
            confidence = confidence.coerceIn(0.0, 1.0),
            createdAt = createdAt,
        )

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("commitment_participants")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "CommitmentParticipantRepo"
        private const val PAGE_LIMIT = 100
        private const val REFRESH_PAGE_CAP = 10
    }
}
