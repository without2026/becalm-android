package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import kotlinx.datetime.Instant

/**
 * Aggregate sync-state banner shown above the timeline (TDY-008).
 *
 * Four terminal states, computed from the six-source strip (voice excluded per Q7):
 * - [Idle]            — every source is idle/never-connected.
 * - [Syncing]         — at least one source is actively syncing; carries `count/total`.
 * - [Synced]          — every source completed successfully; carries the min(last_sync_at).
 * - [PartialFailure]  — at least one source is in error (amber warning copy).
 */
public sealed interface OverallSyncState {
    public data object Idle : OverallSyncState
    public data class Syncing(val count: Int, val total: Int) : OverallSyncState
    public data class Synced(val at: Instant) : OverallSyncState
    public data object PartialFailure : OverallSyncState
}

/**
 * Computes the TDY-008 aggregate state from the per-source strip.
 *
 * Only the six external product sources count toward the banner
 * ([SourceType.PRODUCT_SOURCES]). [SourceStatusRepository.observeAll] already filters
 * the input to this set, so the belt-and-braces `.filter` below is defensive: if a
 * caller ever passes a non-product row (e.g. VOICE or CALL_RECORDING) directly, it
 * still must not skew the aggregate.
 *
 * Priority order:
 * 1. Any source in ERROR → [OverallSyncState.PartialFailure].
 * 2. Any source in SYNCING → [OverallSyncState.Syncing(count, 6)].
 * 3. Every source in CONNECTED → [OverallSyncState.Synced(min(lastSyncedAt))].
 *    Falls back to [OverallSyncState.Idle] when the earliest timestamp is null.
 * 4. Otherwise → [OverallSyncState.Idle].
 */
internal fun deriveOverallState(sources: List<SourceStatus>): OverallSyncState {
    // Defensive filter — expected input is already PRODUCT_SOURCES only, but guard
    // against future callers forwarding the schema-wide set.
    val chipSources = sources.filter { it.sourceType in SourceType.PRODUCT_SOURCES }
    val total = chipSources.size
    val errorCount = chipSources.count { it.status == SourceConnectionStatus.ERROR }
    val syncingCount = chipSources.count { it.status == SourceConnectionStatus.SYNCING }
    val connectedCount = chipSources.count { it.status == SourceConnectionStatus.CONNECTED }

    return when {
        errorCount > 0 -> OverallSyncState.PartialFailure
        syncingCount > 0 -> OverallSyncState.Syncing(syncingCount, total)
        connectedCount == total && total > 0 -> {
            val earliest = chipSources.mapNotNull { it.lastSyncedAt }.minOrNull()
            if (earliest != null) OverallSyncState.Synced(earliest) else OverallSyncState.Idle
        }
        else -> OverallSyncState.Idle
    }
}
