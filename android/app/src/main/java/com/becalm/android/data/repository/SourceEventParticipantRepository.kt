package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.NoopSyncCursorStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.NoopSourceEventAnchorDao
import com.becalm.android.data.local.db.dao.PersonIndexDao
import com.becalm.android.data.local.db.dao.SourceEventAnchorDao
import com.becalm.android.data.local.db.entity.SourceEventAnchorEntity
import com.becalm.android.data.local.db.entity.SourceEventAnchorOrigin
import com.becalm.android.data.local.db.entity.SourceEventParticipantEntity
import com.becalm.android.data.local.db.entity.stableSourceEventAnchorId
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceEventParticipantDto
import com.becalm.android.domain.person.PersonIdentityResolver
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
    private val sourceEventAnchorDao: SourceEventAnchorDao = NoopSourceEventAnchorDao,
    private val apiProvider: Provider<RailwayApi>,
    private val cursorStore: SyncCursorStore,
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
        sourceEventAnchorDao = NoopSourceEventAnchorDao,
        apiProvider = Provider { api },
        cursorStore = NoopSyncCursorStore,
        logger = logger,
    )

    override suspend fun refreshSince(
        userId: String,
        sourceType: String?,
        since: Instant?,
    ): BecalmResult<SourceEventParticipantRepository.RefreshStats> = withContext(ioDispatcher) {
        val cursorKey = MirrorCursorKeys.sourceEventParticipants(userId, sourceType)
        val useStoredCursor = since == null
        var cursor: String? = if (useStoredCursor) cursorStore.observeCursor(cursorKey).first() else null
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
            val anchors = body.data.mapNotNull { it.toSourceEventAnchorEntity(userId) }
            val mergedParticipants = if (participants.isEmpty()) {
                emptyList()
            } else {
                val existing = personIndexDao.findSourceEventParticipantsForUserAndEventIds(
                    userId = userId,
                    sourceEventIds = participants.map { it.sourceEventId }.distinct(),
                )
                participants.mergeWithLocalUserDecisions(existing)
            }.coalesceSourceEventParticipantPersons()
            if (anchors.isNotEmpty()) {
                sourceEventAnchorDao.upsertAll(anchors)
            }
            if (mergedParticipants.isNotEmpty()) {
                val incomingPersons = mergedParticipants.mapNotNull { it.toPersonEntityOrNull() }
                val existingPersons = incomingPersons
                    .map { it.id }
                    .distinct()
                    .takeIf { it.isNotEmpty() }
                    ?.let { personIndexDao.findPersonsByIds(userId = userId, personIds = it) }
                    .orEmpty()
                personIndexDao.upsertPersons((existingPersons + incomingPersons).preferStrongestPersonRows())

                val incomingIdentities = mergedParticipants.flatMap { it.toPersonIdentityEntities() }
                val existingIdentities = incomingIdentities
                    .map { it.id }
                    .distinct()
                    .takeIf { it.isNotEmpty() }
                    ?.let { personIndexDao.findIdentitiesByIds(userId = userId, identityIds = it) }
                    .orEmpty()
                personIndexDao.upsertIdentities((existingIdentities + incomingIdentities).preferStrongestIdentityRows())
                personIndexDao.upsertSourceEventParticipants(mergedParticipants)
                personIndexDao.upsertDirtySources(
                    PersonIndexDirtySources.forSourceParticipants(
                        participants = mergedParticipants,
                        reason = "source_participant_refresh",
                        now = Clock.System.now(),
                    ),
                )
            }

            totalFetched += body.data.size
            totalUpserted += mergedParticipants.size
            lastHasMore = body.hasMore
            lastCursor = body.cursor
            cursor = body.cursor
            if (useStoredCursor) {
                cursorStore.setCursor(cursorKey, body.cursor)
            }
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

    private fun SourceEventParticipantDto.toSourceEventAnchorEntity(userId: String): SourceEventAnchorEntity? {
        val now = Clock.System.now()
        val normalizedSourceEventId = sourceEventId.trim().takeIf { it.isNotEmpty() }
        val normalizedSourceRef = sourceRef?.trim()?.takeIf { it.isNotEmpty() }
        val hasSourceBrief = !sourceConnectionId.isNullOrBlank() ||
            !providerEventId.isNullOrBlank() ||
            !conversationRef.isNullOrBlank() ||
            !sourceEventTitle.isNullOrBlank() ||
            !sourceEventSnippet.isNullOrBlank() ||
            sourceEventOccurredAt != null
        if (normalizedSourceEventId == null && normalizedSourceRef == null && providerEventId.isNullOrBlank()) return null
        if (!hasSourceBrief) return null
        return SourceEventAnchorEntity(
            id = stableSourceEventAnchorId(
                userId = userId,
                sourceType = sourceType,
                sourceEventId = normalizedSourceEventId,
                localRawEventId = null,
                sourceRef = normalizedSourceRef,
                providerEventId = providerEventId,
            ),
            userId = userId,
            sourceType = sourceType,
            sourceOrigin = SourceEventAnchorOrigin.BACKEND,
            sourceEventId = normalizedSourceEventId,
            localRawEventId = null,
            sourceConnectionId = sourceConnectionId,
            sourceAccountKey = null,
            providerEventId = providerEventId,
            conversationRef = conversationRef,
            sourceRef = normalizedSourceRef,
            title = sourceEventTitle,
            snippet = sourceEventSnippet,
            occurredAt = sourceEventOccurredAt,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    private fun List<SourceEventParticipantEntity>.mergeWithLocalUserDecisions(
        existing: List<SourceEventParticipantEntity>,
    ): List<SourceEventParticipantEntity> {
        if (existing.isEmpty()) return this
        val terminalById = existing
            .filter { it.resolutionStatus.isUserDecisionStatus() }
            .associateBy { it.id }
        val terminalByIdentity = existing
            .filter { it.resolutionStatus.isUserDecisionStatus() }
            .flatMap { local ->
                local.identityDecisionKeys().map { key -> key to local }
            }
            .toMap()
        return map { incoming ->
            val exact = terminalById[incoming.id]
            when {
                exact != null && incoming.resolutionStatus.isReviewableStatus() -> exact
                incoming.resolutionStatus.isReviewableStatus() -> {
                    incoming.identityDecisionKeys()
                        .firstNotNullOfOrNull { terminalByIdentity[it] }
                        ?.let { incoming.copyUserDecisionFrom(it) }
                        ?: incoming
                }
                else -> incoming
            }
        }
    }

    private fun SourceEventParticipantEntity.copyUserDecisionFrom(
        local: SourceEventParticipantEntity,
    ): SourceEventParticipantEntity =
        when (local.resolutionStatus) {
            "self_resolved" -> copy(
                personId = null,
                relationToUser = "self",
                resolutionStatus = "self_resolved",
                confidence = maxOf(confidence, local.confidence),
            )
            "resolved",
            "person_resolved",
            -> copy(
                personId = local.personId,
                relationToUser = local.relationToUser,
                identityType = identityType ?: local.identityType,
                normalizedValue = normalizedValue ?: local.normalizedValue,
                displayNameRaw = displayNameRaw ?: local.displayNameRaw,
                emailRaw = emailRaw ?: local.emailRaw,
                phoneRaw = phoneRaw ?: local.phoneRaw,
                organizationRaw = organizationRaw ?: local.organizationRaw,
                titleRaw = titleRaw ?: local.titleRaw,
                resolutionStatus = local.resolutionStatus,
                confidence = maxOf(confidence, local.confidence),
            )
            "ignored" -> copy(
                personId = null,
                relationToUser = local.relationToUser,
                resolutionStatus = "ignored",
                confidence = maxOf(confidence, local.confidence),
            )
            else -> this
        }

    private fun SourceEventParticipantEntity.identityDecisionKeys(): Set<String> =
        buildSet {
            val scope = "$userId|$sourceType|$sourceEventId"
            identityTokens().forEach { token -> add("$scope|$token") }
        }

    private fun SourceEventParticipantEntity.identityTokens(): Set<String> =
        buildSet {
            PersonIdentityResolver.normalizeRelationEmailAnchor(emailRaw)?.let { add("email:$it") }
            normalizedValue
                .takeIf { identityType == "email" }
                ?.let(PersonIdentityResolver::normalizeRelationEmailAnchor)
                ?.let { add("email:$it") }
            PersonIdentityResolver.normalizePhoneAnchor(phoneRaw)?.let { add("phone:$it") }
            normalizedValue
                .takeIf { identityType == "phone" }
                ?.let(PersonIdentityResolver::normalizePhoneAnchor)
                ?.let { add("phone:$it") }
            listOf(displayNameRaw, normalizedValue.takeIf { identityType in setOf("name", "alias") })
                .forEach { value ->
                    PersonIdentityResolver.normalizeAlias(value)?.let { add("alias:$it") }
                }
            listOf(organizationRaw, normalizedValue.takeIf { identityType == "organization" })
                .forEach { value ->
                    PersonIdentityResolver.normalizeAlias(value)?.let { add("organization:$it") }
                }
        }

    private fun String.isReviewableStatus(): Boolean =
        this == "unresolved" || this == "suggested_self"

    private fun String.isUserDecisionStatus(): Boolean =
        this == "self_resolved" || this == "resolved" || this == "person_resolved" || this == "ignored"

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
