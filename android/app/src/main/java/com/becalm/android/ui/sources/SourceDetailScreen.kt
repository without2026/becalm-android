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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.SourceStatusIndicator
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.datetime.Clock

/**
 * Source detail screen — status, last-sync info, reconnect / disconnect / manual-sync actions.
 *
 * [SourceStatusIndicator] label is status-phrasing only; no account email/PII is surfaced.
 * Disconnect is a no-op until SMG-004 is implemented (see [SourcesListViewModel.disconnectSource]).
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
                SourceDetailContent(
                    state = state,
                    contentPadding = padding,
                )
            }
        }
    }
}

@Composable
private fun SourceDetailContent(
    state: SourceDetailUiState,
    contentPadding: PaddingValues,
) {
    val syncStatus = when (state.status.uppercase()) {
        "CONNECTED" -> SourceSyncStatus.Ok
        "SYNCING" -> SourceSyncStatus.Ok
        "ERROR" -> SourceSyncStatus.Error
        "NEVER_CONNECTED" -> SourceSyncStatus.Unknown
        else -> SourceSyncStatus.Stale
    }
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
                    Spacer(modifier = Modifier.height(16.dp))
                    // Reconnect / Disconnect — disconnect is a no-op (SMG-004 pending)
                    BecalmButton(
                        text = stringResource(R.string.action_reconnect),
                        onClick = { /* TODO(SMG-004): wire reconnect action */ },
                        variant = BecalmButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    BecalmButton(
                        text = stringResource(R.string.action_disconnect),
                        onClick = { /* TODO(SMG-004): no API exists yet */ },
                        variant = BecalmButtonVariant.Text,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                        style = MaterialTheme.typography.labelSmall,
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
}

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
            SourceDetailContent(
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
            )
        }
    }
}
