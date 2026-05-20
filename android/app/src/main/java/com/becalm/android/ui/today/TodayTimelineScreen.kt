package com.becalm.android.ui.today

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.data.repository.isActive
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.CounterpartyText
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.MainTabHeaderActions
import com.becalm.android.ui.components.MainTabStatusHeader
import com.becalm.android.ui.components.RelationshipCard
import com.becalm.android.ui.components.SkeletonBlock
import com.becalm.android.ui.components.becalmSkeletonColor
import com.becalm.android.ui.components.TimestampText
import com.becalm.android.ui.components.commitmentActionLabelRes
import com.becalm.android.ui.components.isCalendarSource
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.evidence.EvidenceImportFloatingActionButton
import com.becalm.android.ui.evidence.EvidenceImportSheetHost
import com.becalm.android.ui.evidence.EvidenceImportUiState
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.becalm.android.ui.evidence.rememberEvidenceImportSheetController
import com.becalm.android.ui.evidence.rememberEvidenceImportActions
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.dispatchTodayEffect
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Today screen — unified calendar events + due commitments timeline.
 *
 * Renders a time-sorted list of [TimelineItem] for today. Shows a loading
 * spinner while [TodayUiState.loading] is true. Shows [ErrorState] when
 * [TodayUiState.error] is non-null (e.g. unauthenticated).
 *
 * Composition (TDY-003 / TDY-006 / TDY-008 / TDY-009):
 * ```
 * BecalmScaffold(title=Today)
 *   OverallSyncIndicator(state)            // TDY-008 banner
 *   SourceStatusStrip(chips)               // TDY-003 read-only chips (no tap)
 *   PullRefreshIndicator + TimelineList    // TDY-006 pull-to-refresh + TDY-009 catch-up
 * ```
 *
 * spec: TDY-001..TDY-009
 *
 * Primary VM: [TodayViewModel]
 * Navigation entry: [BecalmRoute.Today]
 * Navigation exit: [BecalmRoute.Settings] (via top-right icon)
 */
@Composable
public fun TodayTimelineScreen(
    navController: NavHostController,
    viewModel: TodayViewModel = hiltViewModel(),
    evidenceImportViewModel: EvidenceImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val importMessage = evidenceImportState.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(importMessage, snackbarHostState, evidenceImportViewModel::onMessageShown)
    val todayMessage = state.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(todayMessage, snackbarHostState, viewModel::onMessageShown)

    val evidenceImportActions = rememberEvidenceImportActions(evidenceImportViewModel)

    CollectFlowEffect(viewModel.effects) { effect ->
        navController.dispatchTodayEffect(effect)
    }

    TodayTimelineContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenSettings = viewModel::onOpenSettings,
        onOpenSources = {
            navController.navigate(BecalmRoute.SettingsSources.path) {
                launchSingleTop = true
            }
        },
        onOpenProcessingStatus = {
            navController.navigate(BecalmRoute.ProcessingStatus.path)
        },
        onPullRefresh = viewModel::onPullRefresh,
        onResolveScheduleConflict = viewModel::onResolveScheduleConflict,
        onOpenCommitmentDetail = { commitmentId ->
            navController.navigate(BecalmRoute.CommitmentDetail(commitmentId).path)
        },
        onAddDueTime = { commitmentId ->
            navController.navigate(BecalmRoute.CommitmentEdit(commitmentId).path)
        },
        onMessageScreenshotImport = evidenceImportActions.openMessageScreenshotPicker,
        onMeetingAudioImport = evidenceImportActions.openMeetingAudioPicker,
        evidenceImportState = evidenceImportState,
        onMeetingSelfSpeakerSelected = evidenceImportViewModel::onMeetingSelfSpeakerSelected,
        onMeetingSpeakerReviewConfirmed = evidenceImportViewModel::onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = evidenceImportViewModel::onMeetingSpeakerReviewCancelled,
        onMeetingPreviewLoadingCancelled = evidenceImportViewModel::onMeetingPreviewLoadingCancelled,
        onReviewRequiredClick = {
            navController.navigate(BecalmRoute.PersonsUnassigned.path)
        },
    )
}

/**
 * Stateless Today screen content.
 *
 * Hoisted from [TodayTimelineScreen] per rubric D1 so Compose UI tests can drive the
 * rendered tree with a raw [TodayUiState] + lambdas, without booting a ViewModel.
 */
