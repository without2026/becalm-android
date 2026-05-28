package com.becalm.android.data.repository.internal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceStatusItemDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SERVER_CONNECTION_STATE_CLIENT_MANAGED
import com.becalm.android.data.repository.SERVER_CONNECTION_STATE_CONNECTED
import com.becalm.android.data.repository.SERVER_CONNECTION_STATE_NEEDS_REAUTH
import com.becalm.android.data.repository.SERVER_CONNECTION_STATE_NEVER_CONNECTED

// ─── Wire-state constants (api-contract.yml § GET /v1/source_status) ─────────

internal const val WIRE_STATE_IDLE = "idle"
internal const val WIRE_STATE_SYNCING = "syncing"
internal const val WIRE_STATE_SYNCED = "synced"
internal const val WIRE_STATE_ERROR = "error"

private val KNOWN_WIRE_STATES = setOf(
    WIRE_STATE_IDLE,
    WIRE_STATE_SYNCING,
    WIRE_STATE_SYNCED,
    WIRE_STATE_ERROR,
)

private val KNOWN_CONNECTION_STATES = setOf(
    SERVER_CONNECTION_STATE_CONNECTED,
    SERVER_CONNECTION_STATE_NEVER_CONNECTED,
    SERVER_CONNECTION_STATE_NEEDS_REAUTH,
    SERVER_CONNECTION_STATE_CLIENT_MANAGED,
)

private const val TAG = "SourceStatusRepository"

/**
 * Merges [items] from `GET /v1/source_status` into the DataStore cache.
 *
 * Each item is written under the same keys used by the offline-derivation path so that
 * [observeAll] emits the server-authoritative value on the next tick. Unknown source types
 * (e.g. a future server-added type) are skipped with a WARN log rather than stored.
 *
 * Callers provide the preference-key builders so the repository's private `Keys` companion
 * remains the single source of truth for key formats — the merger stays pure over the
 * caller-owned schema.
 */
internal suspend fun mergeServerState(
    userPrefs: DataStore<Preferences>,
    items: List<SourceStatusItemDto>,
    logger: Logger,
    lastSyncedAt: (String) -> Preferences.Key<Long>,
    lastError: (String) -> Preferences.Key<String>,
    inProgress: (String) -> Preferences.Key<Boolean>,
    connectionState: (String) -> Preferences.Key<String>,
) {
    userPrefs.edit { prefs ->
        for (item in items) {
            // Schema validation — the server is allowed to emit any data-model.yml enum
            // value, so we check against the full ALL set (includes VOICE and
            // CALL_RECORDING) rather than the product-UI PRODUCT_SOURCES subset.
            if (item.sourceType !in SourceType.ALL) {
                logger.w(TAG, "refreshFromServer skipped unknown source_type='${item.sourceType}'")
                continue
            }
            val syncState = item.syncState ?: item.state
            if (syncState !in KNOWN_WIRE_STATES) {
                logger.w(TAG, "refreshFromServer unknown sync_state='$syncState' for source='${item.sourceType}'")
                continue
            }
            val serverConnectionState = item.connectionState
            if (serverConnectionState != null && serverConnectionState !in KNOWN_CONNECTION_STATES) {
                logger.w(
                    TAG,
                    "refreshFromServer unknown connection_state='$serverConnectionState' for source='${item.sourceType}'",
                )
                continue
            }
            if (serverConnectionState != null) {
                prefs[connectionState(item.sourceType)] = serverConnectionState
            } else if (syncState == WIRE_STATE_IDLE && item.lastSyncAt == null) {
                prefs.remove(connectionState(item.sourceType))
            }
            if (serverConnectionState == SERVER_CONNECTION_STATE_NEVER_CONNECTED) {
                prefs.remove(inProgress(item.sourceType))
                prefs.remove(lastError(item.sourceType))
                prefs.remove(lastSyncedAt(item.sourceType))
                continue
            }
            when (syncState) {
                WIRE_STATE_SYNCING -> {
                    prefs[inProgress(item.sourceType)] = true
                    prefs.remove(lastError(item.sourceType))
                }
                WIRE_STATE_SYNCED -> {
                    prefs.remove(inProgress(item.sourceType))
                    prefs.remove(lastError(item.sourceType))
                    val at = item.lastSyncAt
                    if (at != null) {
                        prefs[lastSyncedAt(item.sourceType)] = at.toEpochMilliseconds()
                    }
                }
                WIRE_STATE_ERROR -> {
                    prefs.remove(inProgress(item.sourceType))
                    prefs[lastError(item.sourceType)] = item.lastError ?: "error"
                    val at = item.lastSyncAt
                    if (at != null) {
                        prefs[lastSyncedAt(item.sourceType)] = at.toEpochMilliseconds()
                    }
                }
                WIRE_STATE_IDLE -> {
                    prefs.remove(inProgress(item.sourceType))
                    prefs.remove(lastError(item.sourceType))
                    val at = item.lastSyncAt
                    if (at != null) {
                        prefs[lastSyncedAt(item.sourceType)] = at.toEpochMilliseconds()
                    } else {
                        prefs.remove(lastSyncedAt(item.sourceType))
                    }
                }
                else -> {
                    logger.w(TAG, "refreshFromServer unknown state='${item.state}' for source='${item.sourceType}'")
                }
            }
        }
    }
}
