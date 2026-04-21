package com.becalm.android.ui.commitments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.becalm.android.R
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CommitmentCard
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ExpandableSectionHeader
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/**
 * Commitment management screen — full list with filter tabs.
 *
 * Filter tabs: 전체 / 내가 한 / 상대가 한.
 * Due-today / overdue indicators surface as per-card DN badges, not top-level filters.
 * Each [CommitmentRow] is rendered via [CommitmentCard].
 * Error surfaced via [SnackbarHost].
 * Pull-to-refresh (CMT-010) triggers [CommitmentManagementViewModel.onPullRefresh].
 *
 * spec: CMT-001..CMT-010
 *
 * Primary VM: [CommitmentManagementViewModel]
 * Navigation entry: [BecalmRoute.Commitments]
 * Navigation exit: none (leaf screen in this round)
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
public fun CommitmentManagementScreen(
    viewModel: CommitmentManagementViewModel = hiltViewModel(),
    onOpenDetail: (id: String) -> Unit = {},
    onOpenCreate: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullRefreshState(
        refreshing = state.refreshing,
        onRefresh = viewModel::onPullRefresh,
    )

    LaunchedEffect(state.error) {
        state.error?.let { err ->
            scope.launch {
                snackbarHostState.showSnackbar(err)
                viewModel.onErrorDismissed()
            }
        }
    }

    // CMT-013 — collect one-shot undo snapshots emitted by [onComplete] / [onCancel]
    // and present a `[복구]` snackbar with a 5 s window. Material3 does not expose a
    // 5 s SnackbarDuration token (Short ≈ 4 s, Long ≈ 10 s), so we race the Long
    // snackbar against an explicit 5 s timeout via [withTimeoutOrNull]. When the
    // user taps the action before the timeout fires we see [SnackbarResult.ActionPerformed]
    // and invoke [onUndo]; a timeout or dismissal leaves the terminal state as-is.
    val undoCompletedMessage = stringResource(R.string.commitment_undo_completed)
    val undoCancelledMessage = stringResource(R.string.commitment_undo_cancelled)
    val undoActionLabel = stringResource(R.string.commitment_undo_action)
    LaunchedEffect(Unit) {
        viewModel.undoFlow.collect { snapshot ->
            val message = when (snapshot) {
                is CommitmentUndoSnapshot.Completed -> undoCompletedMessage
                is CommitmentUndoSnapshot.Cancelled -> undoCancelledMessage
            }
            scope.launch {
                val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
                    snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = undoActionLabel,
                        duration = SnackbarDuration.Long,
                    )
                }
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.onUndo(snapshot)
                } else {
                    // 5s elapsed without a tap — dismiss the still-showing Long snackbar.
                    snackbarHostState.currentSnackbarData?.dismiss()
                }
            }
        }
    }

    BecalmScaffold(
        title = stringResource(R.string.commitments_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // C9 / MAN-001 — Manual add FAB. Navigates to the create sheet with
            // supersedeOf=null; edit-sheet supersede path reuses the same destination
            // with supersedeOf bound to the old row id.
            ExtendedFloatingActionButton(
                onClick = onOpenCreate,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.commitment_fab_add_content_desc),
                    )
                },
                text = { Text(text = stringResource(R.string.commitment_fab_add)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            FilterChipRow(
                selectedFilter = state.filter,
                onFilterSelect = viewModel::onFilterChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pullRefresh(pullState),
            ) {
                when {
                    state.loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.items.isEmpty() -> {
                        EmptyState(
                            title = stringResource(R.string.commitments_empty_title),
                            message = stringResource(R.string.commitments_empty_message),
                        )
                    }
                    else -> {
                        // CMT-009 — partition rows into active + the two terminal sections
                        // (completed / cancelled). The active group renders without a
                        // header; each non-empty terminal group renders behind a
                        // collapsed-by-default [ExpandableSectionHeader]. Empty groups
                        // omit their header entirely so `이행 완료 (0)` never flashes.
                        val (activeRows, completedRows, cancelledRows) =
                            partitionRowsByLifecycle(state.items)
                        var completedExpanded by rememberSaveable {
                            mutableStateOf(false)
                        }
                        var cancelledExpanded by rememberSaveable {
                            mutableStateOf(false)
                        }
                        val completedHeader = stringResource(
                            R.string.commitment_section_completed_fmt,
                            completedRows.size,
                        )
                        val cancelledHeader = stringResource(
                            R.string.commitment_section_cancelled_fmt,
                            cancelledRows.size,
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(items = activeRows, key = { "active-${it.id}" }) { row ->
                                CommitmentRowCard(row = row, onOpenDetail = onOpenDetail)
                            }

                            if (completedRows.isNotEmpty()) {
                                item(key = "header-completed") {
                                    ExpandableSectionHeader(
                                        title = completedHeader,
                                        expanded = completedExpanded,
                                        onToggle = {
                                            completedExpanded = !completedExpanded
                                        },
                                    )
                                }
                                if (completedExpanded) {
                                    items(
                                        items = completedRows,
                                        key = { "completed-${it.id}" },
                                    ) { row ->
                                        CommitmentRowCard(row = row, onOpenDetail = onOpenDetail)
                                    }
                                }
                            }

                            if (cancelledRows.isNotEmpty()) {
                                item(key = "header-cancelled") {
                                    ExpandableSectionHeader(
                                        title = cancelledHeader,
                                        expanded = cancelledExpanded,
                                        onToggle = {
                                            cancelledExpanded = !cancelledExpanded
                                        },
                                    )
                                }
                                if (cancelledExpanded) {
                                    items(
                                        items = cancelledRows,
                                        key = { "cancelled-${it.id}" },
                                    ) { row ->
                                        CommitmentRowCard(row = row, onOpenDetail = onOpenDetail)
                                    }
                                }
                            }
                        }
                    }
                }

                PullRefreshIndicator(
                    refreshing = state.refreshing,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * CMT-013 undo window. Spec pins it at 5 seconds; Material3's [SnackbarDuration.Long]
 * is the closest built-in (~10 s), so the call-site races it against this timeout.
 */
