package com.becalm.android.ui.commitments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.becalm.android.R
import com.becalm.android.core.util.KST
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CommitmentCard
import com.becalm.android.ui.components.CommitmentWire
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.ExpandableSectionHeader
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.MainTabHeaderActions
import com.becalm.android.ui.components.MainTabStatusHeader
import com.becalm.android.ui.components.SkeletonBlock
import com.becalm.android.ui.components.becalmSkeletonColor
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.evidence.EvidenceImportFloatingActionButton
import com.becalm.android.ui.evidence.EvidenceImportSheetHost
import com.becalm.android.ui.evidence.EvidenceImportUiState
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.becalm.android.ui.evidence.rememberEvidenceImportSheetController
import com.becalm.android.ui.evidence.rememberEvidenceImportActions
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.MainTabHeaderViewModel
import com.becalm.android.ui.navigation.dispatchCommitmentManagementNavigation
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

private val CommitmentListBottomPadding = 144.dp

/**
 * Commitment management screen — full list with filter tabs.
 *
 * Filter tabs: 전체 / 액션 / 내가 한 / 상대가 한 / 일정 / 결정.
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
    evidenceImportViewModel: EvidenceImportViewModel = hiltViewModel(),
    headerViewModel: MainTabHeaderViewModel = hiltViewModel(),
    onOpenDetail: (id: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenUnassigned: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
    val headerState by headerViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullRefreshState(
        refreshing = state.refreshing,
        onRefresh = viewModel::onPullRefresh,
    )

    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)
    val importMessage = evidenceImportState.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(importMessage, snackbarHostState, evidenceImportViewModel::onMessageShown)

    val evidenceImportActions = rememberEvidenceImportActions(evidenceImportViewModel)

    // CMT-013 — collect one-shot undo snapshots emitted by [onComplete] / [onCancel]
    // and present a `[복구]` snackbar with a 5 s window. Material3 does not expose a
    // 5 s SnackbarDuration token (Short ≈ 4 s, Long ≈ 10 s), so we race the Long
    // snackbar against an explicit 5 s timeout via [withTimeoutOrNull]. When the
    // user taps the action before the timeout fires we see [SnackbarResult.ActionPerformed]
    // and invoke [onUndo]; a timeout or dismissal leaves the terminal state as-is.
    val undoCompletedMessage = stringResource(R.string.commitment_undo_completed)
    val undoCancelledMessage = stringResource(R.string.commitment_undo_cancelled)
    val undoActionLabel = stringResource(R.string.commitment_undo_action)
    CollectFlowEffect(viewModel.undoFlow) { snapshot ->
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

    CollectFlowEffect(viewModel.navigation) { nav ->
        dispatchCommitmentManagementNavigation(nav, onOpenDetail)
    }

    CommitmentManagementScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        pullState = pullState,
        headerState = headerState,
        onFilterChange = viewModel::onFilterChange,
        onMessageScreenshotImport = {
            evidenceImportActions.openMessageScreenshotPicker()
        },
        onMeetingAudioImport = evidenceImportActions.openMeetingAudioPicker,
        evidenceImportState = evidenceImportState,
        onMeetingSelfSpeakerSelected = evidenceImportViewModel::onMeetingSelfSpeakerSelected,
        onMeetingSpeakerReviewConfirmed = evidenceImportViewModel::onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = evidenceImportViewModel::onMeetingSpeakerReviewCancelled,
        onReviewRequiredClick = onOpenUnassigned,
        onOpenSettings = onOpenSettings,
        onOpenDetail = viewModel::onCommitmentSelected,
        onToggleCompletedSection = viewModel::onToggleCompletedSection,
        onToggleCancelledSection = viewModel::onToggleCancelledSection,
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
public fun CommitmentManagementScreenContent(
    state: CommitmentUiState,
    snackbarHostState: SnackbarHostState,
    pullState: androidx.compose.material.pullrefresh.PullRefreshState,
    headerState: MainTabHeaderState = MainTabHeaderState(),
    onFilterChange: (CommitmentFilter) -> Unit,
    onMessageScreenshotImport: () -> Unit,
    onMeetingAudioImport: () -> Unit,
    evidenceImportState: EvidenceImportUiState = EvidenceImportUiState(),
    onMeetingSelfSpeakerSelected: (String) -> Unit = {},
    onMeetingSpeakerReviewConfirmed: () -> Unit = {},
    onMeetingSpeakerReviewCancelled: () -> Unit = {},
    onReviewRequiredClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDetail: (String) -> Unit,
    onToggleCompletedSection: () -> Unit,
    onToggleCancelledSection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val evidenceImportController = rememberEvidenceImportSheetController()
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.commitments_title),
        actions = {
            MainTabHeaderActions(
                onOpenSettings = onOpenSettings,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            EvidenceImportFloatingActionButton(onClick = evidenceImportController::openSheet)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MainTabStatusHeader(state = headerState)
            FilterChipRow(
                selectedFilter = state.filter,
                onFilterSelect = onFilterChange,
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
                        CommitmentListSkeleton()
                    }
                    state.items.isEmpty() -> {
                        EmptyState(
                            title = stringResource(R.string.commitments_empty_title),
                            message = stringResource(R.string.commitments_empty_message),
                        )
                    }
                    else -> {
                        val activePersonGroups = remember(state.activeItems) {
                            state.activePersonGroups
                        }
                        val completedHeader = stringResource(
                            R.string.commitment_section_completed_fmt,
                            state.completedSection.count,
                        )
                        val cancelledHeader = stringResource(
                            R.string.commitment_section_cancelled_fmt,
                            state.cancelledSection.count,
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 8.dp,
                                end = 16.dp,
                                bottom = CommitmentListBottomPadding,
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("commitment-list"),
                        ) {
                            activePersonGroups.forEach { group ->
                                item(key = "active-group-${group.stableKey}") {
                                    CommitmentPersonGroupHeader(
                                        group = group,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp, bottom = 4.dp),
                                    )
                                }
                                items(
                                    items = group.items,
                                    key = { "active-${it.id}" },
                                ) { row ->
                                    CommitmentRowCard(
                                        row = row,
                                        onOpenDetail = onOpenDetail,
                                    )
                                }
                            }

                            if (state.completedSection.visible) {
                                item(key = "header-completed") {
                                    ExpandableSectionHeader(
                                        title = completedHeader,
                                        expanded = state.completedSection.expanded,
                                        onToggle = onToggleCompletedSection,
                                    )
                                }
                                if (state.completedSection.expanded) {
                                    items(
                                        items = state.completedSection.items,
                                        key = { "completed-${it.id}" },
                                    ) { row ->
                                        CommitmentRowCard(
                                            row = row,
                                            onOpenDetail = onOpenDetail,
                                        )
                                    }
                                }
                            }

                            if (state.cancelledSection.visible) {
                                item(key = "header-cancelled") {
                                    ExpandableSectionHeader(
                                        title = cancelledHeader,
                                        expanded = state.cancelledSection.expanded,
                                        onToggle = onToggleCancelledSection,
                                    )
                                }
                                if (state.cancelledSection.expanded) {
                                    items(
                                        items = state.cancelledSection.items,
                                        key = { "cancelled-${it.id}" },
                                    ) { row ->
                                        CommitmentRowCard(
                                            row = row,
                                            onOpenDetail = onOpenDetail,
                                        )
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

    EvidenceImportSheetHost(
        controller = evidenceImportController,
        onMessageScreenshotImport = onMessageScreenshotImport,
        onMeetingAudioImport = onMeetingAudioImport,
        state = evidenceImportState,
        onMeetingSelfSpeakerSelected = onMeetingSelfSpeakerSelected,
        onMeetingSpeakerReviewConfirmed = onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = onMeetingSpeakerReviewCancelled,
        onReviewRequiredClick = onReviewRequiredClick,
    )
}

/**
 * CMT-013 undo window. Spec pins it at 5 seconds; Material3's [SnackbarDuration.Long]
 * is the closest built-in (~10 s), so the call-site races it against this timeout.
 */
