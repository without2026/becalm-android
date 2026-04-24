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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.ExpandableSectionHeader
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.launch

/**
 * Navigates from the person detail screen to a raw event drill-down. Scoped to
 * this file so the per-branch plumbing through `interactionSection` stays
 * readable; passed in as a parameter rather than closed-over so the recomposition
 * key is stable.
 */
private typealias OnEventTap = (eventId: String) -> Unit

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
    HandleSnackbarMessage(state.error, snackbarHostState, viewModel::onErrorDismissed)

    val onEventTap: OnEventTap = { eventId ->
        navController.navigate(
            BecalmRoute.RawEventDetail(personId = personId, eventId = eventId).path,
        )
    }

    PersonDetailScreenContent(
        state = state,
        title = state.displayName ?: personId.take(16),
        snackbarHostState = snackbarHostState,
        onBack = navController::popBackStack,
        onEventTap = onEventTap,
        onToggleCompletedExpanded = viewModel::onToggleCompletedExpanded,
    )
}

@Composable
public fun PersonDetailScreenContent(
    state: PersonDetailUiState,
    title: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEventTap: (String) -> Unit,
    onToggleCompletedExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BecalmScaffold(
        modifier = modifier,
        title = title,
        navigationIcon = {
            IconButton(onClick = onBack) {
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
            else -> PersonDetailList(
                state = state,
                padding = padding,
                onEventTap = onEventTap,
                onToggleCompletedExpanded = onToggleCompletedExpanded,
            )
        }
    }
}

// ─── Content branches ─────────────────────────────────────────────────────────

@Composable
private fun PersonDetailEmpty(state: PersonDetailUiState, padding: PaddingValues) {
    LazyColumn(
        contentPadding = padding,
        modifier = Modifier
            .fillMaxSize()
            .testTag("person-detail-list"),
    ) {
        item(key = "header") {
            PersonHeader(
                displayName = state.displayName,
                nickname = state.nickname,
                companyName = state.companyName,
                jobTitle = state.jobTitle,
                personRef = state.personRef,
                eventCount = state.eventCount,
                pendingCommitmentCount = state.pendingCommitmentCount,
            )
        }
        item(key = "empty") {
            EmptyState(title = stringResource(R.string.person_detail_empty_interactions))
        }
    }
}

@Composable
private fun PersonDetailList(
    state: PersonDetailUiState,
    padding: PaddingValues,
    onEventTap: (String) -> Unit,
    onToggleCompletedExpanded: () -> Unit,
) {
    val pendingHeader = stringResource(
        R.string.person_detail_section_pending_fmt,
        state.pendingCommitments.size,
    )
    val completedHeader = stringResource(
        R.string.person_detail_section_completed_fmt,
        state.completedCommitments.size,
    )
    val historyHeader = stringResource(R.string.person_detail_history_section)

    LazyColumn(
        contentPadding = padding,
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            PersonHeader(
                displayName = state.displayName,
                nickname = state.nickname,
                companyName = state.companyName,
                jobTitle = state.jobTitle,
                personRef = state.personRef,
                eventCount = state.eventCount,
                pendingCommitmentCount = state.pendingCommitmentCount,
            )
        }
        pendingCommitmentsSection(
            header = pendingHeader,
            rows = state.pendingCommitments,
            onEventTap = onEventTap,
        )
        completedCommitmentsSection(
            header = completedHeader,
            rows = state.completedCommitments,
            expanded = state.completedExpanded,
            onToggleExpanded = onToggleCompletedExpanded,
            onEventTap = onEventTap,
        )
        historySection(
            header = historyHeader,
            rows = state.interactionHistory,
            onEventTap = onEventTap,
        )
    }
}

// ─── LazyListScope section builders ───────────────────────────────────────────

/**
 * Section 1 — pending commitments. Eagerly visible. No-op when empty.
 */
private fun LazyListScope.pendingCommitmentsSection(
    header: String,
    rows: List<InteractionRow.Commitment>,
    onEventTap: OnEventTap,
) {
    if (rows.isEmpty()) return
    item(key = "header-pending") { SectionHeader(text = header) }
    items(
        items = rows,
        key = { row -> "cp-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}" },
    ) { row ->
        InteractionHistoryRow(
            row = row,
            onEventTap = onEventTap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * Section 2 — completed commitments. Rendered as [ExpandableSectionHeader] and
 * default-collapsed per SRC-008 "접힌 상태 기본. 탭 시 … 펼쳐짐". No-op when empty.
 */
private fun LazyListScope.completedCommitmentsSection(
    header: String,
    rows: List<InteractionRow.Commitment>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEventTap: OnEventTap,
) {
    if (rows.isEmpty()) return
    item(key = "header-completed") {
        ExpandableSectionHeader(
            title = header,
            expanded = expanded,
            onToggle = onToggleExpanded,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    if (!expanded) return
    items(
        items = rows,
        key = { row -> "cd-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}" },
    ) { row ->
        InteractionHistoryRow(
            row = row,
            onEventTap = onEventTap,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/**
 * Section 3 — interaction history (raw events + calendar meetings, newest first).
 * Always visible. No-op when empty.
 */
private fun LazyListScope.historySection(
    header: String,
    rows: List<InteractionRow>,
    onEventTap: OnEventTap,
) {
    if (rows.isEmpty()) return
    item(key = "header-history") { SectionHeader(text = header) }
    items(
        items = rows,
        key = { row ->
            when (row) {
                is InteractionRow.Event -> "e-${row.id}"
                is InteractionRow.Commitment -> "c-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}"
                is InteractionRow.CalendarMeeting -> "m-${row.timestamp.toEpochMilliseconds()}-${row.title.hashCode()}"
            }
        },
    ) { row ->
        InteractionHistoryRow(
            row = row,
            onEventTap = onEventTap,
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
                        nickname = "Alice",
                        companyName = "Acme Corp",
                        jobTitle = "Product Lead",
                        personRef = "alice@acme.com",
                        eventCount = 2,
                        pendingCommitmentCount = 1,
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
                        onEventTap = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
