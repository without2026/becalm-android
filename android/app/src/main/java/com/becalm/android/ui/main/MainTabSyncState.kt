package com.becalm.android.ui.main

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.ui.components.ChipState
import com.becalm.android.ui.components.SourceStatusChip
import kotlinx.datetime.Instant

public data class SourceStatusUi(
    val syncing: Boolean,
    val statusLabel: String,
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
            syncing = status.status == SourceConnectionStatus.SYNCING,
            statusLabel = status.status.name,
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
        val chipState: ChipState = when {
            ui == null -> return@mapNotNull null
            ui.errorMessage != null -> return@mapNotNull null
            ui.syncing -> ChipState.Syncing
            ui.statusLabel == "CONNECTED" && ui.lastSyncedAt != null ->
                ChipState.Synced(ui.lastSyncedAt)
            ui.statusLabel == "CONNECTED" -> ChipState.Idle
            else -> return@mapNotNull null
        }
        SourceStatusChip(sourceType = sourceType, state = chipState)
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
            ui.errorMessage != null || ui.statusLabel == "ERROR" -> failedCount += 1
            ui.statusLabel == "NEVER_CONNECTED" -> disconnectedCount += 1
        }
    }
    return SourceStatusAttention(
        disconnectedCount = disconnectedCount,
        failedCount = failedCount,
    )
}
