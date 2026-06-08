package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.entity.PersonInteractionEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.PersonEventDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.person.SourceInteractionKind
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

public interface PersonDetailRemoteRepository {
    public suspend fun refreshPersonEvents(
        userId: String,
        personId: String,
        limit: Int = 100,
    ): BecalmResult<RefreshStats>

    public data class RefreshStats(
        val fetched: Int,
        val upserted: Int,
        val hasMore: Boolean,
        val nextCursor: String?,
    )
}

@Singleton
public class PersonDetailRemoteRepositoryImpl @Inject constructor(
    private val personIndexDao: PersonIndexDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PersonDetailRemoteRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    public constructor(
        personIndexDao: PersonIndexDao,
        api: RailwayApi,
        logger: Logger,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        personIndexDao = personIndexDao,
        apiProvider = Provider { api },
        logger = logger,
        ioDispatcher = ioDispatcher,
    )

    override suspend fun refreshPersonEvents(
        userId: String,
        personId: String,
        limit: Int,
    ): BecalmResult<PersonDetailRemoteRepository.RefreshStats> = withContext(ioDispatcher) {
        if (userId.isBlank() || personId.isBlank()) {
            return@withContext BecalmResult.Failure(BecalmError.Validation("person_id", "missing person detail refresh key"))
        }

        val response = try {
            api.getPersonEvents(personId = personId, limit = limit.coerceIn(1, PAGE_LIMIT))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            logger.w(TAG, "person events refresh network error", error)
            return@withContext BecalmResult.Failure(BecalmError.Network(0, error.message ?: "network error"))
        } catch (error: Exception) {
            logger.e(TAG, "person events refresh failed", error)
            return@withContext BecalmResult.Failure(BecalmError.Unknown(error))
        }

        if (!response.isSuccessful) {
            return@withContext BecalmResult.Failure(response.toRefreshError())
        }
        val body = response.body()
            ?: return@withContext BecalmResult.Failure(
                BecalmError.Unknown(IllegalStateException("empty person events response")),
            )
        val interactions = body.data.mapNotNull { row ->
            row.toInteractionEntity(userId = userId, fallbackPersonId = personId)
        }
        if (interactions.isNotEmpty()) {
            try {
                personIndexDao.upsertInteractions(interactions)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.e(TAG, "person events materialization failed", error)
                return@withContext BecalmResult.Failure(BecalmError.Io(error.message ?: "person events materialization failed"))
            }
        }

        BecalmResult.Success(
            PersonDetailRemoteRepository.RefreshStats(
                fetched = body.data.size,
                upserted = interactions.size,
                hasMore = body.hasMore,
                nextCursor = body.cursor,
            ),
        )
    }

    private fun PersonEventDto.toInteractionEntity(
        userId: String,
        fallbackPersonId: String,
    ): PersonInteractionEntity? {
        val sourceTypeValue = sourceType.clean() ?: return null
        val resolvedPersonId = personId.clean() ?: fallbackPersonId
        val kind = normalizedInteractionKind(sourceTypeValue)
        if (kind == SourceInteractionKind.COMMITMENT) return null

        val sourceEvent = sourceEventId.clean()
        val commitment = commitmentId.clean()
        val remoteInteractionKey = interactionKey.clean()
        val sourceRefValue = sourceRef.clean()
            ?: providerEventId.clean()
            ?: remoteInteractionKey.extractSourceRefFromInteractionKey(
                userId = userId,
                personId = resolvedPersonId,
                commitmentId = commitment,
            )
            ?: sourceEvent?.let { "source_event:$it" }
            ?: commitment?.let { "commitment:$it" }
            ?: "person_event:${id.clean() ?: return null}"
        val materializedId = stableInteractionId(
            userId = userId,
            personId = resolvedPersonId,
            sourceType = sourceTypeValue,
            sourceRef = sourceRefValue,
            kind = kind,
        )
        val key = remoteInteractionKey
            ?: "$userId:$resolvedPersonId:${sourceEvent ?: sourceRefValue}:${commitment.orEmpty()}:$kind"
        return PersonInteractionEntity(
            id = materializedId,
            userId = userId,
            personId = resolvedPersonId,
            sourceType = sourceTypeValue,
            sourceRef = sourceRefValue,
            interactionKind = kind,
            sourceEventId = sourceEvent,
            commitmentId = commitment,
            interactionKey = key,
            interactionType = interactionType.clean() ?: kind,
            role = role.clean() ?: DEFAULT_PERSON_ROLE,
            direction = direction.clean(),
            status = status.clean() ?: manualOriginStatus(sourceRefValue, key),
            occurredAt = occurredAt,
            title = title.clean(),
            snippet = snippet.clean(),
            confidence = (confidence ?: DEFAULT_CONFIDENCE).coerceIn(0.0, 1.0),
            createdAt = createdAt ?: occurredAt,
        )
    }

    private fun PersonEventDto.normalizedInteractionKind(sourceTypeValue: String): String {
        val raw = interactionType.clean() ?: eventKind.clean()
        return when (raw) {
            null -> SourceInteractionKind.forSourceType(sourceTypeValue)
            "mail" -> "email"
            "screenshot" -> SourceInteractionKind.forSourceType(sourceTypeValue)
            else -> raw
        }
    }

    private fun String?.extractSourceRefFromInteractionKey(
        userId: String,
        personId: String,
        commitmentId: String?,
    ): String? {
        val key = clean() ?: return null
        val prefix = "$userId:$personId:"
        if (!key.startsWith(prefix)) return null
        val tail = key.removePrefix(prefix)
        val beforeCommitment = commitmentId?.let { id ->
            tail.substringBeforeLast(":$id:", missingDelimiterValue = tail)
        }?.takeIf { it != tail }
        if (beforeCommitment != null) return beforeCommitment.clean()
        val beforeManualOrigin = tail.substringBefore("::", missingDelimiterValue = "")
            .clean()
        if (beforeManualOrigin != null) return beforeManualOrigin
        return tail.substringBeforeLast(':', missingDelimiterValue = tail).clean()
    }

    private fun manualOriginStatus(sourceRef: String, interactionKey: String): String? {
        if (!sourceRef.startsWith("manual_memory:")) return null
        return interactionKey.substringAfter("::manual_", missingDelimiterValue = "")
            .clean()
    }

    private fun stableInteractionId(
        userId: String,
        personId: String,
        sourceType: String,
        sourceRef: String,
        kind: String,
    ): String {
        if (sourceType == SourceType.MANUAL && sourceRef.startsWith("manual_memory:")) {
            val clientMemoryId = sourceRef.removePrefix("manual_memory:")
            return stableId("first-memory:source", userId, clientMemoryId, personId)
        }
        return stableId("interaction", userId, sourceType, sourceRef, personId, kind)
    }

    private fun stableId(vararg parts: String): String =
        UUID.nameUUIDFromBytes(parts.joinToString(":").toByteArray(StandardCharsets.UTF_8)).toString()

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun <T> Response<T>.toRefreshError(): BecalmError = when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("person_events")
        422 -> BecalmError.Validation(null, message())
        429 -> BecalmError.RateLimited(headers()["Retry-After"]?.toLongOrNull())
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }

    private companion object {
        private const val TAG = "PersonDetailRemoteRepo"
        private const val PAGE_LIMIT = 100
        private const val DEFAULT_PERSON_ROLE = "counterparty"
        private const val DEFAULT_CONFIDENCE = 1.0
    }
}
