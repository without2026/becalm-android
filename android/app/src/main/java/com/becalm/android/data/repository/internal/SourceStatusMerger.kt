package com.becalm.android.data.repository.internal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.becalm.android.core.util.Logger
import com.becalm.android.data.remote.dto.SourceStatusItemDto
import com.becalm.android.data.remote.dto.SourceType

// ─── Wire-state constants (api-contract.yml § GET /v1/source_status) ─────────

internal const val WIRE_STATE_IDLE = "idle"
internal const val WIRE_STATE_SYNCING = "syncing"
internal const val WIRE_STATE_SYNCED = "synced"
internal const val WIRE_STATE_ERROR = "error"

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
            when (item.state) {
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
                }
                else -> {
                    logger.w(TAG, "refreshFromServer unknown state='${item.state}' for source='${item.sourceType}'")
                }
            }
        }
    }
}
