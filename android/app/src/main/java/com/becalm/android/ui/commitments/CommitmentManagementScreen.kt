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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.becalm.android.ui.actions.PersonActionEvidenceDialog
import com.becalm.android.ui.actions.PersonActionFeedStatusLine
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.personActionFeedCompactStatusMessage
import com.becalm.android.ui.actions.shouldOfferReminder
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonSize
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTopChrome
import com.becalm.android.ui.components.CommitmentCard
import com.becalm.android.ui.components.CommitmentWire
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.MainTabHeaderActions
import com.becalm.android.ui.components.MainTabCompactSourceAttentionLine
import com.becalm.android.ui.components.SkeletonBlock
import com.becalm.android.ui.components.becalmSkeletonColor
import com.becalm.android.ui.components.formatDayBadgeLabel
import com.becalm.android.ui.components.hasSourceWarningForCompactLine
import com.becalm.android.ui.components.isGiveDirection
import com.becalm.android.ui.components.isTakeDirection
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

private val CommitmentListBottomPadding = 144.dp

/**
 * Commitment management screen — open commitments grouped by Give / Take.
 *
 * Give / Take sections are the primary scan path. Filter chips stay as a secondary
 * control and each [CommitmentRow] is rendered via [CommitmentCard].
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
    onOpenSources: () -> Unit = onOpenSettings,
    onOpenSource: ((String) -> Unit)? = null,
    onOpenProcessingStatus: () -> Unit = {},
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
        onOpenSource = onOpenSource,
        headerState = headerState,
        onOpenDetail = viewModel::onCommitmentSelected,
        onCompletePersonAction = viewModel::onCompletePersonAction,
        onOpenPersonActionEvidence = viewModel::onOpenPersonActionEvidence,
        onDismissPersonActionEvidence = viewModel::onDismissPersonActionEvidence,
        onRemindPersonAction = viewModel::onRemindPersonAction,
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
    onCompletePersonAction: (String) -> Unit = {},
    onOpenPersonActionEvidence: (String, String?, String?) -> Unit = { _, _, _ -> },
    onDismissPersonActionEvidence: () -> Unit = {},
    onRemindPersonAction: (String) -> Unit = {},
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
    onOpenSource: ((String) -> Unit)? = null,
) {
    val evidenceImportController = rememberEvidenceImportSheetController()
    val showEvidenceImportFab = !state.loading && state.topActions.isEmpty()
    val hasSourceWarning = headerState.hasSourceWarningForCompactLine()
    val actionFeedStatus = state.actionFeedStatus
    val actionFeedStatusMessage = actionFeedStatus?.let { personActionFeedCompactStatusMessage(it) }
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.commitments_title),
        topChrome = BecalmTopChrome.MainTab,
        actions = {
            MainTabHeaderActions(
                onOpenSettings = onOpenSettings,
                compact = true,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (showEvidenceImportFab) {
                EvidenceImportFloatingActionButton(onClick = evidenceImportController::openSheet)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hasSourceWarning) {
                MainTabCompactSourceAttentionLine(
                    state = headerState,
                    onOpenSources = onOpenSources,
                    onOpenSource = onOpenSource,
                    supportingStatusText = actionFeedStatusMessage,
                    onOpenSupportingStatus = onStatusDetailsClick,
                    testTagPrefix = "commitments-source",
                )
            } else if (actionFeedStatus != null) {
                PersonActionFeedStatusLine(
                    status = actionFeedStatus,
                    onOpenProcessingStatus = onStatusDetailsClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    testTag = "commitments-action-feed-statusline",
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pullRefresh(pullState),
            ) {
                when {
                    state.loading -> {
                        CommitmentListSkeleton()
                    }
                    else -> {
                        val rowsForDirection = state.activeItems.ifEmpty { state.items }.distinctByCommitmentId()
                        val rowsById = rowsForDirection.associateBy { it.id }
                        val today = state.today
                            ?: Clock.System.now().toLocalDateTime(KST).date
                        val giveTag = stringResource(R.string.commitment_direction_give_tag)
                        val takeTag = stringResource(R.string.commitment_direction_take_tag)
                        val unknownTag = stringResource(R.string.commitment_direction_unknown_tag)
                        val tagLabels = mapOf(
                            CommitmentDirectionBucket.GIVE to giveTag,
                            CommitmentDirectionBucket.TAKE to takeTag,
                            CommitmentDirectionBucket.UNKNOWN to unknownTag,
                        )
                        val filteredActions = state.topActions
                            .map { action ->
                                CommitmentListEntry.Action(
                                    action = action,
                                    directionBucket = action.directionBucket(rowsById),
                                )
                            }
                            .filter { it.directionBucket.isVisibleForFilter(state.filter) }
                        val actionCommitmentIds = filteredActions.mapNotNullTo(mutableSetOf()) { it.action.commitmentId }
                        val filteredRows = rowsForDirection
                            .map { row ->
                                CommitmentListEntry.Row(
                                    row = row,
                                    directionBucket = row.directionBucket(),
                                )
                            }
                            .filter { it.directionBucket.isVisibleForFilter(state.filter) }
                            .filterNot { it.row.id in actionCommitmentIds }
                        val sections = buildCommitmentDueSections(
                            entries = filteredActions + filteredRows,
                            today = today,
                        )
                        val visibleContentCount = sections.sumOf { it.entries.size }
                        var pastExpanded by rememberSaveable(state.filter) { mutableStateOf(false) }
                        var laterExpanded by rememberSaveable(state.filter) { mutableStateOf(false) }
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                top = 10.dp,
                                end = 16.dp,
                                bottom = CommitmentListBottomPadding,
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("commitment-list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "commitment-direction-intro") {
                                CommitmentDirectionIntro(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 2.dp),
                                )
                            }
                            item(key = "commitment-filter-row") {
                                FilterChipRow(
                                    selectedFilter = state.filter,
                                    onFilterSelect = onFilterChange,
                                    contentPadding = PaddingValues(horizontal = 0.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            sections.forEach { section ->
                                val expanded = when (section.kind) {
                                    CommitmentDueSectionKind.PAST -> pastExpanded
                                    CommitmentDueSectionKind.LATER -> laterExpanded
                                    else -> true
                                }
                                commitmentDueSection(
                                    section = section,
                                    today = today,
                                    expanded = expanded,
                                    onToggleExpanded = {
                                        when (section.kind) {
                                            CommitmentDueSectionKind.PAST -> pastExpanded = !pastExpanded
                                            CommitmentDueSectionKind.LATER -> laterExpanded = !laterExpanded
                                            else -> Unit
                                        }
                                    },
                                    directionTagLabels = tagLabels,
                                    loadingEvidenceActionId = state.loadingEvidenceActionId,
                                    loadingReminderActionId = state.loadingReminderActionId,
                                    onOpenDetail = onOpenDetail,
                                    onCompleteAction = onCompletePersonAction,
                                    onOpenEvidence = onOpenPersonActionEvidence,
                                    onRemind = onRemindPersonAction,
                                )
                            }
                            if (visibleContentCount == 0) {
                                item(key = "commitment-direction-empty") {
                                    EmptyState(
                                        title = stringResource(R.string.commitments_empty_title),
                                        message = stringResource(R.string.commitments_empty_message),
                                    )
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

    state.evidenceDetail?.let { detail ->
        PersonActionEvidenceDialog(
            detail = detail,
            onDismiss = onDismissPersonActionEvidence,
        )
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
private fun CommitmentDirectionIntro(
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.commitments_header_subtitle),
        modifier = modifier.testTag("commitment-direction-intro"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun LazyListScope.commitmentDueSection(
    section: CommitmentDueSection,
    today: LocalDate,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    directionTagLabels: Map<CommitmentDirectionBucket, String>,
    loadingEvidenceActionId: String?,
    loadingReminderActionId: String?,
    onOpenDetail: (String) -> Unit,
    onCompleteAction: (String) -> Unit,
    onOpenEvidence: (String, String?, String?) -> Unit,
    onRemind: (String) -> Unit,
) {
    if (section.entries.isEmpty() && !section.kind.alwaysVisible) return
    item(key = "commitment-due-header-${section.kind.key}") {
        CommitmentDueSectionHeader(
            title = stringResource(section.kind.titleRes, section.entries.size),
            expandable = section.kind.expandable && section.entries.isNotEmpty(),
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("commitment-due-${section.kind.key}"),
        )
    }
    if (section.entries.isEmpty()) {
        item(key = "commitment-due-${section.kind.key}-empty") {
            CommitmentDueSectionEmpty(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        return
    }
    if (!expanded) return
    items(
        items = section.entries,
        key = { "${section.kind.key}-${it.stableKey}" },
    ) { entry ->
        when (entry) {
            is CommitmentListEntry.Action -> {
                val action = entry.action
                EvidenceCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    CommitmentActionRow(
                        action = action,
                        directionTag = directionTagLabels.getValue(entry.directionBucket),
                        dueBadge = entry.dueBadge(today),
                        loadingEvidence = loadingEvidenceActionId == action.id,
                        loadingReminder = loadingReminderActionId == action.id,
                        onOpenDetail = onOpenDetail,
                        onCompleteAction = onCompleteAction,
                        onOpenEvidence = onOpenEvidence,
                        onRemind = onRemind,
                    )
                }
            }
            is CommitmentListEntry.Row -> {
                CommitmentRowCard(row = entry.row, onOpenDetail = onOpenDetail)
            }
        }
    }
}

@Composable
private fun CommitmentDueSectionHeader(
    title: String,
    expandable: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        if (expandable) {
            TextButton(onClick = onToggleExpanded) {
                Text(
                    text = stringResource(
                        if (expanded) {
                            R.string.commitment_due_section_collapse
                        } else {
                            R.string.commitment_due_section_expand
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun CommitmentDueSectionEmpty(
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.commitment_due_section_empty),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private enum class CommitmentDueSectionKind(
    val key: String,
    @StringRes val titleRes: Int,
    val alwaysVisible: Boolean,
    val expandable: Boolean = false,
) {
    PAST("past", R.string.commitment_due_past_title_fmt, false, expandable = true),
    TODAY("today", R.string.commitment_due_today_title_fmt, true),
    THIS_WEEK("this-week", R.string.commitment_due_this_week_title_fmt, true),
    LATER("later", R.string.commitment_due_later_title_fmt, false, expandable = true),
}

private data class CommitmentDueSection(
    val kind: CommitmentDueSectionKind,
    val entries: List<CommitmentListEntry>,
)

private sealed interface CommitmentListEntry {
    val stableKey: String
    val dueAt: Instant?
    val dueHint: String?
    val dueIsApproximate: Boolean
    val directionBucket: CommitmentDirectionBucket

    data class Action(
        val action: PersonActionItemUi,
        override val directionBucket: CommitmentDirectionBucket,
    ) : CommitmentListEntry {
        override val stableKey: String = "action-${action.id}"
        override val dueAt: Instant? = action.dueAt
        override val dueHint: String? = action.dueHint
        override val dueIsApproximate: Boolean = false
    }

    data class Row(
        val row: CommitmentRow,
        override val directionBucket: CommitmentDirectionBucket,
    ) : CommitmentListEntry {
        override val stableKey: String = "row-${row.id}"
        override val dueAt: Instant? = row.dueAt
        override val dueHint: String? = row.dueHint
        override val dueIsApproximate: Boolean = row.dueIsApproximate
    }
}

private fun buildCommitmentDueSections(
    entries: List<CommitmentListEntry>,
    today: LocalDate,
): List<CommitmentDueSection> {
    val grouped = linkedMapOf(
        CommitmentDueSectionKind.PAST to mutableListOf<CommitmentListEntry>(),
        CommitmentDueSectionKind.TODAY to mutableListOf<CommitmentListEntry>(),
        CommitmentDueSectionKind.THIS_WEEK to mutableListOf<CommitmentListEntry>(),
        CommitmentDueSectionKind.LATER to mutableListOf<CommitmentListEntry>(),
    )
    entries
        .sortedWith(
            compareBy<CommitmentListEntry> { it.dayDeltaForSort(today) ?: Int.MAX_VALUE }
                .thenByDescending { it.secondarySortInstant() },
        )
        .forEach { entry ->
            grouped.getValue(entry.sectionKind(today)).add(entry)
        }
    return grouped.map { (kind, sectionEntries) ->
        CommitmentDueSection(kind = kind, entries = sectionEntries)
    }
}

private fun CommitmentListEntry.sectionKind(today: LocalDate): CommitmentDueSectionKind {
    val dayDelta = dayDeltaForBucket(today)
    return when {
        dayDelta != null && dayDelta < 0 -> CommitmentDueSectionKind.PAST
        dayDelta == 0 -> CommitmentDueSectionKind.TODAY
        dayDelta != null && dayDelta in 1..7 -> CommitmentDueSectionKind.THIS_WEEK
        else -> CommitmentDueSectionKind.LATER
    }
}

private fun CommitmentListEntry.dayDeltaForSort(today: LocalDate): Int? =
    dueAtDayDelta(today) ?: dueHintDayDelta()

private fun CommitmentListEntry.dayDeltaForBucket(today: LocalDate): Int? =
    dueAtDayDelta(today) ?: dueHintDayDelta()

private fun CommitmentListEntry.dueAtDayDelta(today: LocalDate): Int? {
    val due = dueAt ?: return null
    val dueDate = due.toLocalDateTime(KST).date
    return today.daysUntil(dueDate)
}

private fun CommitmentListEntry.dueHintDayDelta(): Int? {
    val hint = dueHint?.trim()?.lowercase() ?: return null
    return when {
        hint.contains("오늘") || hint.contains("금일") || hint.contains("today") -> 0
        hint.contains("내일") || hint.contains("tomorrow") -> 1
        hint.contains("이번 주") || hint.contains("이번주") || hint.contains("금주") ||
            hint.contains("this week") -> 1
        else -> null
    }
}

private fun CommitmentListEntry.secondarySortInstant(): Instant? =
    when (this) {
        is CommitmentListEntry.Action -> action.evidence?.occurredAt
        is CommitmentListEntry.Row -> row.sourceOccurredAt
    }

private fun CommitmentListEntry.dueBadge(today: LocalDate): String? {
    val dayDelta = dueAtDayDelta(today) ?: return null
    return formatDayBadgeLabel(days = dayDelta, approximate = dueIsApproximate)
}

private enum class CommitmentDirectionBucket {
    GIVE,
    TAKE,
    UNKNOWN,
}

private fun CommitmentDirectionBucket.isVisibleForFilter(filter: CommitmentFilter): Boolean =
    when (filter) {
        CommitmentFilter.ALL,
        CommitmentFilter.SCHEDULE,
        CommitmentFilter.CLOSED,
        -> true
        CommitmentFilter.GIVE -> this == CommitmentDirectionBucket.GIVE
        CommitmentFilter.TAKE -> this == CommitmentDirectionBucket.TAKE
    }

private fun CommitmentRow.directionBucket(): CommitmentDirectionBucket =
    when {
        isGiveDirection(direction) -> CommitmentDirectionBucket.GIVE
        isTakeDirection(direction) -> CommitmentDirectionBucket.TAKE
        else -> CommitmentDirectionBucket.UNKNOWN
    }

private fun PersonActionItemUi.directionBucket(
    rowsByCommitmentId: Map<String, CommitmentRow>,
): CommitmentDirectionBucket {
    val rowBucket = commitmentId?.let { rowsByCommitmentId[it]?.directionBucket() }
    return when {
        reasonCodes.any { it.matchesDirectionReason(CommitmentWire.DIRECTION_GIVE) } ->
            CommitmentDirectionBucket.GIVE
        reasonCodes.any { it.matchesDirectionReason(CommitmentWire.DIRECTION_TAKE) } ->
            CommitmentDirectionBucket.TAKE
        rowBucket != null -> rowBucket
        else -> CommitmentDirectionBucket.UNKNOWN
    }
}

private fun String.matchesDirectionReason(direction: String): Boolean {
    val normalized = trim().lowercase()
    val expected = direction.lowercase()
    return normalized == expected || normalized == "direction:$expected"
}

private fun List<CommitmentRow>.distinctByCommitmentId(): List<CommitmentRow> {
    val seen = mutableSetOf<String>()
    return filter { seen.add(it.id) }
}

@Composable
private fun CommitmentActionRow(
    action: PersonActionItemUi,
    directionTag: String,
    dueBadge: String?,
    loadingEvidence: Boolean,
    loadingReminder: Boolean,
    onOpenDetail: (String) -> Unit,
    onCompleteAction: (String) -> Unit,
    onOpenEvidence: (String, String?, String?) -> Unit,
    onRemind: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val commitmentId = action.commitmentId
    val rowModifier = if (commitmentId == null) {
        modifier
    } else {
        modifier.clickable { onOpenDetail(commitmentId) }
    }
    Column(
        modifier = rowModifier
            .fillMaxWidth()
            .testTag("commitment-action-${action.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommitmentActionMetaPill(text = directionTag, emphasized = true)
            dueBadge?.let { badge ->
                CommitmentActionMetaPill(text = badge, emphasized = true)
            }
            action.personDisplayName?.takeIf { it.isNotBlank() }?.let { displayName ->
                CommitmentActionMetaPill(text = displayName)
            }
            action.sourceType?.let { sourceType ->
                CommitmentActionMetaPill(text = stringResource(sourcePresentationFor(sourceType).labelRes))
            }
        }
        Text(
            text = action.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = action.shortReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        action.evidence?.quote?.takeIf { it.isNotBlank() }?.let { quote ->
            Text(
                text = stringResource(R.string.schedule_row_quote_fmt, quote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val evidence = action.evidence
        Row(
            modifier = Modifier.align(Alignment.End),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (evidence?.kind != null && evidence.id != null) {
                BecalmButton(
                    text = stringResource(R.string.commitment_action_evidence),
                    onClick = { onOpenEvidence(action.id, evidence.kind, evidence.id) },
                    loading = loadingEvidence,
                    variant = BecalmButtonVariant.Secondary,
                    size = BecalmButtonSize.Compact,
                )
            }
            if (action.shouldOfferReminder()) {
                IconButton(
                    onClick = { onRemind(action.id) },
                    enabled = !loadingReminder,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("commitment-action-reminder-${action.id}"),
                ) {
                    if (loadingReminder) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = stringResource(R.string.commitment_action_remind),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            BecalmButton(
                text = stringResource(R.string.commitment_action_complete),
                onClick = { onCompleteAction(action.id) },
                variant = BecalmButtonVariant.Primary,
                size = BecalmButtonSize.Compact,
            )
        }
    }
}

@Composable
private fun CommitmentActionMetaPill(
    text: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val backgroundColor = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

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
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val filters = listOf(
        CommitmentFilter.ALL to stringResource(R.string.commitments_filter_all),
        CommitmentFilter.GIVE to stringResource(R.string.commitments_filter_give),
        CommitmentFilter.TAKE to stringResource(R.string.commitments_filter_take),
    )
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
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
