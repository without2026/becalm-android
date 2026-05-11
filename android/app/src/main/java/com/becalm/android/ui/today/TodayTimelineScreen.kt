package com.becalm.android.ui.today

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

    val evidenceImportActions = rememberEvidenceImportActions(evidenceImportViewModel)

    CollectFlowEffect(viewModel.effects) { effect ->
        navController.dispatchTodayEffect(effect)
    }

    TodayTimelineContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenSettings = viewModel::onOpenSettings,
        onPullRefresh = viewModel::onPullRefresh,
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
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenSettings: () -> Unit,
    onPullRefresh: () -> Unit,
    onOpenCommitmentDetail: (String) -> Unit = {},
    onAddDueTime: (String) -> Unit = {},
    onMessageScreenshotImport: () -> Unit = {},
    onMeetingAudioImport: () -> Unit = {},
    evidenceImportState: EvidenceImportUiState = EvidenceImportUiState(),
    onMeetingSelfSpeakerSelected: (String) -> Unit = {},
    onMeetingSpeakerReviewConfirmed: () -> Unit = {},
    onMeetingSpeakerReviewCancelled: () -> Unit = {},
    modifier: Modifier = Modifier,
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
                MainTabStatusHeader(state = headerState)
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
                TodayPersonFocusRow(person = person)
            }
        }
    }
}

@Composable
private fun TodayPersonFocusRow(person: TodayPersonFocus) {
    val label = person.displayName ?: stringResource(R.string.today_counterparty_unknown)
    Row(
        modifier = Modifier.fillMaxWidth(),
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
        text = item.timelineAt?.let(::formatKstTime) ?: stringResource(R.string.today_no_due_time),
        modifier = modifier
            .width(54.dp)
            .padding(top = 12.dp, start = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (item.isTimed) {
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
    val clickModifier = if (item is TimelineItem.Commitment) {
        Modifier.clickable { onOpenCommitmentDetail(item.id) }
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
            text = typeLabelFor(item),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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
        if (item is TimelineItem.Commitment) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CounterpartyText(name = item.counterpartyDisplayName)
                if (item.itemType == CommitmentItemType.SCHEDULE) {
                    Spacer(modifier = Modifier.size(size = 8.dp))
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
        CommitmentItemType.SCHEDULE -> stringResource(R.string.today_type_schedule)
        else -> stringResource(R.string.today_section_commitments)
    }
    is TimelineItem.CalendarEvent -> stringResource(R.string.today_type_event)
    is TimelineItem.Meeting -> stringResource(R.string.today_type_meeting)
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