private const val UNDO_WINDOW_MS: Long = 5_000L

/**
 * Static placeholder rows shown during the cold-start no-data window.
 * Mirrors the [CommitmentCard] geometry (glass panel + avatar circle + title
 * row + footer pill row) so real cards land in place without layout pop.
 * No motion — DESIGN.md Process-Hidden Rule (first-line surface).
 */
@Composable
private fun CommitmentListSkeleton(modifier: Modifier = Modifier) {
    val avatarColor = becalmSkeletonColor()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = CommitmentListBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(count = 3, key = { index -> "commitments-skeleton-$index" }) {
            EvidenceCard(
                modifier = Modifier
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(avatarColor),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            SkeletonBlock(modifier = Modifier.fillMaxWidth(0.45f).height(10.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            SkeletonBlock(modifier = Modifier.fillMaxWidth(0.3f).height(8.dp))
                        }
                        SkeletonBlock(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(36.dp)
                                .height(18.dp),
                        )
                    }
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f).height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SkeletonBlock(modifier = Modifier.width(48.dp).height(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        SkeletonBlock(modifier = Modifier.width(56.dp).height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitmentPersonGroupHeader(
    group: CommitmentPersonGroup,
    modifier: Modifier = Modifier,
) {
    val displayName = group.displayName ?: stringResource(R.string.commitment_counterparty_unknown)
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.commitments_person_group_count_fmt, group.count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        itemType = row.itemType,
        title = row.title,
        direction = row.direction,
        scheduleStatus = row.scheduleStatus,
        decisionStatus = row.decisionStatus,
        derivedStatus = row.derivedStatus,
        dueAt = row.dueAt,
        counterpartyDisplayName = row.counterpartyDisplayName,
        dueIsApproximate = row.dueIsApproximate,
        dueHint = row.dueHint,
        isManual = row.isManual,
        sourceContextLabel = commitmentSourceContextLabel(row),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (row.deEmphasized) 0.62f else 1f)
            .padding(vertical = 4.dp),
        // C4 wiring — card tap opens the CommitmentDetailSheet route
        // (see BecalmRoute.CommitmentDetail). Action buttons live inside the sheet;
        // onMarkDone stays unset so the card stays visually minimal.
        onClick = { onOpenDetail(row.id) },
    )
}

@Composable
private fun commitmentSourceContextLabel(row: CommitmentRow): String? {
    if (row.isManual) return stringResource(R.string.commitment_source_manual)
    val source = row.sourceTitle?.takeIf { it.isNotBlank() }
        ?: row.sourceType?.let { readableSourceType(it) }
        ?: return null
    val date = row.sourceOccurredAt?.toMonthDayLabel()
    return if (date == null) {
        source
    } else {
        stringResource(R.string.commitment_source_context_fmt, date, source)
    }
}

@Composable
private fun readableSourceType(sourceType: String): String =
    stringResource(sourcePresentationFor(sourceType).labelRes)

private fun Instant.toMonthDayLabel(): String {
    val date = toLocalDateTime(KST).date
    return "${date.monthNumber}/${date.dayOfMonth}"
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
        CommitmentFilter.SCHEDULE to stringResource(R.string.commitments_filter_schedule),
        CommitmentFilter.CLOSED to stringResource(R.string.commitments_filter_closed),
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
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("commitment-filter-${filter.name.lowercase()}"),
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
                            CommitmentWire.DIRECTION_GIVE to "Send contract draft" to CommitmentWire.ACTION_REMINDED_UPPER,
                            CommitmentWire.DIRECTION_TAKE to "Review budget proposal" to CommitmentWire.ACTION_FOLLOWED_UPPER,
                        ),
                    ) { (dirTitlePair, status) ->
                        val (dir, title) = dirTitlePair
                        CommitmentCard(
                            itemType = "action",
                            title = title,
                            direction = dir,
                            scheduleStatus = null,
                            decisionStatus = null,
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
