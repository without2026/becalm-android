package com.becalm.android.ui.main

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.sourceSyncStatusFor
import kotlinx.datetime.Instant

public data class SourceStatusUi(
    val status: SourceSyncStatus,
    val errorMessage: String?,
    val lastSyncedAt: Instant?,
)

public sealed interface OverallSyncState {
    public data object Idle : OverallSyncState
    public data class Syncing(val count: Int, val total: Int) : OverallSyncState
    public data class Synced(val at: Instant) : OverallSyncState
    public data object PartialFailure : OverallSyncState
}

internal val CHIP_ORDER: List<String> = listOf(
    SourceType.VOICE,
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
    SourceType.NAVER_IMAP,
    SourceType.DAUM_IMAP,
    SourceType.GOOGLE_CALENDAR,
    SourceType.OUTLOOK_CALENDAR,
)

internal fun buildSourceStatusUiMap(statuses: List<SourceStatus>): Map<String, SourceStatusUi> =
    statuses.associate { status ->
        status.sourceType to SourceStatusUi(
            status = sourceSyncStatusFor(status.status),
            errorMessage = status.errorMessage,
            lastSyncedAt = status.lastSyncedAt,
        )
    }

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

internal fun buildChips(sourceStatus: Map<String, SourceStatusUi>): List<SourceStatusChip> =
    CHIP_ORDER.mapNotNull { sourceType ->
        val ui = sourceStatus[sourceType]
        val chipStatus: SourceSyncStatus = when {
            ui == null -> return@mapNotNull null
            ui.errorMessage != null -> return@mapNotNull null
            ui.status == SourceSyncStatus.Syncing -> SourceSyncStatus.Syncing
            ui.status == SourceSyncStatus.Connected -> SourceSyncStatus.Connected
            else -> return@mapNotNull null
        }
        SourceStatusChip(
            sourceType = sourceType,
            status = chipStatus,
            lastSyncedAt = ui.lastSyncedAt,
        )
    }

internal data class SourceStatusAttention(
    val disconnectedCount: Int,
    val failedCount: Int,
) {
    val hasWarning: Boolean = disconnectedCount > 0 || failedCount > 0
}

internal fun buildSourceStatusAttention(sourceStatus: Map<String, SourceStatusUi>): SourceStatusAttention {
    var disconnectedCount = 0
    var failedCount = 0
    CHIP_ORDER.forEach { sourceType ->
        val ui = sourceStatus[sourceType] ?: return@forEach
        when {
            ui.errorMessage != null || ui.status == SourceSyncStatus.Error -> failedCount += 1
            ui.status == SourceSyncStatus.Disconnected -> disconnectedCount += 1
        }
    }
    return SourceStatusAttention(
        disconnectedCount = disconnectedCount,
        failedCount = failedCount,
    )
}
