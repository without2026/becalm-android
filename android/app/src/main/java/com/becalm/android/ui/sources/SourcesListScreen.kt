package com.becalm.android.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.SourceStatusIndicator
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.statusStringToSyncStatus
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Sources list screen — 6-source adapter status rows.
 *
 * Tap on a source navigates to [BecalmRoute.SourceDetail].
 * [SourceStatusIndicator] label is the status string only, never an email or
 * account identifier (KDoc on [SourceStatusIndicator] prohibits PII in label).
 *
 * spec: ENR-008, SMG-001
 *
 * Primary VM: [SourcesListViewModel]
 * Navigation entry: [BecalmRoute.SettingsSources]
 * Navigation exit: [BecalmRoute.SourceDetail] on row tap | back to [BecalmRoute.Settings]
 */
@Composable
public fun SourcesListScreen(
    navController: NavHostController,
    viewModel: SourcesListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BecalmScaffold(
        title = stringResource(R.string.sources_title),
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.sources_empty_title),
                message = stringResource(R.string.sources_empty_message),
                icon = Icons.Filled.List,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(items = state.items, key = { it.sourceType }) { row ->
                    SourceRowItem(
                        row = row,
                        onClick = {
                            navController.navigate(BecalmRoute.SourceDetail(row.sourceType).path)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceRowItem(
    row: SourceStatusRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val syncStatus = statusStringToSyncStatus(row.status)
    val statusLabel = when (syncStatus) {
        SourceSyncStatus.Ok -> stringResource(R.string.sources_status_ok)
        SourceSyncStatus.Stale -> stringResource(R.string.sources_status_stale)
        SourceSyncStatus.Error -> stringResource(R.string.sources_status_error)
        SourceSyncStatus.Unknown -> stringResource(R.string.sources_status_unknown)
    }
    Row(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.sourceType.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (row.lastError != null) {
                Text(
                    text = row.lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        SourceStatusIndicator(
            status = syncStatus,
            label = statusLabel,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewSourcesListScreen() {
    BecalmTheme {
        BecalmScaffold(
            title = "Data Sources",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        ) { padding ->
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(
                    listOf(
                        SourceStatusRow("gmail", "CONNECTED", null, null, 0),
                        SourceStatusRow("outlook", "ERROR", null, "Auth expired", 0),
                        SourceStatusRow("imap", "NEVER_CONNECTED", null, null, 0),
                    ),
                ) { row ->
                    SourceRowItem(
                        row = row,
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
