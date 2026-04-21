package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Raw event detail screen — extended fields loaded from Room for a single ingestion event.
 *
 * Named "Sheet" in the spec but implemented as a full screen for navigation consistency.
 * PII note: [RawIngestionEventEntity.eventSnippet] and raw [personRef] are intentionally
 * not shown in @Preview sample data.
 *
 * spec: SRC-008
 *
 * Primary VM: [RawEventDetailViewModel]
 * Navigation entry: [BecalmRoute.RawEventDetail]
 * Navigation exit: back to [BecalmRoute.PersonDetail]
 */
@Composable
public fun RawEventDetailSheet(
    navController: NavHostController,
    personId: String,
    eventId: String,
    viewModel: RawEventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BecalmScaffold(
        title = stringResource(R.string.raw_event_detail_title),
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
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                ErrorState(
                    title = stringResource(R.string.raw_event_detail_not_found),
                    message = state.error,
                    modifier = Modifier.padding(padding),
                )
            }
            state.sourceType != null -> {
                // Snapshot the delegated state properties into locals so the compiler can
                // smart-cast them inside the block below (delegated `by` properties cannot
                // be smart-cast across references). `sourceType` is guarded non-null by the
                // branch condition, so `!!` is safe here.
                val sourceType: String = state.sourceType!!
                val timestamp = state.timestamp
                val eventTitle = state.eventTitle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(padding)
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassPanel(MaterialTheme.shapes.medium)
                            .padding(16.dp),
                    ) {
                        DetailRow(
                            label = stringResource(R.string.raw_event_detail_source),
                            value = sourceType,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        DetailRow(
                            label = stringResource(R.string.raw_event_detail_timestamp),
                            value = timestamp?.toString() ?: "",
                        )
                        if (eventTitle != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = eventTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            else -> {
                EmptyState(
                    title = stringResource(R.string.raw_event_detail_not_found),
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewRawEventDetailSheetLoading() {
    BecalmTheme {
        BecalmScaffold(
            title = "Event Detail",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}
