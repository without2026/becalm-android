package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.launch

/**
 * Person detail screen — renders a [PersonHeader] plus a 3-section body
 * (pending commitments / completed commitments / interaction history) per
 * `.spec/contracts/ui-map.yml:106-111`.
 *
 * spec: SRC-003, SRC-004, SRC-005, SRC-008, ENR-006
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

    BecalmScaffold(
        title = state.displayName ?: personId.take(16),
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
            !hasAnyInteractions -> PersonDetailEmpty(state = state, padding = padding)
            else -> PersonDetailList(state = state, padding = padding)
        }
    }
}

// ─── Content branches ─────────────────────────────────────────────────────────

@Composable
private fun PersonDetailEmpty(state: PersonDetailUiState, padding: PaddingValues) {
    LazyColumn(
        contentPadding = padding,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            PersonHeader(
                displayName = state.displayName,
                companyName = state.companyName,
                jobTitle = state.jobTitle,
                personRef = state.personRef,
            )
        }
        item(key = "empty") {
            EmptyState(title = stringResource(R.string.person_detail_empty_interactions))
        }
    }
}

@Composable
private fun PersonDetailList(state: PersonDetailUiState, padding: PaddingValues) {
    val commitmentsHeader = stringResource(R.string.person_detail_commitments_section)
    val historyHeader = stringResource(R.string.person_detail_history_section)
    LazyColumn(
        contentPadding = padding,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            PersonHeader(
                displayName = state.displayName,
                companyName = state.companyName,
                jobTitle = state.jobTitle,
                personRef = state.personRef,
            )
        }
        interactionSection(
            header = commitmentsHeader,
            headerKey = "header-pending",
            rows = state.pendingCommitments,
            itemKey = { row -> "cp-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}" },
        )
        interactionSection(
            header = commitmentsHeader,
            headerKey = "header-completed",
            rows = state.completedCommitments,
            itemKey = { row -> "cd-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}" },
        )
        interactionSection(
            header = historyHeader,
            headerKey = "header-history",
            rows = state.interactionHistory,
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
 * Emits a `SectionHeader` + list of [InteractionHistoryRow]s into this [LazyListScope].
 *
 * No-op when [rows] is empty. The generic bound `<T : InteractionRow>` lets callers pass
 * either a covariant list of a subtype or the polymorphic list without an explicit cast.
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
        InteractionHistoryRow(
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
                    PersonHeader(
                        displayName = "Alice Kim",
                        companyName = "Acme Corp",
                        jobTitle = "Product Lead",
                        personRef = "alice@acme.com",
                    )
                }
                items(
                    listOf(
                        InteractionRow.Commitment(
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            title = "Send contract draft",
                            direction = "give",
                            actionState = "pending",
                        ),
                        InteractionRow.CalendarMeeting(
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            title = "Q2 Planning Meeting",
                        ),
                    ),
                ) { row ->
                    InteractionHistoryRow(
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
