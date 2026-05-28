package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.datastore.NoopSyncCursorStore
import com.becalm.android.data.local.datastore.SyncCursorStore
import com.becalm.android.data.local.db.dao.SelfIdentityAnchorDao
import com.becalm.android.data.local.db.dao.SourceConnectionDao
import com.becalm.android.data.local.db.dao.UserProfileDao
import com.becalm.android.data.local.db.entity.SelfIdentityAnchorEntity
import com.becalm.android.data.local.db.entity.SourceConnectionEntity
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.EmailConnectionRecommendationDto
import com.becalm.android.data.remote.dto.OnboardingSelfIdentityCommitRequestDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorCreateRequestDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorDto
import com.becalm.android.data.remote.dto.SelfIdentityAnchorPatchRequestDto
import com.becalm.android.data.remote.dto.SourceConnectionDto
import com.becalm.android.data.remote.dto.SourceConnectionPatchRequestDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.remote.dto.UserProfileDto
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import retrofit2.Response

public interface SelfIdentityRepository {
    public fun observeAll(userId: String): Flow<List<SelfIdentityAnchorEntity>>
    public fun observeActive(userId: String): Flow<List<SelfIdentityAnchorEntity>>
    public suspend fun refresh(userId: String): BecalmResult<List<SelfIdentityAnchorEntity>>
    public suspend fun commitOnboardingSelfIdentity(
        userId: String,
        displayName: String,
        displayNameSource: String,
        displayNameReadOnly: Boolean,
        email: String?,
        emailReadOnly: Boolean,
        phoneE164: String?,
        phoneReadOnly: Boolean,
        phoneVerified: Boolean,
        alias: String?,
        authProvider: String,
    ): BecalmResult<OnboardingSelfIdentityCommit>
    public suspend fun createAnchor(
        userId: String,
        anchorType: String,
        value: String,
        displayValue: String? = null,
        source: String = "user_profile",
        scope: String = "global",
        trust: String = "user_confirmed",
        status: String = "active",
        sourceConnectionId: String? = null,
        sourceEventId: String? = null,
    ): BecalmResult<SelfIdentityAnchorEntity>
    public suspend fun upsertLocalAnchor(
        userId: String,
        anchorType: String,
        value: String,
        displayValue: String? = null,
        source: String = "user_profile",
        scope: String = "global",
        trust: String = "user_confirmed",
        status: String = "active",
        sourceConnectionId: String? = null,
        sourceEventId: String? = null,
    ): SelfIdentityAnchorEntity
    public suspend fun updateAnchor(
        id: String,
        displayValue: String? = null,
        trust: String? = null,
        status: String? = null,
    ): BecalmResult<SelfIdentityAnchorEntity>
}

public data class OnboardingSelfIdentityCommit(
    val profile: UserProfileEntity,
    val anchors: List<SelfIdentityAnchorEntity>,
    val emailConnectionRecommendation: EmailConnectionRecommendationDto?,
)

public interface SourceConnectionRepository {
    public fun observeAll(userId: String): Flow<List<SourceConnectionEntity>>
    public suspend fun refresh(userId: String): BecalmResult<List<SourceConnectionEntity>>
    public suspend fun setOwnership(
        userId: String,
        connectionId: String,
        ownership: String,
        linkedSelfAnchorId: String? = null,
    ): BecalmResult<SourceConnectionEntity>
    public suspend fun disconnectConnection(
        userId: String,
        connectionId: String,
    ): BecalmResult<SourceConnectionEntity>
    public suspend fun deleteConnection(
        userId: String,
        connectionId: String,
    ): BecalmResult<SourceConnectionEntity>
}

