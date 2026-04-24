package com.becalm.android.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.becalm.android.data.local.db.entity.UserProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
public interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun upsert(entity: UserProfileEntity): Long

    @Query("SELECT * FROM user_profile WHERE user_id = :userId LIMIT 1")
    public fun observeByUserId(userId: String): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile WHERE user_id = :userId LIMIT 1")
    public suspend fun findByUserId(userId: String): UserProfileEntity?

    @Query("DELETE FROM user_profile")
    public suspend fun deleteAll(): Int
}
