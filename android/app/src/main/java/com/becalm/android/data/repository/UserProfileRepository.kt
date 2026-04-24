package com.becalm.android.data.repository

import com.becalm.android.data.local.db.dao.UserProfileDao
import com.becalm.android.data.local.db.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

public interface UserProfileRepository {
    public fun observe(userId: String): Flow<UserProfileEntity?>
    public suspend fun find(userId: String): UserProfileEntity?
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
) : UserProfileRepository {

    override fun observe(userId: String): Flow<UserProfileEntity?> = dao.observeByUserId(userId)

    override suspend fun find(userId: String): UserProfileEntity? = dao.findByUserId(userId)

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
