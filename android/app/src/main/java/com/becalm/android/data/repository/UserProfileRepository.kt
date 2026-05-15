package com.becalm.android.data.repository

import com.becalm.android.core.di.IoDispatcher
import com.becalm.android.core.result.BecalmError
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.util.Logger
import com.becalm.android.core.util.coroutines.rethrowIfCancellation
import com.becalm.android.data.local.db.dao.UserProfileDao
import com.becalm.android.data.local.db.entity.UserProfileEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.UserProfileDto
import com.becalm.android.data.remote.dto.UserProfilePatchRequestDto
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.datetime.Clock
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

public interface UserProfileRepository {
    public fun observe(userId: String): Flow<UserProfileEntity?>
    public suspend fun find(userId: String): UserProfileEntity?
    public suspend fun refreshFromServer(userId: String): BecalmResult<UserProfileEntity>
    public suspend fun updateRemote(
        userId: String,
        displayName: String?,
        phoneE164Self: String?,
    ): BecalmResult<UserProfileEntity>
    public suspend fun bootstrapIfMissing(
        userId: String,
        timezone: String,
        preferredLocale: String,
    ): UserProfileEntity
    public suspend fun deleteAll(): Int
}

@Singleton
public class UserProfileRepositoryImpl @Inject constructor(
    private val dao: UserProfileDao,
    private val apiProvider: Provider<RailwayApi>,
    private val logger: Logger,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : UserProfileRepository {

    private val api: RailwayApi
        get() = apiProvider.get()

    override fun observe(userId: String): Flow<UserProfileEntity?> = dao.observeByUserId(userId)

    override suspend fun find(userId: String): UserProfileEntity? = dao.findByUserId(userId)

    override suspend fun refreshFromServer(userId: String): BecalmResult<UserProfileEntity> = withContext(ioDispatcher) {
        try {
            val response = api.getUserProfile()
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toUserProfileError())
            val dto = response.body()?.data
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null user profile body")),
                )
            val entity = dto.toEntity(userId = userId, existing = dao.findByUserId(userId))
            dao.upsert(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "profile refresh network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "profile refresh failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun updateRemote(
        userId: String,
        displayName: String?,
        phoneE164Self: String?,
    ): BecalmResult<UserProfileEntity> = withContext(ioDispatcher) {
        try {
            val response = api.patchUserProfile(
                UserProfilePatchRequestDto(
                    displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
                    phoneE164Self = phoneE164Self?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
            if (!response.isSuccessful) return@withContext BecalmResult.Failure(response.toUserProfileError())
            val dto = response.body()?.data
                ?: return@withContext BecalmResult.Failure(
                    BecalmError.Unknown(IllegalStateException("null user profile body")),
                )
            val entity = dto.toEntity(userId = userId, existing = dao.findByUserId(userId))
            dao.upsert(entity)
            BecalmResult.Success(entity)
        } catch (e: IOException) {
            logger.w(TAG, "profile update network failure", e)
            BecalmResult.Failure(BecalmError.Network(0, e.message ?: "network error"))
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            logger.e(TAG, "profile update failed", t)
            BecalmResult.Failure(BecalmError.Unknown(t))
        }
    }

    override suspend fun bootstrapIfMissing(
        userId: String,
        timezone: String,
        preferredLocale: String,
    ): UserProfileEntity {
        val existing = dao.findByUserId(userId)
        if (existing != null) return existing
        val now = Clock.System.now()
        val entity = UserProfileEntity(
            userId = userId,
            timezone = timezone,
            preferredLocale = preferredLocale,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity
    }

    override suspend fun deleteAll(): Int = dao.deleteAll()
}

private const val TAG = "UserProfileRepository"

private fun UserProfileDto.toEntity(
    userId: String,
    existing: UserProfileEntity?,
): UserProfileEntity {
    val now = Clock.System.now()
    return UserProfileEntity(
        userId = this.userId.ifBlank { userId },
        displayNameOverride = displayNameOverride ?: displayName,
        phoneE164Self = phoneE164Self,
        timezone = timezone,
        preferredLocale = preferredLocale,
        createdAt = createdAt ?: existing?.createdAt ?: now,
        updatedAt = updatedAt ?: now,
    )
}

private fun <T> Response<T>.toUserProfileError(): BecalmError =
    when (code()) {
        401 -> BecalmError.Unauthorized
        404 -> BecalmError.NotFound("user_profile")
        422 -> BecalmError.Validation(field = null, message = "user_profile")
        in 500..599 -> BecalmError.ServerError(code(), errorBody()?.string())
        else -> BecalmError.Network(code(), message())
    }
