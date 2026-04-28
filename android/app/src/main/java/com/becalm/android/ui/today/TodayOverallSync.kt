package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import kotlinx.datetime.Instant

/**
 * Aggregate sync-state banner shown above the timeline (TDY-008).
 *
 * Four terminal states, computed from currently active user-facing sources:
 * - [Idle]            — no source has connected/syncing state yet.
 * - [Syncing]         — at least one active source is syncing; carries `count/activeTotal`.
 * - [Synced]          — every active source completed successfully; carries min(last_sync_at).
 * - [PartialFailure]  — at least one active source is in error (amber warning copy).
 */
public sealed interface OverallSyncState {
    public data object Idle : OverallSyncState
    public data class Syncing(val count: Int, val total: Int) : OverallSyncState
    public data class Synced(val at: Instant) : OverallSyncState
    public data object PartialFailure : OverallSyncState
}

/**
 * Computes the TDY-008 aggregate state from connected/syncing/error product sources.
 *
 * Only the seven user-facing sources count toward the banner. [SourceType.CALL_RECORDING]
 * is explicitly excluded even if a caller forwards it directly.
 *
 * Priority order:
 * 1. Any source in ERROR → [OverallSyncState.PartialFailure].
 * 2. Any source in SYNCING → [OverallSyncState.Syncing(count, activeTotal)].
 * 3. Every active source in CONNECTED → [OverallSyncState.Synced(min(lastSyncedAt))].
 *    Falls back to [OverallSyncState.Idle] when the earliest timestamp is null.
 * 4. Otherwise → [OverallSyncState.Idle].
 */
internal fun deriveOverallState(sources: List<SourceStatus>): OverallSyncState {
    val chipSources = SourceType.PRODUCT_SOURCES.mapNotNull { source ->
        sources.firstOrNull { it.sourceType == source }
    }.filter { source ->
        source.status != SourceConnectionStatus.NEVER_CONNECTED
    }
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
