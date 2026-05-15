package com.becalm.android.ui.sources

import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.components.sourceSyncStatusFor

internal object SourceDetailProjector {
    fun buildUiState(
        sourceType: String,
        status: SourceStatus?,
        sourceEvents: List<RawIngestionEventEntity>,
        showDisconnectConfirmDialog: Boolean,
        disconnectOutcome: SourceDisconnectOutcome?,
        actionError: UiMessage?,
    ): SourceDetailUiState {
        val eventSummaries = sourceEvents.map { entity ->
            RecentEventSummary(
                id = entity.id,
                timestamp = entity.timestamp,
                title = entity.eventTitle,
            )
        }
        val connectionButtons = buttonVisibilityFor(status?.status)
        return SourceDetailUiState(
            sourceType = sourceType,
            status = sourceSyncStatusFor(status?.status),
            lastSyncAt = status?.lastSyncedAt,
            eventsSyncedCount = eventSummaries.size,
            hasError = status?.status == SourceConnectionStatus.ERROR || status?.errorMessage != null,
            showReconnectButton = connectionButtons.showReconnectButton,
            showDisconnectButton = connectionButtons.showDisconnectButton,
            showManualSyncButton = connectionButtons.showManualSyncButton,
            showMeetingAudioAddButton = sourceType == SourceType.MEETING,
            showDisconnectConfirmDialog = showDisconnectConfirmDialog,
            disconnectOutcome = disconnectOutcome,
            actionError = actionError,
            recentEvents = eventSummaries,
            error = null,
        )
    }

    private fun buttonVisibilityFor(
        status: SourceConnectionStatus?,
    ): DetailButtonVisibility =
        when (status) {
            SourceConnectionStatus.CONNECTED,
            SourceConnectionStatus.SYNCING,
            -> DetailButtonVisibility(
                showReconnectButton = false,
                showDisconnectButton = true,
                showManualSyncButton = true,
            )
            SourceConnectionStatus.ERROR,
            -> DetailButtonVisibility(
                showReconnectButton = true,
                showDisconnectButton = false,
                showManualSyncButton = true,
            )
            SourceConnectionStatus.NEVER_CONNECTED,
            null,
            -> DetailButtonVisibility(
                showReconnectButton = true,
                showDisconnectButton = false,
                showManualSyncButton = false,
            )
        }
}

internal data class DetailButtonVisibility(
    val showReconnectButton: Boolean,
    val showDisconnectButton: Boolean,
    val showManualSyncButton: Boolean,
)
