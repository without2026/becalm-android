package com.becalm.android.ui.sources

import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus

internal object SourceDetailProjector {
    fun buildUiState(
        sourceType: String,
        status: SourceStatus?,
        sourceEvents: List<RawIngestionEventEntity>,
        showDisconnectConfirmDialog: Boolean,
        disconnectOutcome: SourceDisconnectOutcome?,
        actionError: String?,
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
            status = status?.status?.name ?: "",
            lastSyncAt = status?.lastSyncedAt,
            eventsSyncedCount = eventSummaries.size,
            lastError = status?.errorMessage,
            showReconnectButton = connectionButtons.showReconnectButton,
            showDisconnectButton = connectionButtons.showDisconnectButton,
            showManualSyncButton = connectionButtons.showManualSyncButton,
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
