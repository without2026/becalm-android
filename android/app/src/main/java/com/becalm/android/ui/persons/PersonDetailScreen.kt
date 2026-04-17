package com.becalm.android.ui.persons

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.coroutines.launch

/**
 * Person detail screen — 3-section body: pending commitments / completed / interaction history.
 *
 * spec: SRC-003, SRC-004, SRC-005
 *
 * Primary VM: [PersonDetailViewModel]
 * Navigation entry: [BecalmRoute.PersonDetail]
 * Navigation exit: [BecalmRoute.RawEventDetail] on event tap | back to [BecalmRoute.Persons]
 */
@Composable
public fun PersonDetailScreen(
    navController: NavHostController,
    personId: String,
    viewModel: PersonDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error) {
        state.error?.let { err ->
            scope.launch {
                snackbarHostState.showSnackbar(err)
                viewModel.onErrorDismissed()
            }
        }
    }

    val displayName = state.displayName ?: personId.take(16)

    BecalmScaffold(
        title = displayName,
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val hasAnyInteractions = state.pendingCommitments.isNotEmpty() ||
            state.completedCommitments.isNotEmpty() ||
            state.interactionHistory.isNotEmpty()
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
            state.error != null && !hasAnyInteractions -> {
                ErrorState(
                    title = stringResource(R.string.error_generic_title),
                    message = state.error,
                    modifier = Modifier.padding(padding),
                )
            }
            !hasAnyInteractions -> {
                EmptyState(
                    title = stringResource(R.string.person_detail_empty_interactions),
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                InteractionList(
                    pendingCommitments = state.pendingCommitments,
                    completedCommitments = state.completedCommitments,
                    interactionHistory = state.interactionHistory,
                    personId = personId,
                    contentPadding = padding,
                    onEventClick = { eventId ->
                        navController.navigate(BecalmRoute.RawEventDetail(personId, eventId).path)
                    },
                )
            }
        }
    }
}

@Composable
private fun InteractionList(
    pendingCommitments: List<InteractionRow.Commitment>,
    completedCommitments: List<InteractionRow.Commitment>,
    interactionHistory: List<InteractionRow>,
    personId: String,
    contentPadding: PaddingValues,
    onEventClick: (String) -> Unit,
) {
    val commitmentsHeader = stringResource(R.string.person_detail_commitments_section)
    val historyHeader = stringResource(R.string.person_detail_history_section)
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        interactionSection(
            header = commitmentsHeader,
            headerKey = "header-pending",
            rows = pendingCommitments,
            itemKey = { row ->
                "cp-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}"
            },
        )
        interactionSection(
            header = commitmentsHeader,
            headerKey = "header-completed",
            rows = completedCommitments,
            itemKey = { row ->
                "cd-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}"
            },
        )
        interactionSection(
            header = historyHeader,
            headerKey = "header-history",
            rows = interactionHistory,
            itemKey = { row ->
                when (row) {
                    is InteractionRow.Event -> "e-${row.timestamp.toEpochMilliseconds()}-${row.source}"
                    is InteractionRow.Commitment -> "c-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}"
                    is InteractionRow.CalendarMeeting -> "m-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}"
                }
            },
        )
    }
}

/**
 * Emits a `SectionHeader` + list of [InteractionRowItem]s into this [LazyListScope].
 *
 * No-op when [rows] is empty, matching the `isNotEmpty()` guard at each former call site.
 * The generic bound `<T : InteractionRow>` lets callers pass either a `List<InteractionRow.Commitment>`
 * (covariant) or the polymorphic `List<InteractionRow>` without forcing a cast.
 */
private fun <T : InteractionRow> LazyListScope.interactionSection(
    header: String,
    headerKey: String,
    rows: List<T>,
    itemKey: (T) -> String,
) {
    if (rows.isEmpty()) return
    item(key = headerKey) {
        SectionHeader(text = header)
    }
    items(
        items = rows,
        key = itemKey,
    ) { row ->
        InteractionRowItem(
            row = row,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun InteractionRowItem(
    row: InteractionRow,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        when (row) {
            is InteractionRow.Event -> {
                Row {
                    Text(
                        text = stringResource(R.string.person_detail_source_label, row.source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (row.summary != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = row.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            is InteractionRow.Commitment -> {
                InteractionLabelAndBody(
                    label = stringResource(R.string.person_detail_commitments_section),
                    body = row.title,
                )
            }
            is InteractionRow.CalendarMeeting -> {
                InteractionLabelAndBody(
                    label = stringResource(R.string.today_section_meetings),
                    body = row.title,
                )
            }
        }
    }
}

/**
 * Label + body text pair used by [InteractionRowItem] for the Commitment and CalendarMeeting
 * branches. The Event branch intentionally inlines its own layout because it wraps the label
 * in a `Row` (preserving the original Compose tree).
 *
 * Visual parity with the former inlined forms:
 *  - Label: `labelSmall` typography, primary colour.
 *  - 4.dp spacer between label and body.
 *  - Body: `bodyMedium` typography, onSurface colour.
 */
@Composable
private fun InteractionLabelAndBody(label: String, body: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@PreviewLightDark
@Composable
private fun PreviewPersonDetailScreenWithHistory() {
    BecalmTheme {
        BecalmScaffold(
            title = "Alice Kim",
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
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = "Interaction History",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(
                    listOf(
                        InteractionRow.Commitment(
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            title = "Send contract draft",
                            direction = "give",
                            actionState = "CONFIRMED",
                        ),
                        InteractionRow.CalendarMeeting(
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            title = "Q2 Planning Meeting",
                        ),
                    ),
                ) { row ->
                    InteractionRowItem(
                        row = row,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
