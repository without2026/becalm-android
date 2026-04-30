package com.becalm.android.ui.sources

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.SourceStatusIndicator
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.statusStringToSyncStatus
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.dispatchSourceDetailEffect
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Source detail screen — status, last-sync info, reconnect / disconnect / manual-sync actions.
 *
 * [SourceStatusIndicator] label is status-phrasing only; no account email/PII is surfaced.
 *
 * spec: SMG-002..005
 *
 * Primary VM: [SourceDetailViewModel]
 * Navigation entry: [BecalmRoute.SourceDetail]
 * Navigation exit: back to [BecalmRoute.SettingsSources]
 */
@Composable
public fun SourceDetailScreen(
    navController: NavHostController,
    sourceId: String,
    viewModel: SourceDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CollectFlowEffect(viewModel.effects) { effect ->
        navController.dispatchSourceDetailEffect(effect)
    }

    BecalmScaffold(
        title = state.sourceType.replaceFirstChar { it.uppercase() }.ifEmpty {
            stringResource(R.string.source_detail_title)
        },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        when {
            state.error != null -> {
                ErrorState(
                    title = stringResource(R.string.source_detail_error_missing_source),
                    message = state.error,
                    modifier = Modifier.padding(padding),
                )
            }
            state.sourceType.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                SourceDetailScreenContent(
                    state = state,
                    contentPadding = padding,
                    onReconnect = viewModel::onReconnect,
                    onManualSync = viewModel::onManualSync,
                    onDisconnectClick = viewModel::onDisconnectClick,
                    onDisconnectDismiss = viewModel::onDisconnectDismiss,
                    onDisconnectConfirm = viewModel::onDisconnectConfirm,
                )
            }
        }
    }
}

@Composable
public fun SourceDetailScreenContent(
    state: SourceDetailUiState,
    contentPadding: PaddingValues,
    onReconnect: () -> Unit,
    onManualSync: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDisconnectDismiss: () -> Unit,
    onDisconnectConfirm: () -> Unit,
) {
    val syncStatus = statusStringToSyncStatus(state.status)
    val statusLabel = when (syncStatus) {
        SourceSyncStatus.Ok -> stringResource(R.string.sources_status_ok)
        SourceSyncStatus.Stale -> stringResource(R.string.sources_status_stale)
        SourceSyncStatus.Error -> stringResource(R.string.sources_status_error)
        SourceSyncStatus.Unknown -> stringResource(R.string.sources_status_unknown)
    }

    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        // Status section
        item {
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(MaterialTheme.shapes.medium)
                        .padding(16.dp),
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
                    state.lastError?.let { lastError ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.source_detail_last_error_fmt, lastError),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    state.actionError?.let { actionError ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = actionError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
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
                    if (state.showDisconnectButton) {
                        Spacer(modifier = Modifier.height(8.dp))
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
            }
        }

        // Recent events section
        item {
            Text(
                text = stringResource(R.string.source_detail_recent_events_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (state.recentEvents.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.source_detail_empty_events),
                )
            }
        } else {
            items(items = state.recentEvents, key = { it.id }) { event ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .glassPanel(MaterialTheme.shapes.medium)
                        .padding(12.dp),
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
        }
    }

    if (state.showDisconnectConfirmDialog) {
        AlertDialog(
            onDismissRequest = onDisconnectDismiss,
            title = {
                Text(text = stringResource(R.string.source_detail_disconnect_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.source_detail_disconnect_confirm_body))
            },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = onDisconnectConfirm,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier.testTag("source-detail-disconnect-confirm"),
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDisconnectDismiss,
                    variant = BecalmButtonVariant.Text,
                    modifier = Modifier.testTag("source-detail-disconnect-cancel"),
                )
            },
        )
    }
}

private fun Instant.toLocalTimeLabel(): String = toString().substringAfter("T").take(5)

@PreviewLightDark
@Composable
private fun PreviewSourceDetailScreenWithEvents() {
    BecalmTheme {
        BecalmScaffold(
            title = "Gmail",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        ) { padding ->
            SourceDetailScreenContent(
                state = SourceDetailUiState(
                    sourceType = "gmail",
                    status = "CONNECTED",
                    recentEvents = listOf(
                        RecentEventSummary(
                            id = "evt1",
                            timestamp = Clock.System.now(),
                            title = "Meeting follow-up",
                        ),
                        RecentEventSummary(
                            id = "evt2",
                            timestamp = Clock.System.now(),
                            title = null,
                        ),
                    ),
                ),
                contentPadding = padding,
                onReconnect = {},
                onManualSync = {},
                onDisconnectClick = {},
                onDisconnectDismiss = {},
                onDisconnectConfirm = {},
            )
        }
    }
}
