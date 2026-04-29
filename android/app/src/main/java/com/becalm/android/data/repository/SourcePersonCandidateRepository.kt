package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.SourcePersonCandidateEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourcePersonCandidateDto
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import retrofit2.Response

public interface SourcePersonCandidateRepository {
    public suspend fun refreshSince(
        userId: String,
        sourceType: String? = null,
        since: Instant? = null,
    ): BecalmResult<RefreshStats>

    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

@Singleton
public class SourcePersonCandidateRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourcePersonCandidateRepository {

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
        sourceType: String?,
        since: Instant?,
    ): BecalmResult<SourcePersonCandidateRepository.RefreshStats> = withContext(ioDispatcher) {
        var cursor: String? = null
        var totalFetched = 0
        var totalUpserted = 0
        var lastHasMore = false
        var lastCursor: String? = null

        repeat(REFRESH_PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat

            val response = try {
                api.getSourcePersonCandidates(
                    cursor = cursor,
                    limit = PAGE_LIMIT,
                    since = since?.toString(),
                    sourceType = sourceType,
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
            if (entities.isNotEmpty()) personIndexDao.upsertCandidates(entities)

            totalFetched += body.data.size
            totalUpserted += entities.size
            lastHasMore = body.hasMore
            lastCursor = body.cursor
            cursor = body.cursor
        }

        logger.d(
            TAG,
            "refreshSince done sourceType=$sourceType fetched=$totalFetched upserted=$totalUpserted",
        )
        BecalmResult.Success(
            SourcePersonCandidateRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = lastHasMore,
                nextCursor = lastCursor,
            ),
        )
    }

    private fun SourcePersonCandidateDto.toEntity(userId: String): SourcePersonCandidateEntity =
        SourcePersonCandidateEntity(
            id = id.ifBlank {
                UUID.nameUUIDFromBytes("$userId:$sourceType:$sourceRef:$candidateRef".toByteArray()).toString()
            },
            userId = userId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            candidateRef = candidateRef,
            role = role,
            name = name,
            email = email,
            phone = phone,
            organization = organization,
            evidence = evidence,
            confidence = confidence.coerceIn(0.0, 1.0),
            createdAt = createdAt,
        )

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("source_person_candidates")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "SourcePersonCandidateRepo"
        private const val PAGE_LIMIT = 100
        private const val REFRESH_PAGE_CAP = 10
    }
}