private const val UNDO_WINDOW_MS: Long = 5_000L

/**
 * CMT-009 — partitions the full [rows] list into the three rendering buckets used by
 * [CommitmentManagementScreen]. Extracted as a pure helper so the Composable body reads
 * as "partition + render" rather than three inline [List.filter] lambdas.
 *
 * @return A [Triple] of (active, completed, cancelled). `active` is every row whose
 *   `action_state` is neither COMPLETED nor CANCELLED — i.e. PENDING / REMINDED /
 *   FOLLOWED_UP / OVERDUE. The three buckets are disjoint and their union equals
 *   [rows] (no row is dropped or duplicated).
 */
private fun partitionRowsByLifecycle(
    rows: List<CommitmentRow>,
): Triple<List<CommitmentRow>, List<CommitmentRow>, List<CommitmentRow>> {
    val active = rows.filter {
        it.actionState != CommitmentState.COMPLETED &&
            it.actionState != CommitmentState.CANCELLED
    }
    val completed = rows.filter { it.actionState == CommitmentState.COMPLETED }
    val cancelled = rows.filter { it.actionState == CommitmentState.CANCELLED }
    return Triple(active, completed, cancelled)
}

/**
 * Renders one [CommitmentRow] as a [CommitmentCard]. Extracted so the LazyColumn call
 * sites in the three partitioned groups (active / completed / cancelled) share a
 * single card contract — particularly the C4 detail-sheet navigation wiring.
 */
@Composable
private fun CommitmentRowCard(
    row: CommitmentRow,
    onOpenDetail: (id: String) -> Unit,
) {
    CommitmentCard(
        title = row.title,
        direction = row.direction,
        derivedStatus = row.derivedStatus,
        dueAt = row.dueAt,
        counterpartyDisplayName = row.counterpartyDisplayName,
        dueIsApproximate = row.dueIsApproximate,
        dueHint = row.dueHint,
        isManual = row.isManual,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        // C4 wiring — card tap opens the CommitmentDetailSheet route
        // (see BecalmRoute.CommitmentDetail). Action buttons live inside the sheet;
        // onMarkDone stays unset so the card stays visually minimal.
        onClick = { onOpenDetail(row.id) },
    )
}

@Composable
private fun FilterChipRow(
    selectedFilter: CommitmentFilter,
    onFilterSelect: (CommitmentFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = listOf(
        CommitmentFilter.ALL to stringResource(R.string.commitments_filter_all),
        CommitmentFilter.GIVE to stringResource(R.string.commitments_filter_give),
        CommitmentFilter.TAKE to stringResource(R.string.commitments_filter_take),
    )
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(filters) { (filter, label) ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelect(filter) },
                label = {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewCommitmentManagementScreenPopulated() {
    BecalmTheme {
        BecalmScaffold(title = "Commitments") { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                FilterChipRow(
                    selectedFilter = CommitmentFilter.ALL,
                    onFilterSelect = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(
                        listOf(
                            "give" to "Send contract draft" to "REMINDED",
                            "take" to "Review budget proposal" to "FOLLOWED_UP",
                        ),
                    ) { (dirTitlePair, status) ->
                        val (dir, title) = dirTitlePair
                        CommitmentCard(
                            title = title,
                            direction = dir,
                            derivedStatus = status,
                            dueAt = Instant.parse("2026-04-20T00:00:00+09:00"),
                            counterpartyDisplayName = "Alice Kim",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
