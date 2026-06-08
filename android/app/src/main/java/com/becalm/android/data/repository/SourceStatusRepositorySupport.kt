package com.becalm.android.data.repository

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

internal object SourceStatusPrefsKeys {
    fun lastSyncedAt(source: String): Preferences.Key<Long> =
        longPreferencesKey("source_status.$source.last_synced_at")

    fun lastError(source: String): Preferences.Key<String> =
        stringPreferencesKey("source_status.$source.last_error")

    fun inProgress(source: String): Preferences.Key<Boolean> =
        booleanPreferencesKey("source_status.$source.in_progress")

    fun inProgressStartedAt(source: String): Preferences.Key<Long> =
        longPreferencesKey("source_status.$source.in_progress_started_at")

    fun connectionState(source: String): Preferences.Key<String> =
        stringPreferencesKey("source_status.$source.connection_state")
}

internal const val SERVER_CONNECTION_STATE_CONNECTED = "connected"
internal const val SERVER_CONNECTION_STATE_NEVER_CONNECTED = "never_connected"
internal const val SERVER_CONNECTION_STATE_NEEDS_REAUTH = "needs_reauth"
internal const val SERVER_CONNECTION_STATE_CLIENT_MANAGED = "client_managed"
internal const val SOURCE_STATUS_IN_PROGRESS_TTL_MILLIS = 15 * 60 * 1000L

internal fun isAuthoritativeServerConnectionState(state: String?): Boolean =
    state == SERVER_CONNECTION_STATE_CONNECTED ||
        state == SERVER_CONNECTION_STATE_NEVER_CONNECTED ||
        state == SERVER_CONNECTION_STATE_NEEDS_REAUTH

internal object SourceStatusDeriver {
    fun derive(
        sourceType: String,
        lastSyncedAtMs: Long?,
        lastError: String?,
        isInProgress: Boolean,
        inProgressStartedAtMs: Long? = null,
        serverConnectionState: String? = null,
        nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    ): SourceStatus {
        val lastSyncedAt = lastSyncedAtMs?.let(Instant::fromEpochMilliseconds)
        val isFreshInProgress = isInProgress && inProgressStartedAtMs.isFreshInProgress(nowMs)
        val status = when {
            serverConnectionState == SERVER_CONNECTION_STATE_NEEDS_REAUTH -> SourceConnectionStatus.ERROR
            isFreshInProgress -> SourceConnectionStatus.SYNCING
            !lastError.isNullOrBlank() -> SourceConnectionStatus.ERROR
            serverConnectionState == SERVER_CONNECTION_STATE_CONNECTED -> SourceConnectionStatus.CONNECTED
            lastSyncedAt == null -> SourceConnectionStatus.NEVER_CONNECTED
            else -> SourceConnectionStatus.CONNECTED
        }
        return SourceStatus(
            sourceType = sourceType,
            status = status,
            lastSyncedAt = lastSyncedAt,
            errorMessage = if (status == SourceConnectionStatus.ERROR) {
                lastError?.takeIf { it.isNotBlank() }
                    ?: "needs_reauth".takeIf { serverConnectionState == SERVER_CONNECTION_STATE_NEEDS_REAUTH }
            } else {
                null
            },
        )
    }

    private fun Long?.isFreshInProgress(nowMs: Long): Boolean =
        this != null && nowMs - this in 0..SOURCE_STATUS_IN_PROGRESS_TTL_MILLIS
}
