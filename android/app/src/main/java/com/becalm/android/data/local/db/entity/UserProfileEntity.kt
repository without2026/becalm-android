package com.becalm.android.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Room mirror of the local `user_profile` bootstrap row.
 *
 * Cold sync Stage 1 requires Android to create a minimal local profile row from the
 * authenticated session plus default timezone / locale before any external mirror path runs.
 * The row is intentionally small: only fields required by current MVP screens and prompt
 * context are materialised locally.
 */
@Entity(tableName = "user_profile")
public data class UserProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "display_name_override")
    val displayNameOverride: String? = null,
    @ColumnInfo(name = "phone_e164_self")
    val phoneE164Self: String? = null,
    @ColumnInfo(name = "timezone")
    val timezone: String = "Asia/Seoul",
    @ColumnInfo(name = "preferred_locale")
    val preferredLocale: String = "ko",
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)
