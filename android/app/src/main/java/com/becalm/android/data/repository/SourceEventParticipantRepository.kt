package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceEventParticipantDto
import com.becalm.android.domain.person.PersonIdentityResolver
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import retrofit2.Response

public interface SourceEventParticipantRepository {
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
public class SourceEventParticipantRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceEventParticipantRepository {

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
    ): BecalmResult<SourceEventParticipantRepository.RefreshStats> = withContext(ioDispatcher) {
        var cursor: String? = null
        var totalFetched = 0
        var totalUpserted = 0
        var lastHasMore = false
        var lastCursor: String? = null

        repeat(REFRESH_PAGE_CAP) { pageIndex ->
            if (pageIndex > 0 && !lastHasMore) return@repeat

            val response = try {
                api.getSourceEventParticipants(
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
            val participants = body.data.map { it.toEntity(userId) }
            if (participants.isNotEmpty()) {
                personIndexDao.upsertPersons(participants.mapNotNull { it.toPersonEntity() })
                personIndexDao.upsertIdentities(participants.mapNotNull { it.toPersonIdentityEntity() })
                personIndexDao.upsertSourceEventParticipants(participants)
                personIndexDao.upsertDirtySources(
                    PersonIndexDirtySources.forSourceParticipants(
                        participants = participants,
                        reason = "source_participant_refresh",
                        now = Clock.System.now(),
                    ),
                )
            }

            totalFetched += body.data.size
            totalUpserted += participants.size
            lastHasMore = body.hasMore
            lastCursor = body.cursor
            cursor = body.cursor
        }

        logger.d(
            TAG,
            "refreshSince done sourceType=$sourceType fetched=$totalFetched upserted=$totalUpserted",
        )
        BecalmResult.Success(
            SourceEventParticipantRepository.RefreshStats(
                fetched = totalFetched,
                upserted = totalUpserted,
                hasMore = lastHasMore,
                nextCursor = lastCursor,
            ),
        )
    }

    private fun SourceEventParticipantDto.toEntity(userId: String): SourceEventParticipantEntity =
        SourceEventParticipantEntity(
            id = id,
            userId = userId,
            sourceEventId = sourceEventId,
            sourceType = sourceType,
            sourceRef = sourceRef,
            personId = personId,
            role = role,
            relationToUser = relationToUser,
            identityType = identityType,
            normalizedValue = normalizedValue,
            displayNameRaw = displayNameRaw,
            emailRaw = emailRaw,
            phoneRaw = phoneRaw,
            organizationRaw = organizationRaw,
            titleRaw = titleRaw,
            evidence = evidence,
            confidence = confidence.coerceIn(0.0, 1.0),
            resolutionStatus = resolutionStatus,
            createdAt = createdAt,
        )

    private fun SourceEventParticipantEntity.toPersonEntity(): PersonEntity? {
        val id = personId ?: return null
        return PersonEntity(
            id = id,
            userId = userId,
            displayName = displayNameRaw ?: organizationRaw ?: normalizedValue ?: emailRaw ?: phoneRaw ?: id,
            kind = if (identityType == "organization") "organization" else "person",
            primaryEmail = emailRaw ?: normalizedValue.takeIf { identityType == "email" },
            primaryPhone = phoneRaw ?: normalizedValue.takeIf { identityType == "phone" },
            confidence = confidence.coerceIn(0.0, 1.0),
            createdAt = createdAt,
            updatedAt = createdAt,
            archivedAt = null,
        )
    }

    private fun SourceEventParticipantEntity.toPersonIdentityEntity(): PersonIdentityEntity? {
        val ownerPersonId = personId ?: return null
        val type = identityType ?: return null
        val normalized = normalizedValue ?: return null
        val raw = when (type) {
            "email" -> emailRaw ?: normalized
            "phone" -> phoneRaw ?: normalized
            "organization" -> organizationRaw ?: normalized
            "name" -> displayNameRaw ?: normalized
            else -> normalized
        }
        return PersonIdentityEntity(
            id = PersonIdentityResolver.stableIdentityId(
                userId = userId,
                identityKey = "$type:$normalized",
            ),
            userId = userId,
            personId = ownerPersonId,
            identityKey = "$type:$normalized",
            identityType = type,
            rawValue = raw,
            displayNameHint = displayNameRaw ?: organizationRaw ?: raw,
            identityValue = raw,
            normalizedValue = normalized,
            displayName = displayNameRaw,
            sourceType = sourceType,
            sourceRef = sourceRef,
            confidence = confidence.coerceIn(0.0, 1.0),
            isPrimary = true,
            verified = resolutionStatus == "resolved",
            lastSeenAt = createdAt,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("source_event_participants")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "SourceEventParticipantRepo"
        private const val PAGE_LIMIT = 100
        private const val REFRESH_PAGE_CAP = 10
    }
}
