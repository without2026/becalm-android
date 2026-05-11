package com.becalm.android.ui.sources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.DangerZone
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.SourceStatusIndicator
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.uiMessageStringResource
import kotlinx.datetime.Instant

@Composable
internal fun SourceStatusSummarySection(
    state: SourceDetailUiState,
    syncStatus: SourceSyncStatus,
    statusLabel: String,
    onReconnect: () -> Unit,
    onManualSync: () -> Unit,
    onMeetingAudioAdd: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.source_detail_status_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        QuietPanel(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.source_detail_status_section),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SourceStatusIndicator(
                    status = syncStatus,
                    label = statusLabel,
                )
            }
            SourceStatusMeta(state = state)
            SourceRecoveryActions(
                state = state,
                onReconnect = onReconnect,
                onManualSync = onManualSync,
            )
            MeetingImportActions(
                state = state,
                onMeetingAudioAdd = onMeetingAudioAdd,
            )
        }
    }
}

@Composable
private fun SourceStatusMeta(state: SourceDetailUiState) {
    state.lastSyncAt?.let { at ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(
                R.string.source_detail_last_sync_fmt,
                at.toLocalTimeLabel(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    state.eventsSyncedCount?.let { count ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.source_detail_events_synced_fmt, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.hasError) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.source_detail_last_error_generic),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    state.actionError?.let { actionError ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = uiMessageStringResource(actionError),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SourceRecoveryActions(
    state: SourceDetailUiState,
    onReconnect: () -> Unit,
    onManualSync: () -> Unit,
) {
    if (!state.showReconnectButton && !state.showManualSyncButton) return
    Spacer(modifier = Modifier.height(16.dp))
    if (state.showReconnectButton) {
        BecalmButton(
            text = stringResource(R.string.action_reconnect),
            onClick = onReconnect,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("source-detail-reconnect"),
        )
    }
    if (state.showManualSyncButton) {
        if (state.showReconnectButton) {
            Spacer(modifier = Modifier.height(8.dp))
        }
        BecalmButton(
            text = stringResource(R.string.action_sync_now),
            onClick = onManualSync,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("source-detail-sync-now"),
        )
    }
}

@Composable
private fun MeetingImportActions(
    state: SourceDetailUiState,
    onMeetingAudioAdd: () -> Unit,
) {
    if (!state.showMeetingAudioAddButton) return
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = stringResource(R.string.source_detail_meeting_import_section),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (state.showMeetingAudioAddButton) {
        Spacer(modifier = Modifier.height(8.dp))
        BecalmButton(
            text = stringResource(R.string.source_detail_meeting_audio_add),
            onClick = onMeetingAudioAdd,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("source-detail-meeting-audio-add"),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.source_detail_meeting_audio_formats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SourceDetailSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
internal fun RecentSourceEventRow(event: RecentEventSummary) {
    EvidenceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = event.timestamp.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (event.title != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
internal fun SourceDangerZone(onDisconnectClick: () -> Unit) {
    DangerZone(
        title = stringResource(R.string.source_detail_danger_zone_title),
        body = stringResource(R.string.source_detail_disconnect_body),
    ) {
        BecalmButton(
            text = stringResource(R.string.action_disconnect),
            onClick = onDisconnectClick,
            variant = BecalmButtonVariant.Text,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("source-detail-disconnect"),
        )
    }
}

private fun Instant.toLocalTimeLabel(): String = toString().substringAfter("T").take(5)