@Singleton
public class SelfIdentityRepositoryImpl @Inject constructor(
    private val dao: SelfIdentityAnchorDao,
    private val userProfileDao: UserProfileDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SelfIdentityRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    override fun observeAll(userId: String): Flow<List<SelfIdentityAnchorEntity>> = dao.observeAll(userId)

    override fun observeActive(userId: String): Flow<List<SelfIdentityAnchorEntity>> = dao.observeActive(userId)

    override suspend fun refresh(userId: String): BecalmResult<List<SelfIdentityAnchorEntity>> = withContext(ioDispatcher) {
        try {
            val response = api.getSelfIdentityAnchors()
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("self_identity_anchors"))
            val body = response.body()
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null body for self_identity_anchors")),
                )
            val entities = body.data.map { it.toEntity(userId) }
            if (entities.isEmpty()) {
                dao.deleteAllForUser(userId)
            } else {
                dao.insertAll(entities)
                dao.deleteMissingForUser(userId, entities.map { it.id })
            }
            BecalmResult.Success(entities)
        } catch (e: IOException) {
            logger.w(TAG, "self identity refresh network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "self identity refresh failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun commitOnboardingSelfIdentity(
        userId: String,
        displayName: String,
        displayNameSource: String,
        displayNameReadOnly: Boolean,
        email: String?,
        emailReadOnly: Boolean,
        phoneE164: String?,
        phoneReadOnly: Boolean,
        phoneVerified: Boolean,
        alias: String?,
        authProvider: String,
    ): BecalmResult<OnboardingSelfIdentityCommit> = withContext(ioDispatcher) {
        val request = OnboardingSelfIdentityCommitRequestDto(
            displayName = displayName.trim(),
            displayNameSource = displayNameSource.trim().takeIf { it.isNotEmpty() },
            displayNameReadOnly = displayNameReadOnly,
            email = email?.trim()?.takeIf { it.isNotEmpty() },
            emailReadOnly = emailReadOnly,
            phoneE164 = phoneE164?.trim()?.takeIf { it.isNotEmpty() },
            phoneReadOnly = phoneReadOnly,
            phoneVerified = phoneVerified,
            alias = alias?.trim()?.takeIf { it.isNotEmpty() },
            authProvider = authProvider.trim().takeIf { it.isNotEmpty() },
        )
        try {
            val response = api.commitOnboardingSelfIdentity(request)
            if (!response.isSuccessful) {
                return@withContext BecalmResult.Failure(response.toIdentityError("onboarding_self_identity"))
            }
            val body = response.body()?.data
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null body for onboarding_self_identity")),
                )
            val profile = body.profile.toProfileEntity(
                fallbackUserId = userId,
                existing = userProfileDao.findByUserId(userId),
            )
            val anchors = body.anchors.map { it.toEntity(userId) }
            userProfileDao.upsert(profile)
            if (anchors.isNotEmpty()) {
                dao.insertAll(anchors)
            }
            BecalmResult.Success(
                OnboardingSelfIdentityCommit(
                    profile = profile,
                    anchors = anchors,
                    emailConnectionRecommendation = body.emailConnectionRecommendation,
                ),
            )
        } catch (e: IOException) {
            logger.w(TAG, "onboarding self identity commit network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "onboarding self identity commit failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun createAnchor(
        userId: String,
        anchorType: String,
        value: String,
        displayValue: String?,
        source: String,
        scope: String,
        trust: String,
        status: String,
        sourceConnectionId: String?,
        sourceEventId: String?,
    ): BecalmResult<SelfIdentityAnchorEntity> = withContext(ioDispatcher) {
        val request = SelfIdentityAnchorCreateRequestDto(
            anchorType = anchorType,
            value = value,
            displayValue = displayValue,
            source = source,
            scope = scope,
            trust = trust,
            status = status,
            sourceConnectionId = sourceConnectionId,
            sourceEventId = sourceEventId,
        )
        try {
            val response = api.createSelfIdentityAnchor(request)
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("self_identity_anchor"))
            val entity = response.body()?.data?.toEntity(userId)
                ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("self_identity_anchor"))
            dao.upsert(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "self identity create network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "self identity create failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun upsertLocalAnchor(
        userId: String,
        anchorType: String,
        value: String,
        displayValue: String?,
        source: String,
        scope: String,
        trust: String,
        status: String,
        sourceConnectionId: String?,
        sourceEventId: String?,
    ): SelfIdentityAnchorEntity = withContext(ioDispatcher) {
        val normalized = value.trim()
        val entity = SelfIdentityAnchorEntity(
            id = localSelfAnchorId(userId, anchorType, normalized, scope, sourceConnectionId, sourceEventId),
            userId = userId,
            anchorType = anchorType,
            normalizedValue = normalized,
            displayValue = displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: normalized,
            source = source,
            scope = scope,
            sourceConnectionId = sourceConnectionId,
            sourceEventId = sourceEventId,
            trust = trust,
            status = status,
            createdAt = kotlinx.datetime.Clock.System.now(),
            updatedAt = kotlinx.datetime.Clock.System.now(),
        )
        dao.upsert(entity)
        entity
    }

    override suspend fun updateAnchor(
        id: String,
        displayValue: String?,
        trust: String?,
        status: String?,
    ): BecalmResult<SelfIdentityAnchorEntity> = withContext(ioDispatcher) {
        try {
            val response = api.patchSelfIdentityAnchor(
                id = id,
                request = SelfIdentityAnchorPatchRequestDto(displayValue = displayValue, trust = trust, status = status),
            )
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("self_identity_anchor"))
            val fallbackUserId = dao.findById(id)?.userId.orEmpty()
            val entity = response.body()?.data?.toEntity(fallbackUserId)
                ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("self_identity_anchor"))
            dao.upsert(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "self identity update network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "self identity update failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }
}

@Singleton
public class SourceConnectionRepositoryImpl @Inject constructor(
    private val dao: SourceConnectionDao,
    private val apiProvider: Provider<RailwayApi>,
    private val syncCursorStore: SyncCursorStore = NoopSyncCursorStore,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SourceConnectionRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    override fun observeAll(userId: String): Flow<List<SourceConnectionEntity>> = dao.observeAll(userId)

    override suspend fun refresh(userId: String): BecalmResult<List<SourceConnectionEntity>> = withContext(ioDispatcher) {
        try {
            val response = api.getSourceConnections()
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("source_connections"))
            val body = response.body()
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null body for source_connections")),
                )
            val entities = body.data.map { it.toEntity(userId) }
            val previousById = entities.associate { entity ->
                entity.id to dao.findById(entity.id)
            }
            if (entities.isEmpty()) {
                dao.deleteAllForUser(userId)
            } else {
                dao.insertAll(entities)
                dao.deleteMissingForUser(userId, entities.map { it.id })
            }
            entities
                .filter { SourceMirrorCursorReset.shouldResetAfterRefresh(previousById[it.id], it) }
                .forEach { SourceMirrorCursorReset.clearForConnection(syncCursorStore, it) }
            BecalmResult.Success(entities)
        } catch (e: IOException) {
            logger.w(TAG, "source connections refresh network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "source connections refresh failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun setOwnership(
        userId: String,
        connectionId: String,
        ownership: String,
        linkedSelfAnchorId: String?,
    ): BecalmResult<SourceConnectionEntity> = withContext(ioDispatcher) {
        try {
            val response = api.patchSourceConnection(
                id = connectionId,
                request = SourceConnectionPatchRequestDto(
                    ownership = ownership,
                    linkedSelfAnchorId = linkedSelfAnchorId,
                ),
            )
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("source_connection"))
            val entity = response.body()?.data?.toEntity(userId)
                ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("source_connection"))
            dao.upsert(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "source connection ownership update network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "source connection ownership update failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun disconnectConnection(
        userId: String,
        connectionId: String,
    ): BecalmResult<SourceConnectionEntity> = withContext(ioDispatcher) {
        try {
            val response = api.disconnectSourceConnection(connectionId)
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("source_connection"))
            val entity = response.body()?.data?.toEntity(userId)
                ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("source_connection"))
            dao.upsert(entity)
            clearMirrorCursorsFor(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "source connection disconnect network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "source connection disconnect failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun deleteConnection(
        userId: String,
        connectionId: String,
    ): BecalmResult<SourceConnectionEntity> = withContext(ioDispatcher) {
        try {
            val response = api.deleteSourceConnection(connectionId)
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toIdentityError("source_connection"))
            val entity = response.body()?.data?.toEntity(userId)
                ?: return@withContext BecalmResult.Failure(BecalmError.NotFound("source_connection"))
            dao.deleteById(entity.id)
            clearMirrorCursorsFor(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "source connection delete network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "source connection delete failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    private suspend fun clearMirrorCursorsFor(connection: SourceConnectionEntity) {
        SourceMirrorCursorReset.clearForConnection(syncCursorStore, connection)
    }
}

private const val TAG = "IdentityRepository"

private fun localSelfAnchorId(
    userId: String,
    anchorType: String,
    normalizedValue: String,
    scope: String,
    sourceConnectionId: String?,
    sourceEventId: String?,
): String {
    val key = listOf(userId, anchorType, normalizedValue, scope, sourceConnectionId.orEmpty(), sourceEventId.orEmpty())
        .joinToString("|")
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
    return "local-self-" + digest.joinToString("") { "%02x".format(it) }.take(24)
}

private fun SelfIdentityAnchorDto.toEntity(fallbackUserId: String): SelfIdentityAnchorEntity =
    SelfIdentityAnchorEntity(
        id = id,
        userId = userId.ifBlank { fallbackUserId },
        anchorType = anchorType,
        normalizedValue = normalizedValue,
        displayValue = displayValue,
        source = source,
        scope = scope,
        sourceConnectionId = sourceConnectionId,
        sourceEventId = sourceEventId,
        trust = trust,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun UserProfileDto.toProfileEntity(
    fallbackUserId: String,
    existing: UserProfileEntity?,
): UserProfileEntity {
    val now = kotlinx.datetime.Clock.System.now()
    return UserProfileEntity(
        userId = userId.ifBlank { fallbackUserId },
        displayNameOverride = displayNameOverride ?: displayName,
        phoneE164Self = phoneE164Self,
        timezone = timezone,
        preferredLocale = preferredLocale,
        displayNameSource = displayNameSource ?: existing?.displayNameSource,
        onboardingCompletedAt = onboardingCompletedAt ?: existing?.onboardingCompletedAt,
        createdAt = createdAt ?: existing?.createdAt ?: now,
        updatedAt = updatedAt ?: now,
    )
}

private fun SourceConnectionDto.toEntity(fallbackUserId: String): SourceConnectionEntity =
    SourceConnectionEntity(
        id = id,
        userId = userId?.takeIf { it.isNotBlank() } ?: fallbackUserId,
        provider = provider,
        capability = capability,
        accountIdentifier = accountIdentifier,
        accountDisplayName = accountDisplayName,
        ownership = ownership,
        status = status,
        linkedSelfAnchorId = linkedSelfAnchorId,
        lastSyncAt = lastSyncAt,
        lastError = lastError,
    )

private fun <T> Response<T>.toIdentityError(resource: String): BecalmError =
    when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound(resource)
        422 -> BecalmError.Validation(field = null, message = resource)
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }
