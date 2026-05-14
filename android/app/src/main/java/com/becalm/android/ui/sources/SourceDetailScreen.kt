package com.becalm.android.ui.sources

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.components.sourceStatusLabelRes
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.domain.meeting.MeetingImportFilePolicy
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.dispatchSourceDetailEffect
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Clock

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
    val audioPicker = rememberLauncherForActivityResult(
        MeetingOpenDocumentContract(),
    ) { uri ->
        viewModel.onMeetingAudioSelected(uri)
    }
    val audioMimeTypes = remember { MeetingImportFilePolicy.AUDIO_MIME_TYPES }
    val meetingAudioInitialUri = remember(state.meetingAudioPickerInitialUri) {
        state.meetingAudioPickerInitialUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    CollectFlowEffect(viewModel.effects) { effect ->
        navController.dispatchSourceDetailEffect(effect)
    }

    BecalmScaffold(
        title = if (state.sourceType.isNotEmpty()) {
            stringResource(sourcePresentationFor(state.sourceType).labelRes)
        } else {
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
                    message = uiMessageStringResource(requireNotNull(state.error)),
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
                    onMeetingAudioAdd = {
                        audioPicker.launch(
                            MeetingOpenDocumentRequest(
                                mimeTypes = audioMimeTypes,
                                initialUri = meetingAudioInitialUri,
                            ),
                        )
                    },
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
    onMeetingAudioAdd: () -> Unit,
) {
    val statusLabel = stringResource(sourceStatusLabelRes(state.status))

    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier
            .fillMaxSize()
            .testTag("source-detail-list"),
    ) {
        item {
            SourceStatusSummarySection(
                state = state,
                syncStatus = state.status,
                statusLabel = statusLabel,
                onReconnect = onReconnect,
                onManualSync = onManualSync,
                onMeetingAudioAdd = onMeetingAudioAdd,
            )
        }

        item {
            SourceDetailSectionHeader(text = stringResource(R.string.source_detail_recent_events_section))
        }

        if (state.recentEvents.isEmpty()) {
            item {
                EmptyState(
                    title = stringResource(R.string.source_detail_empty_events),
                )
            }
        } else {
            items(items = state.recentEvents, key = { it.id }) { event ->
                RecentSourceEventRow(event = event)
            }
        }

        if (state.showDisconnectButton) {
            item(key = "danger-zone") {
                SourceDangerZone(onDisconnectClick = onDisconnectClick)
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
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
        ) { padding ->
            SourceDetailScreenContent(
                state = SourceDetailUiState(
                    sourceType = "gmail",
                    status = SourceSyncStatus.Connected,
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
                onMeetingAudioAdd = {},
            )
        }
    }
}
