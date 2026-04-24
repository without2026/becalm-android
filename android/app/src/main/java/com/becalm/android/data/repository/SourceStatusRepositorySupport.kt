package com.becalm.android.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.datetime.Instant

internal object SourceStatusPrefsKeys {
    fun lastSyncedAt(source: String): Preferences.Key<Long> =
        longPreferencesKey("source_status.$source.last_synced_at")

    fun lastError(source: String): Preferences.Key<String> =
        stringPreferencesKey("source_status.$source.last_error")

    fun inProgress(source: String): Preferences.Key<Boolean> =
        booleanPreferencesKey("source_status.$source.in_progress")
}

internal object SourceStatusDeriver {
    fun derive(
        sourceType: String,
        lastSyncedAtMs: Long?,
        lastError: String?,
        isInProgress: Boolean,
    ): SourceStatus {
        val lastSyncedAt = lastSyncedAtMs?.let(Instant::fromEpochMilliseconds)
        val status = when {
            isInProgress -> SourceConnectionStatus.SYNCING
            lastSyncedAt == null && lastError.isNullOrBlank() -> SourceConnectionStatus.NEVER_CONNECTED
            !lastError.isNullOrBlank() -> SourceConnectionStatus.ERROR
            else -> SourceConnectionStatus.CONNECTED
        }
        return SourceStatus(
            sourceType = sourceType,
            status = status,
            lastSyncedAt = lastSyncedAt,
            errorMessage = lastError?.takeIf { it.isNotBlank() },
        )
    }
}
