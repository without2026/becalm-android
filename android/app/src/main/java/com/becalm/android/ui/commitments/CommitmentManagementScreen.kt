package com.becalm.android.ui.commitments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import com.becalm.android.ui.components.SkeletonBlock
import com.becalm.android.ui.components.becalmSkeletonColor
import com.becalm.android.ui.components.commitmentScheduleStatusLabelRes
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.evidence.EvidenceImportFloatingActionButton
import com.becalm.android.ui.evidence.EvidenceImportSheetHost
import com.becalm.android.ui.evidence.EvidenceImportUiState
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.becalm.android.ui.evidence.rememberEvidenceImportSheetController
import com.becalm.android.ui.evidence.rememberEvidenceImportActions
import com.becalm.android.ui.main.MainTabHeaderState
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
    onOpenDetail: (id: String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSources: () -> Unit = onOpenSettings,
    onOpenProcessingStatus: () -> Unit = {},
    onOpenUnassigned: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
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
    LaunchedEffect(evidenceImportState.foregroundReviewRequestKey) {
        val requestKey = evidenceImportState.foregroundReviewRequestKey ?: return@LaunchedEffect
        onOpenUnassigned()
        evidenceImportViewModel.onForegroundReviewOpened(requestKey)
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
        onFilterChange = viewModel::onFilterChange,
        onMessageScreenshotImport = {
            evidenceImportActions.openMessageScreenshotPicker()
        },
        onMeetingAudioImport = evidenceImportActions.openMeetingAudioPicker,
        evidenceImportState = evidenceImportState,
        onMeetingSelfSpeakerSelected = evidenceImportViewModel::onMeetingSelfSpeakerSelected,
        onMeetingCounterpartySpeakerSelected = evidenceImportViewModel::onMeetingCounterpartySpeakerSelected,
        onMeetingSpeakerReviewConfirmed = evidenceImportViewModel::onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = evidenceImportViewModel::onMeetingSpeakerReviewCancelled,
        onMeetingSpeakerReviewAction = evidenceImportViewModel::onMeetingSpeakerReviewAction,
        onMeetingPreviewLoadingCancelled = evidenceImportViewModel::onMeetingPreviewLoadingCancelled,
        onRetryFailedImports = evidenceImportViewModel::onRetryFailedImports,
        onReviewRequiredClick = onOpenUnassigned,
        onStatusDetailsClick = onOpenProcessingStatus,
        onConsentRequiredClick = onOpenSettings,
        onOpenSettings = onOpenSettings,
        onOpenSources = onOpenSources,
        onOpenDetail = viewModel::onCommitmentSelected,
        onToggleConfirmedSection = viewModel::onToggleConfirmedSection,
        onToggleReviewSection = viewModel::onToggleReviewSection,
        onTogglePastSection = viewModel::onTogglePastSection,
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
    onFilterChange: (CommitmentFilter) -> Unit,
    onMessageScreenshotImport: () -> Unit,
    onMeetingAudioImport: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onToggleConfirmedSection: () -> Unit = {},
    onToggleReviewSection: () -> Unit = {},
    onTogglePastSection: () -> Unit = {},
    onToggleCompletedSection: () -> Unit = {},
    onToggleCancelledSection: () -> Unit = {},
    modifier: Modifier = Modifier,
    headerState: MainTabHeaderState = MainTabHeaderState(),
    evidenceImportState: EvidenceImportUiState = EvidenceImportUiState(),
    onMeetingSelfSpeakerSelected: (String) -> Unit = {},
    onMeetingCounterpartySpeakerSelected: (String) -> Unit = {},
    onMeetingSpeakerReviewConfirmed: () -> Unit = {},
    onMeetingSpeakerReviewCancelled: () -> Unit = {},
    onMeetingSpeakerReviewAction: () -> Unit = {},
    onMeetingPreviewLoadingCancelled: () -> Unit = {},
    onRetryFailedImports: () -> Unit = {},
    onReviewRequiredClick: () -> Unit = {},
    onStatusDetailsClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onConsentRequiredClick: () -> Unit = onOpenSettings,
    onOpenSources: () -> Unit = onOpenSettings,
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
	                        val confirmedHeader = stringResource(
	                            R.string.commitment_section_confirmed_fmt,
	                            state.confirmedSection.count,
	                        )
	                        val reviewHeader = stringResource(
	                            R.string.commitment_section_review_fmt,
	                            state.reviewSection.count,
	                        )
	                        val pastHeader = stringResource(
	                            R.string.commitment_section_past_fmt,
	                            state.pastSection.count,
	                        )
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
	                            commitmentBucketSection(
	                                sectionKey = "confirmed",
	                                title = confirmedHeader,
	                                section = state.confirmedSection,
	                                showWhenEmpty = state.filter != CommitmentFilter.CLOSED,
	                                onToggle = onToggleConfirmedSection,
	                                onReviewRequiredClick = onReviewRequiredClick,
	                                onOpenDetail = onOpenDetail,
	                            )

	                            commitmentBucketSection(
	                                sectionKey = "review",
	                                title = reviewHeader,
	                                section = state.reviewSection,
	                                showWhenEmpty = state.filter != CommitmentFilter.CLOSED,
	                                onToggle = onToggleReviewSection,
	                                onReviewRequiredClick = onReviewRequiredClick,
	                                onOpenDetail = onOpenDetail,
	                            )

	                            commitmentBucketSection(
	                                sectionKey = "past",
	                                title = pastHeader,
	                                section = state.pastSection,
	                                showWhenEmpty = state.filter != CommitmentFilter.CLOSED,
	                                onToggle = onTogglePastSection,
	                                onReviewRequiredClick = onReviewRequiredClick,
	                                onOpenDetail = onOpenDetail,
	                            )

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
        onMeetingCounterpartySpeakerSelected = onMeetingCounterpartySpeakerSelected,
        onMeetingSpeakerReviewConfirmed = onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = onMeetingSpeakerReviewCancelled,
        onMeetingSpeakerReviewAction = onMeetingSpeakerReviewAction,
        onMeetingPreviewLoadingCancelled = onMeetingPreviewLoadingCancelled,
        onRetryFailedImports = onRetryFailedImports,
        onReviewRequiredClick = onReviewRequiredClick,
        onStatusDetailsClick = onStatusDetailsClick,
        onConsentRequiredClick = onConsentRequiredClick,
    )
}

