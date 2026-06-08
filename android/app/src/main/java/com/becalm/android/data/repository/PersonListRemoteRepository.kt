package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonEntity
import com.becalm.android.data.local.db.entity.PersonIdentityEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.PersonSummaryDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.person.PersonIdentityResolver
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import retrofit2.Response

public interface PersonListRemoteRepository {
    public suspend fun refreshPeople(
        userId: String,
        limit: Int = DEFAULT_PERSON_LIMIT,
    ): BecalmResult<RefreshStats>

    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val eventRowsFetched: Int,
        val eventRowsUpserted: Int,
        val eventRefreshFailures: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )

    public companion object {
        public const val DEFAULT_PERSON_LIMIT: Int = 50
    }
}

@Singleton
public class PersonListRemoteRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val personDetailRemoteRepository: PersonDetailRemoteRepository,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PersonListRemoteRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        personIndexDao: PersonIndexDao,
        personDetailRemoteRepository: PersonDetailRemoteRepository,
        api: RailwayApi,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        personIndexDao = personIndexDao,
        personDetailRemoteRepository = personDetailRemoteRepository,
        apiProvider = Provider { api },
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun refreshPeople(
        userId: String,
        limit: Int,
    ): BecalmResult<PersonListRemoteRepository.RefreshStats> = withContext(ioDispatcher) {
        if (userId.isBlank()) {
            return@withContext BecalmResult.Failure(BecalmError.Validation("user_id", "missing person refresh key"))
        }

        val response = try {
            api.getPersons(limit = limit.coerceIn(1, PAGE_LIMIT))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            logger.w(TAG, "person list refresh network error", error)
            return@withContext BecalmResult.Failure(BecalmError.Network(0, error.message ?: "network error"))
        } catch (error: Exception) {
            logger.e(TAG, "person list refresh failed", error)
            return@withContext BecalmResult.Failure(BecalmError.Unknown(error))
        }

        if (!response.isSuccessful) {
            return@withContext BecalmResult.Failure(response.toRefreshError())
        }
        val body = response.body()
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("empty person list response")),
            )

        val now = Clock.System.now()
        val persons = body.data.mapNotNull { it.toPersonEntity(userId = userId, now = now) }
        val identities = body.data.flatMap { it.toIdentityEntities(userId = userId, now = now) }
        if (persons.isNotEmpty()) {
            try {
                val existing = personIndexDao.findPersonsByIds(userId, persons.map(PersonEntity::id))
                personIndexDao.upsertPersons((existing + persons).preferStrongestPersonRows())
                if (identities.isNotEmpty()) {
                    personIndexDao.upsertIdentities(identities)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e(TAG, "person list materialization failed", error)
                return@withContext BecalmResult.Failure(
                    BecalmError.Io(error.message ?: "person list materialization failed"),
                )
            }
        }

        var eventRowsFetched = 0
        var eventRowsUpserted = 0
        var eventRefreshFailures = 0
        persons.take(PERSON_EVENT_RECALL_PERSON_LIMIT).forEach { person ->
            when (
                val result = personDetailRemoteRepository.refreshPersonEvents(
                    userId = userId,
                    personId = person.id,
                    limit = PERSON_EVENT_RECALL_LIMIT,
                )
            ) {
                is BecalmResult.Success -> {
                    eventRowsFetched += result.value.fetched
                    eventRowsUpserted += result.value.upserted
                }
                is BecalmResult.Failure -> {
                    eventRefreshFailures += 1
                    logger.w(TAG, "person event recall failed for restored person")
                }
            }
        }

        BecalmResult.Success(
            PersonListRemoteRepository.RefreshStats(
                fetched = body.data.size,
                upserted = persons.size,
                eventRowsFetched = eventRowsFetched,
                eventRowsUpserted = eventRowsUpserted,
                eventRefreshFailures = eventRefreshFailures,
                hasMore = body.hasMore,
                nextCursor = body.cursor.takeIf { it.isNotBlank() },
            ),
        )
    }

    private fun PersonSummaryDto.toPersonEntity(userId: String, now: Instant): PersonEntity? {
        val id = personId.clean() ?: return null
        val display = displayName.clean() ?: primaryEmail.clean() ?: primaryPhone.clean() ?: id
        val timestamp = lastContactAt ?: now
        return PersonEntity(
            id = id,
            userId = userId,
            displayName = display,
            kind = kind.clean() ?: "person",
            primaryEmail = primaryEmail.clean(),
            primaryPhone = primaryPhone.clean(),
            confidence = DEFAULT_CONFIDENCE,
            createdAt = timestamp,
            updatedAt = timestamp,
            archivedAt = null,
        )
    }

    private fun PersonSummaryDto.toIdentityEntities(userId: String, now: Instant): List<PersonIdentityEntity> {
        val id = personId.clean() ?: return emptyList()
        val display = displayName.clean() ?: primaryEmail.clean() ?: primaryPhone.clean() ?: id
        return listOfNotNull(
            primaryEmail.clean()?.let { raw ->
                PersonIdentityResolver.normalizeRelationEmailAnchor(raw)?.let { normalized ->
                    remoteSummaryIdentity(
                        userId = userId,
                        personId = id,
                        type = "email",
                        raw = raw,
                        normalized = normalized,
                        display = display,
                        now = now,
                    )
                }
            },
            primaryPhone.clean()?.let { raw ->
                PersonIdentityResolver.normalizePhoneAnchor(raw)?.let { normalized ->
                    remoteSummaryIdentity(
                        userId = userId,
                        personId = id,
                        type = "phone",
                        raw = raw,
                        normalized = normalized,
                        display = display,
                        now = now,
                    )
                }
            },
        )
    }

    private fun remoteSummaryIdentity(
        userId: String,
        personId: String,
        type: String,
        raw: String,
        normalized: String,
        display: String,
        now: Instant,
    ): PersonIdentityEntity {
        val identityKey = "$type:$normalized"
        return PersonIdentityEntity(
            id = PersonIdentityResolver.stableIdentityId(userId, identityKey),
            userId = userId,
            personId = personId,
            identityKey = identityKey,
            identityType = type,
            rawValue = raw,
            displayNameHint = display,
            identityValue = raw,
            normalizedValue = normalized,
            displayName = display,
            sourceType = SourceType.MANUAL,
            sourceRef = "person_summary:$personId",
            confidence = DEFAULT_CONFIDENCE,
            isPrimary = true,
            verified = true,
            lastSeenAt = now,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("persons")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "PersonListRemoteRepo"
        private const val PAGE_LIMIT = 100
        private const val PERSON_EVENT_RECALL_PERSON_LIMIT = 20
        private const val PERSON_EVENT_RECALL_LIMIT = 50
        private const val DEFAULT_CONFIDENCE = 1.0
    }
}
