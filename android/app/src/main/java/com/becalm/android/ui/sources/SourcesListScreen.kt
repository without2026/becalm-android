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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.SourceStatusIndicator
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.components.sourceStatusLabelRes
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.dispatchSourcesListNavigation
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Sources list screen — user-facing source status rows.
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

    CollectFlowEffect(viewModel.navigation) { target ->
        navController.dispatchSourcesListNavigation(target)
    }

    SourcesListScreenContent(
        state = state,
        onBack = navController::popBackStack,
        onRowClick = viewModel::onSourceSelected,
    )
}

@Composable
public fun SourcesListScreenContent(
    state: SourcesListUiState,
    onBack: () -> Unit,
    onRowClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.sources_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
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
                icon = Icons.AutoMirrored.Filled.List,
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
                        onClick = { onRowClick(row.sourceType) },
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
    val statusLabel = stringResource(sourceStatusLabelRes(row.status))
    EvidenceCard(
        modifier = modifier
            .testTag("sources-row-${row.sourceType}")
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rowDisplayName(row),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (row.sourceType == "contacts" && row.enrichedCount != null) {
                    Text(
                        text = stringResource(R.string.sources_contacts_enriched_fmt, row.enrichedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.hasError) {
                    Text(
                        text = stringResource(R.string.sources_last_error_generic),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            SourceStatusIndicator(
                status = row.status,
                label = statusLabel,
            )
        }
    }
}

@Composable
private fun rowDisplayName(row: SourceStatusRow): String = when (row.sourceType) {
    "contacts" -> stringResource(R.string.sources_contacts_title)
    else -> stringResource(sourcePresentationFor(row.sourceType).labelRes)
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
                        SourceStatusRow("gmail", SourceSyncStatus.Connected, null, false),
                        SourceStatusRow("outlook", SourceSyncStatus.Error, null, true),
                        SourceStatusRow("imap", SourceSyncStatus.Disconnected, null, false),
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