/**
 * CMT-013 undo window. Spec pins it at 5 seconds; Material3's [SnackbarDuration.Long]
 * is the closest built-in (~10 s), so the call-site races it against this timeout.
 */
private const val UNDO_WINDOW_MS: Long = 5_000L

@Composable
private fun ScheduleTimelineList(
    rows: List<CommitmentRow>,
    pastSection: CommitmentSectionUiState = CommitmentSectionUiState(),
    pastHeader: String? = null,
    onTogglePastSection: () -> Unit = {},
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (timedRows, untimedRows) = remember(rows) {
        rows.partition { row ->
            val isUntimed = row.scheduleTimelineTiming?.isUntimed ?: (row.dueAt == null)
            !isUntimed
        }
    }
    val (pastTimedRows, pastUntimedRows) = remember(pastSection.items) {
        pastSection.items.partition { row ->
            val isUntimed = row.scheduleTimelineTiming?.isUntimed ?: (row.dueAt == null)
            !isUntimed
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = CommitmentListBottomPadding,
        ),
        modifier = modifier
            .fillMaxSize()
            .testTag("commitment-schedule-timeline"),
    ) {
        scheduleTimelineRows(
            timedRows = timedRows,
            untimedRows = untimedRows,
            keyPrefix = "schedule-timeline",
            dimmed = false,
            onOpenDetail = onOpenDetail,
        )
        if (pastSection.visible) {
            item(key = "schedule-timeline-past-header") {
                ExpandableSectionHeader(
                    title = pastHeader.orEmpty(),
                    expanded = pastSection.expanded,
                    onToggle = onTogglePastSection,
                    modifier = Modifier.testTag("commitment-schedule-past-header"),
                )
            }
            if (pastSection.expanded) {
                scheduleTimelineRows(
                    timedRows = pastTimedRows,
                    untimedRows = pastUntimedRows,
                    keyPrefix = "schedule-timeline-past",
                    dimmed = pastSection.dimmed,
                    onOpenDetail = onOpenDetail,
                )
            }
        }
    }
}