@OptIn(ExperimentalMaterialApi::class)
@Composable
public fun TodayTimelineContent(
    state: TodayUiState,
    onOpenSettings: () -> Unit,
    onOpenSources: () -> Unit = onOpenSettings,
    onPullRefresh: () -> Unit,
    onResolveScheduleConflict: (String, String) -> Unit = { _, _ -> },
    onOpenProcessingStatus: () -> Unit = {},
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenCommitmentDetail: (String) -> Unit = {},
    onAddDueTime: (String) -> Unit = {},
    onMessageScreenshotImport: () -> Unit = {},
    onMeetingAudioImport: () -> Unit = {},
    evidenceImportState: EvidenceImportUiState = EvidenceImportUiState(),
    onMeetingSelfSpeakerSelected: (String) -> Unit = {},
    onMeetingSpeakerReviewConfirmed: () -> Unit = {},
    onMeetingSpeakerReviewCancelled: () -> Unit = {},
    onMeetingPreviewLoadingCancelled: () -> Unit = {},
    onReviewRequiredClick: () -> Unit = {},
) {
    val evidenceImportController = rememberEvidenceImportSheetController()
    val pullState = rememberPullRefreshState(
        refreshing = state.refreshing,
        onRefresh = onPullRefresh,
    )
    val headerState = MainTabHeaderState(
        sourceStatus = state.sourceStatus,
        overallSyncing = state.overallSyncing,
        overall = state.overall,
    )

    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.today_title),
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
        // Single-column calm on every viewport: cap content at the timeline
        // reading width and centre on tablets / foldables. Below the cap,
        // fills available width on phone.
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = TimelineMaxContentWidth),
            ) {
                if (state.processingPaused) {
                    EvidenceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.processing_paused_banner),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                MainTabStatusHeader(
                    state = headerState,
                    onOpenSettings = onOpenSettings,
                    onOpenSources = onOpenSources,
                )
                TodayProcessingStatusStrip(
                    status = state.processingStatus,
                    onOpenProcessingStatus = onOpenProcessingStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ScheduleConflictReviewPanel(
                    items = state.scheduleConflictReviewItems,
                    onResolveScheduleConflict = onResolveScheduleConflict,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pullRefresh(pullState),
                ) {
                    when {
                        state.loading -> {
                            TimelineSkeleton()
                        }
                        state.error != null -> {
                            ErrorState(
                                title = stringResource(R.string.error_generic_title),
                                message = uiMessageStringResource(requireNotNull(state.error)),
                            )
                        }
                        state.timeline.isEmpty() -> {
                            EmptyState(
                                title = stringResource(R.string.today_empty_title),
                                message = stringResource(R.string.today_empty_message),
                            )
                        }
                        else -> {
                            TimelineList(
                                items = state.timeline,
                                personFocus = state.personFocus,
                                onOpenCommitmentDetail = onOpenCommitmentDetail,
                                onAddDueTime = onAddDueTime,
                                onReviewRequiredClick = onReviewRequiredClick,
                                contentPadding = PaddingValues(vertical = 4.dp),
                            )
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

    EvidenceImportSheetHost(
        controller = evidenceImportController,
        onMessageScreenshotImport = onMessageScreenshotImport,
        onMeetingAudioImport = onMeetingAudioImport,
        state = evidenceImportState,
        onMeetingSelfSpeakerSelected = onMeetingSelfSpeakerSelected,
        onMeetingSpeakerReviewConfirmed = onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = onMeetingSpeakerReviewCancelled,
        onMeetingPreviewLoadingCancelled = onMeetingPreviewLoadingCancelled,
        onReviewRequiredClick = onReviewRequiredClick,
    )
}

/** Reading-width cap for the Today timeline. Below this, content fills the
 *  available width on phones; at or above (tablet, foldable open), the column
 *  centres in the viewport so the single-column calm holds on every device. */
private val TimelineMaxContentWidth: Dp = 600.dp

/**
 * Static skeleton placeholder rows shown during the cold-start no-data window.
 *
 * Matches the timeline row geometry (84dp min height, evidence card body,
 * rail, time column) so real data lands in place without layout pop. No
 * animation: motion is intentional only in this system, and a "loading
 * shimmer" reads as process-noise on the first-line surface (DESIGN.md
 * Process-Hidden Rule). Three rows is enough to communicate "list, loading"
 * without padding the screen with placeholders.
 */
@Composable
private fun TimelineSkeleton(modifier: Modifier = Modifier) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(count = 3, key = { index -> "timeline-skeleton-$index" }) {
            TimelineSkeletonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TodayProcessingStatusStrip(
    status: TodayProcessingStatusUi,
    onOpenProcessingStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!status.visible) return
    val phaseLabel = status.latestPhase?.let { phase ->
        stringResource(todayProcessingPhaseLabelRes(phase))
    }
    val recentLabel = when {
        phaseLabel != null && status.latestUpdatedAt != null -> {
            "$phaseLabel · ${formatKstTime(status.latestUpdatedAt)}"
        }
        phaseLabel != null -> phaseLabel
        else -> ""
    }
    val label = when {
        status.activeItemCount > 0 -> stringResource(
            R.string.today_processing_active_items_fmt,
            status.activeItemCount,
        )
        status.activeCount > 0 -> stringResource(
            R.string.today_processing_active_fmt,
            status.activeCount,
        )
        status.actionCount > 0 -> stringResource(
            R.string.today_processing_action_fmt,
            status.actionCount,
        )
        else -> stringResource(R.string.today_processing_recent_fmt, recentLabel)
    }
    val indicatorColor = when {
        status.actionCount > 0 -> MaterialTheme.colorScheme.error
        status.latestPhase?.isActive == true -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    EvidenceCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenProcessingStatus) {
                Text(text = stringResource(R.string.today_processing_open))
            }
        }
    }
}

@Composable
private fun ScheduleConflictReviewPanel(
    items: List<ScheduleConflictReviewItem>,
    onResolveScheduleConflict: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = items.firstOrNull() ?: return
    EvidenceCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.today_schedule_conflict_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (items.size > 1) {
                    stringResource(R.string.today_schedule_conflict_count_fmt, items.size)
                } else {
                    stringResource(R.string.today_schedule_conflict_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScheduleConflictValueColumn(
                    label = stringResource(R.string.today_schedule_conflict_calendar),
                    title = item.calendarTitle,
                    time = item.calendarStartAt,
                    status = item.calendarStatus,
                    modifier = Modifier.weight(1f),
                )
                ScheduleConflictValueColumn(
                    label = stringResource(sourceTypeLabelRes(item.sourceType)),
                    title = item.sourceTitle,
                    time = item.sourceStartAt,
                    status = item.sourceStatus,
                    modifier = Modifier.weight(1f),
                )
            }
            item.evidence?.takeIf { it.isNotBlank() }?.let { evidence ->
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onResolveScheduleConflict(item.linkId, ScheduleEventLinkResolutionChoice.SAME_SCHEDULE) }) {
                        Text(text = stringResource(R.string.today_schedule_conflict_same_schedule))
                    }
                    TextButton(onClick = { onResolveScheduleConflict(item.linkId, ScheduleEventLinkResolutionChoice.SCHEDULE_ADJUSTMENT_NEEDED) }) {
                        Text(text = stringResource(R.string.today_schedule_conflict_adjust_needed))
                    }
                }
                TextButton(onClick = { onResolveScheduleConflict(item.linkId, ScheduleEventLinkResolutionChoice.KEEP_BOTH) }) {
                    Text(text = stringResource(R.string.today_schedule_conflict_keep_both))
                }
            }
        }
    }
}