private fun LazyListScope.scheduleTimelineRows(
    timedRows: List<CommitmentRow>,
    untimedRows: List<CommitmentRow>,
    keyPrefix: String,
    dimmed: Boolean,
    onOpenDetail: (String) -> Unit,
) {
    items(
        items = timedRows,
        key = { "$keyPrefix-${it.id}" },
    ) { row ->
        ScheduleTimelineRow(
            row = row,
            onOpenDetail = onOpenDetail,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (dimmed) 0.62f else 1f)
                .padding(vertical = 4.dp),
        )
    }
    if (untimedRows.isNotEmpty()) {
        item(key = "$keyPrefix-untimed-header") {
            ScheduleTimelineSectionHeader(
                text = stringResource(R.string.today_untimed_section),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
        items(
            items = untimedRows,
            key = { "$keyPrefix-untimed-${it.id}" },
        ) { row ->
            ScheduleTimelineRow(
                row = row,
                onOpenDetail = onOpenDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (dimmed) 0.62f else 1f)
                    .padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ScheduleTimelineSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ScheduleTimelineRow(
    row: CommitmentRow,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .heightIn(min = 84.dp)
            .testTag("commitment-schedule-row-${row.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        ScheduleTimelineCard(
            row = row,
            onOpenDetail = onOpenDetail,
            modifier = Modifier.weight(1f),
        )
        ScheduleTimelineRail()
        ScheduleTimelineTimeColumn(
            timing = row.scheduleTimelineTiming,
            modifier = Modifier.testTag("commitment-schedule-time-${row.id}"),
        )
    }
}

@Composable
private fun ScheduleTimelineCard(
    row: CommitmentRow,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val personName = row.counterpartyDisplayName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.commitment_counterparty_unknown)
    val primaryTime = row.scheduleTimelineTiming?.dayLabel
        ?: stringResource(R.string.today_untimed_section)
    val cardDescription = stringResource(
        R.string.commitment_schedule_timeline_description,
        personName,
        row.title,
        primaryTime,
    )
    val markerColor = stablePersonMarkerColor(personName)
    val sourceContext = commitmentSourceContextLabel(row)

    EvidenceCard(
        modifier = modifier
            .semantics {
                role = Role.Button
                contentDescription = cardDescription
            }
            .clickable { onOpenDetail(row.id) }
            .testTag("commitment-schedule-card-${row.id}"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(markerColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = personName.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = personName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = scheduleStatusLabel(row.scheduleStatus),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = row.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (sourceContext != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = sourceContext,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ScheduleTimelineRail(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .width(18.dp)
            .heightIn(min = 84.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .width(2.dp)
                .weight(1f)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun ScheduleTimelineTimeColumn(
    timing: ScheduleTimelineTiming?,
    modifier: Modifier = Modifier,
) {
    val primary = timing?.dayLabel ?: stringResource(R.string.today_untimed_section)
    Column(
        modifier = modifier
            .width(76.dp)
            .padding(top = 10.dp, start = 8.dp),
    ) {
        Text(
            text = primary,
            style = MaterialTheme.typography.labelMedium,
            color = if (timing?.isUntimed == true || timing?.dayLabel == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        timing?.timeLabel?.let { time ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun stablePersonMarkerColor(label: String): Color {
    val scheme = MaterialTheme.colorScheme
    return remember(label, scheme.primary, scheme.secondary, scheme.tertiary, scheme.error) {
        val palette = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error)
        val hash = label.fold(0) { acc, char -> ((acc * 31) + char.code) and Int.MAX_VALUE }
        palette[hash % palette.size].copy(alpha = 0.78f)
    }
}

@Composable
private fun scheduleStatusLabel(status: String?): String =
    commitmentScheduleStatusLabelRes(status)?.let { stringResource(it) }
        ?: stringResource(R.string.commitment_item_type_schedule)

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

private fun LazyListScope.commitmentBucketSection(
    sectionKey: String,
    title: String,
    section: CommitmentSectionUiState,
    showWhenEmpty: Boolean,
    onToggle: () -> Unit,
    onReviewRequiredClick: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    if (!section.visible && !showWhenEmpty) return
    item(key = "header-$sectionKey") {
        ExpandableSectionHeader(
            title = title,
            expanded = section.expanded,
            onToggle = onToggle,
        )
    }
    if (!section.expanded) return

    buildCommitmentPersonGroups(section.items).forEach { group ->
        item(key = "$sectionKey-group-${group.stableKey}") {
            CommitmentPersonGroupHeader(
                group = group,
                onReviewRequiredClick = onReviewRequiredClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
            )
        }
        items(
            items = group.items,
            key = { "$sectionKey-${it.id}" },
        ) { row ->
            CommitmentRowCard(
                row = if (section.dimmed) row.copy(deEmphasized = true) else row,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

@Composable
private fun CommitmentPersonGroupHeader(
    group: CommitmentPersonGroup,
    onReviewRequiredClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = group.displayName ?: when (group.type) {
        CommitmentPersonGroupType.SCHEDULE -> stringResource(R.string.commitments_schedule_group_title)
        CommitmentPersonGroupType.PERSON,
        CommitmentPersonGroupType.UNKNOWN_PERSON,
        -> stringResource(R.string.commitment_counterparty_unknown)
    }
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
        if (group.type == CommitmentPersonGroupType.UNKNOWN_PERSON) {
            TextButton(onClick = onReviewRequiredClick) {
                Text(text = stringResource(R.string.person_matching_required_banner_action))
            }
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