@Composable
private fun ScheduleConflictValueColumn(
    label: String,
    title: String,
    time: Instant?,
    status: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = title.ifBlank { stringResource(R.string.today_schedule_conflict_missing_title) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            time?.let(::formatKstTime),
            status?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TimelineSkeletonRow(modifier: Modifier = Modifier) {
    val railColor = becalmSkeletonColor()
    Row(
        modifier = modifier.heightIn(min = 84.dp),
        verticalAlignment = Alignment.Top,
    ) {
        EvidenceCard(
            modifier = Modifier
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.4f).height(10.dp))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.85f).height(14.dp))
                SkeletonBlock(modifier = Modifier.fillMaxWidth(0.55f).height(10.dp))
            }
        }
        Column(
            modifier = Modifier
                .width(18.dp)
                .heightIn(min = 84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(railColor),
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(railColor),
            )
        }
        SkeletonBlock(
            modifier = Modifier
                .width(54.dp)
                .padding(top = 12.dp, start = 8.dp)
                .height(12.dp),
        )
    }
}

@Composable
private fun TimelineList(
    items: List<TimelineItem>,
    personFocus: List<TodayPersonFocus>,
    onOpenCommitmentDetail: (String) -> Unit,
    onAddDueTime: (String) -> Unit,
    onReviewRequiredClick: () -> Unit,
    contentPadding: PaddingValues,
) {
    val (timedItems, untimedItems) = remember(items) { items.partition { it.isTimed } }
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (personFocus.isNotEmpty()) {
            item(key = "today-person-focus") {
                TodayPersonFocusPanel(
                    people = personFocus,
                    onReviewRequiredClick = onReviewRequiredClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        if (timedItems.isNotEmpty()) {
            item(key = "today-timed-header") {
                TimelineSectionHeader(
                    text = stringResource(R.string.today_timed_section),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(
            items = timedItems,
            key = { item ->
                when (item) {
                    is TimelineItem.Commitment -> "commitment-${item.id}"
                    is TimelineItem.CalendarEvent -> "event-${item.id}"
                    is TimelineItem.Meeting -> "meeting-${item.id}"
                }
            },
        ) { item ->
            TimelineItemRow(
                item = item,
                onOpenCommitmentDetail = onOpenCommitmentDetail,
                onAddDueTime = onAddDueTime,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (untimedItems.isNotEmpty()) {
            item(key = "today-untimed-header") {
                TimelineSectionHeader(
                    text = stringResource(R.string.today_untimed_section),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(
                items = untimedItems,
                key = { item ->
                    when (item) {
                        is TimelineItem.Commitment -> "untimed-commitment-${item.id}"
                        is TimelineItem.CalendarEvent -> "untimed-event-${item.id}"
                        is TimelineItem.Meeting -> "untimed-meeting-${item.id}"
                    }
                },
            ) { item ->
                TimelineItemRow(
                    item = item,
                    onOpenCommitmentDetail = onOpenCommitmentDetail,
                    onAddDueTime = onAddDueTime,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TimelineSectionHeader(
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
private fun TodayPersonFocusPanel(
    people: List<TodayPersonFocus>,
    onReviewRequiredClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalCommitments = people.sumOf { it.commitmentCount }
    RelationshipCard(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.today_person_focus_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.today_person_focus_subtitle_fmt, people.size, totalCommitments),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            people.forEach { person ->
                TodayPersonFocusRow(
                    person = person,
                    onReviewRequiredClick = onReviewRequiredClick,
                )
            }
        }
    }
}

@Composable
private fun TodayPersonFocusRow(
    person: TodayPersonFocus,
    onReviewRequiredClick: () -> Unit,
) {
    val label = person.displayName ?: stringResource(R.string.today_counterparty_unknown)
    val isUnknown = person.displayName.isNullOrBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isUnknown) Modifier.clickable(onClick = onReviewRequiredClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // Match CommitmentCard's avatar fallback: first letter only,
                // so emoji- / symbol-prefixed names render a legible initial.
                text = label.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isUnknown) {
            TextButton(onClick = onReviewRequiredClick) {
                Text(text = stringResource(R.string.person_matching_required_banner_action))
            }
        } else {
            Text(
                text = stringResource(
                    R.string.today_person_focus_commitments_fmt,
                    person.commitmentCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineItemRow(
    item: TimelineItem,
    onOpenCommitmentDetail: (String) -> Unit,
    onAddDueTime: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.heightIn(min = 84.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TimelineCard(
            item = item,
            onOpenCommitmentDetail = onOpenCommitmentDetail,
            onAddDueTime = onAddDueTime,
            modifier = Modifier.weight(1f),
        )
        TimelineRail()
        TimelineTimeColumn(item = item)
    }
}

@Composable
private fun TimelineTimeColumn(
    item: TimelineItem,
    modifier: Modifier = Modifier,
) {
    Text(
        text = when {
            item.isCalendarAllDay() -> stringResource(R.string.today_all_day)
            else -> item.timelineAt?.let(::formatKstTime) ?: stringResource(R.string.today_no_due_time)
        },
        modifier = modifier
            .width(54.dp)
            .padding(top = 12.dp, start = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (item.isTimed || item.isCalendarAllDay()) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun TimelineRail(modifier: Modifier = Modifier) {
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
private fun TimelineCard(
    item: TimelineItem,
    onOpenCommitmentDetail: (String) -> Unit,
    onAddDueTime: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeLabel = typeLabelFor(item)
    val counterpartyLabel = if (item is TimelineItem.Commitment) {
        item.counterpartyDisplayName?.takeIf { it.isNotBlank() }
            ?: if (item.itemType != CommitmentItemType.SCHEDULE) {
                stringResource(R.string.today_counterparty_unknown)
            } else {
                null
            }
    } else {
        null
    }
    val cardDescription = listOfNotNull(typeLabel, item.title, counterpartyLabel).joinToString(", ")
    val clickModifier = if (item is TimelineItem.Commitment) {
        Modifier
            .semantics {
                role = Role.Button
                contentDescription = cardDescription
            }
            .clickable { onOpenCommitmentDetail(item.id) }
    } else {
        Modifier
    }
    // Surface is intentionally neutral. Action ownership is signalled by the leading label.
    // Tinting the surface itself was double-signal and used wrong colors for take.
    EvidenceCard(
        modifier = modifier
            .then(clickModifier),
        contentPadding = PaddingValues(
            horizontal = 12.dp,
            vertical = if (item is TimelineItem.Commitment &&
                item.rowTreatment == TodayCommitmentRowTreatment.SCHEDULE
            ) {
                8.dp
            } else {
                10.dp
            },
        ),
    ) {
        Text(
            text = typeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        relatedSourceLabel(item)?.let { label ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.title,
            style = if (item is TimelineItem.Commitment &&
                item.rowTreatment == TodayCommitmentRowTreatment.SCHEDULE
            ) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
        calendarMetaLabel(item)?.let { label ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item is TimelineItem.Commitment) {
            val showCounterparty = !item.counterpartyDisplayName.isNullOrBlank() ||
                item.itemType != CommitmentItemType.SCHEDULE
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCounterparty) {
                    CounterpartyText(name = item.counterpartyDisplayName)
                }
                if (item.itemType == CommitmentItemType.SCHEDULE) {
                    if (showCounterparty) {
                        Spacer(modifier = Modifier.size(size = 8.dp))
                    }
                    Text(
                        text = scheduleLabel(item.scheduleStatus),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (item.direction.isNullOrBlank()) {
                    Spacer(modifier = Modifier.size(size = 8.dp))
                    Text(
                        text = stringResource(R.string.commitment_action_label_unknown),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!item.isTimed) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { onAddDueTime(item.id) },
                    modifier = Modifier
                        .align(Alignment.End)
                        .defaultMinSize(minHeight = 44.dp),
                ) {
                    Text(text = stringResource(R.string.today_add_due_time))
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        item.timelineAt?.let { TimestampText(sortKey = it) }
    }
}

@Composable
private fun typeLabelFor(item: TimelineItem): String = when (item) {
    is TimelineItem.Commitment -> when (item.itemType) {
        CommitmentItemType.ACTION -> stringResource(commitmentActionLabelRes(item.direction))
        CommitmentItemType.SCHEDULE -> if (item.sourceType?.isCalendarSource() == true) {
            stringResource(R.string.today_type_schedule)
        } else {
            stringResource(R.string.today_type_schedule_candidate)
        }
        else -> stringResource(R.string.today_section_commitments)
    }
    is TimelineItem.CalendarEvent -> stringResource(R.string.today_type_event)
    is TimelineItem.Meeting -> stringResource(R.string.today_type_meeting)
}

private fun TimelineItem.isCalendarAllDay(): Boolean = when (this) {
    is TimelineItem.CalendarEvent -> isAllDay
    is TimelineItem.Meeting -> isAllDay
    is TimelineItem.Commitment -> false
}

@Composable
private fun calendarMetaLabel(item: TimelineItem): String? {
    val values = when (item) {
        is TimelineItem.CalendarEvent -> listOfNotNull(
            item.location?.takeIf { it.isNotBlank() },
            calendarStatusLabel(item.status),
            availabilityLabel(item.availability),
        )
        is TimelineItem.Meeting -> listOfNotNull(
            item.location?.takeIf { it.isNotBlank() },
            calendarStatusLabel(item.status),
            availabilityLabel(item.availability),
        )
        is TimelineItem.Commitment -> emptyList()
    }
    return values.distinct().takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun calendarStatusLabel(status: String?): String? = when (status) {
    "cancelled" -> stringResource(R.string.today_calendar_status_cancelled)
    "tentative" -> stringResource(R.string.today_calendar_status_tentative)
    else -> null
}

@Composable
private fun availabilityLabel(availability: String?): String? = when (availability) {
    "free" -> stringResource(R.string.today_calendar_availability_free)
    "tentative" -> stringResource(R.string.today_calendar_availability_tentative)
    "oof" -> stringResource(R.string.today_calendar_availability_oof)
    "working_elsewhere" -> stringResource(R.string.today_calendar_availability_working_elsewhere)
    else -> null
}

@Composable
private fun relatedSourceLabel(item: TimelineItem): String? {
    val sourceTypes = when (item) {
        is TimelineItem.CalendarEvent -> item.relatedSourceTypes
        is TimelineItem.Meeting -> item.relatedSourceTypes
        is TimelineItem.Commitment -> emptyList()
    }
    if (sourceTypes.isEmpty()) return null
    val distinctSources = sourceTypes.distinct()
    var labels = ""
    for ((index, sourceType) in distinctSources.withIndex()) {
        if (index > 0) labels += ", "
        labels += stringResource(sourceTypeLabelRes(sourceType))
    }
    return stringResource(R.string.person_detail_related_records_fmt, labels)
}

@StringRes
private fun sourceTypeLabelRes(sourceType: String): Int = when (sourceType) {
    SourceType.GMAIL -> R.string.raw_event_source_badge_gmail
    SourceType.OUTLOOK_MAIL -> R.string.raw_event_source_badge_outlook_mail
    SourceType.NAVER_IMAP -> R.string.raw_event_source_badge_naver_imap
    SourceType.DAUM_IMAP -> R.string.raw_event_source_badge_daum_imap
    SourceType.GOOGLE_CALENDAR -> R.string.raw_event_source_badge_google_calendar
    SourceType.OUTLOOK_CALENDAR -> R.string.raw_event_source_badge_outlook_calendar
    SourceType.VOICE -> R.string.raw_event_source_badge_voice
    SourceType.CALL_RECORDING -> R.string.raw_event_source_badge_call_recording
    SourceType.MEETING -> R.string.raw_event_source_badge_meeting
    SourceType.MESSAGE_SCREENSHOT -> R.string.raw_event_source_badge_message_screenshot
    else -> R.string.raw_event_source_badge_unknown
}

@StringRes
private fun todayProcessingPhaseLabelRes(phase: ProcessingPhase): Int = when (phase) {
    ProcessingPhase.IDLE -> R.string.processing_phase_idle
    ProcessingPhase.SCANNING -> R.string.processing_phase_scanning
    ProcessingPhase.NEW_ITEMS -> R.string.processing_phase_new_items
    ProcessingPhase.GEMINI -> R.string.processing_phase_memory
    ProcessingPhase.UPLOADING -> R.string.processing_phase_uploading
    ProcessingPhase.NO_NEW_ITEMS -> R.string.processing_phase_no_new_items
    ProcessingPhase.SYNCED -> R.string.processing_phase_synced
    ProcessingPhase.BLOCKED,
    ProcessingPhase.ERROR,
    -> R.string.processing_phase_attention_needed
}

private fun formatKstTime(instant: Instant): String {
    val ldt = instant.toLocalDateTime(KST_ZONE)
    val hour = ldt.hour.toString().padStart(2, '0')
    val minute = ldt.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}

private val KST_ZONE: TimeZone = TimeZone.of("Asia/Seoul")

@Composable
private fun scheduleLabel(scheduleStatus: String?): String {
    val status = when (scheduleStatus) {
        CommitmentScheduleStatus.CONFIRMED -> stringResource(R.string.commitment_subtype_schedule_confirmed)
        CommitmentScheduleStatus.CHANGED -> stringResource(R.string.commitment_subtype_schedule_changed)
        CommitmentScheduleStatus.POSTPONED -> stringResource(R.string.commitment_subtype_schedule_postponed)
        CommitmentScheduleStatus.CANCELLED -> stringResource(R.string.commitment_subtype_schedule_cancelled)
        CommitmentScheduleStatus.FOLLOW_UP -> stringResource(R.string.commitment_subtype_schedule_follow_up)
        else -> null
    }
    return status ?: stringResource(R.string.commitment_item_type_schedule)
}

@PreviewLightDark
@Composable
private fun PreviewTodayTimelineScreenWithItems() {
    BecalmTheme {
        BecalmScaffold(title = "Today") { padding ->
            EmptyState(
                title = "Clear day ahead",
                message = "No commitments or meetings scheduled for today.",
                modifier = Modifier.padding(padding),
            )
        }
    }
}
