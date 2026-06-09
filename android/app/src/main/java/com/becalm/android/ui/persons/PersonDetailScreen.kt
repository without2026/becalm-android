package com.becalm.android.ui.persons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.core.util.KST
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.ui.actions.PersonActionDraftDialog
import com.becalm.android.ui.actions.PersonActionEvidenceDialog
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.shouldOfferReminder
import com.becalm.android.ui.actions.supportedDraftKind
import com.becalm.android.ui.components.BecalmActionPill
import com.becalm.android.ui.components.BecalmActionPillVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.RecommendationPanel
import com.becalm.android.ui.components.isCallSource
import com.becalm.android.ui.components.isEmailSource
import com.becalm.android.ui.components.isMeetingTimelineSource
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.evidence.EvidenceImportSheetHost
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.becalm.android.ui.evidence.rememberEvidenceImportActions
import com.becalm.android.ui.evidence.rememberEvidenceImportSheetController
import com.becalm.android.ui.navigation.BecalmNavigationDefaults
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.FirstMemoryFollowUpAction
import com.becalm.android.ui.onboarding.dispatchFirstMemoryFollowUpAction
import com.becalm.android.ui.onboarding.firstMemoryFollowUpActionsFor
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.LocalKstDayTick
import com.becalm.android.ui.theme.becalmColors
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Person detail screen — renders a [PersonHeader] plus a source-filtered unified
 * timeline of original interaction records. Extracted give/take/schedule items are
 * shown after opening a source record, not inline in the timeline.
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
    evidenceImportViewModel: EvidenceImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)
    val evidenceImportMessage = evidenceImportState.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(evidenceImportMessage, snackbarHostState, evidenceImportViewModel::onMessageShown)
    val evidenceImportActions = rememberEvidenceImportActions(evidenceImportViewModel)
    val evidenceImportController = rememberEvidenceImportSheetController()

    val onEventTap: (String) -> Unit = { eventId ->
        navController.navigate(
            BecalmRoute.RawEventDetail(personId = personId, eventId = eventId).path,
        )
    }

    PersonDetailScreenContent(
        state = state,
        title = stringResource(R.string.persons_title),
        snackbarHostState = snackbarHostState,
        onBack = {
            if (!navController.popBackStack()) {
                navController.navigate(BecalmNavigationDefaults.authenticatedHomeRoute) {
                    launchSingleTop = true
                }
            }
        },
        onEventTap = onEventTap,
        onLoadMoreTimeline = viewModel::onLoadMoreTimeline,
        onRetry = viewModel::onRetryLoad,
        onOpenPersonActionEvidence = viewModel::onOpenPersonActionEvidence,
        onDismissPersonActionEvidence = viewModel::onDismissPersonActionEvidence,
        onCompletePersonAction = viewModel::onCompletePersonAction,
        onDismissPersonAction = viewModel::onDismissPersonAction,
        onRemindPersonAction = viewModel::onRemindPersonAction,
        onOpenPersonActionDraft = viewModel::onOpenPersonActionDraft,
        onDismissPersonActionDraft = viewModel::onDismissPersonActionDraft,
        onRetryPersonActionDraft = viewModel::onRetryPersonActionDraft,
        onDraftSubjectChange = viewModel::onDraftSubjectChange,
        onDraftBodyChange = viewModel::onDraftBodyChange,
        onRetryManualMemorySync = viewModel::onRetryManualMemorySync,
        onFirstMemoryFollowUpAction = { action ->
            navController.dispatchFirstMemoryFollowUpAction(
                action = action,
                onMeetingAudio = evidenceImportActions.openMeetingAudioPicker,
                onMessageScreenshot = evidenceImportActions.openMessageScreenshotPicker,
            )
        },
    )

    EvidenceImportSheetHost(
        controller = evidenceImportController,
        onMessageScreenshotImport = evidenceImportActions.openMessageScreenshotPicker,
        onMeetingAudioImport = evidenceImportActions.openMeetingAudioPicker,
        state = evidenceImportState,
        onMeetingSelfSpeakerSelected = evidenceImportViewModel::onMeetingSelfSpeakerSelected,
        onMeetingCounterpartySpeakerSelected = evidenceImportViewModel::onMeetingCounterpartySpeakerSelected,
        onMeetingSpeakerReviewConfirmed = evidenceImportViewModel::onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = evidenceImportViewModel::onMeetingSpeakerReviewCancelled,
        onMeetingSpeakerReviewAction = evidenceImportViewModel::onMeetingSpeakerReviewAction,
        onMeetingPreviewLoadingCancelled = evidenceImportViewModel::onMeetingPreviewLoadingCancelled,
        onRetryFailedImports = evidenceImportViewModel::onRetryFailedImports,
        onReviewRequiredClick = {
            navController.navigate(BecalmRoute.PersonsUnassigned.path)
        },
        onStatusDetailsClick = {
            navController.navigate(BecalmRoute.ProcessingStatus.path)
        },
        onConsentRequiredClick = {
            navController.navigate(BecalmRoute.Settings.path)
        },
    )
}

@Composable
public fun PersonDetailScreenContent(
    state: PersonDetailUiState,
    title: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
    onLoadMoreTimeline: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpenPersonActionEvidence: (String, String?, String?) -> Unit = { _, _, _ -> },
    onDismissPersonActionEvidence: () -> Unit = {},
    onCompletePersonAction: (String) -> Unit = {},
    onDismissPersonAction: (String) -> Unit = {},
    onRemindPersonAction: (String) -> Unit = {},
    onOpenPersonActionDraft: (String) -> Unit = {},
    onDismissPersonActionDraft: () -> Unit = {},
    onRetryPersonActionDraft: () -> Unit = {},
    onDraftSubjectChange: (String) -> Unit = {},
    onDraftBodyChange: (String) -> Unit = {},
    onFirstMemoryFollowUpAction: (FirstMemoryFollowUpAction) -> Unit = {},
    onRetryManualMemorySync: () -> Unit = {},
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
        val hasAnyInteractions = state.timelineItems.isNotEmpty()
        when {
            state.loading -> {
                BecalmSheetSkeleton(modifier = Modifier.padding(padding))
            }
            state.error != null && !hasAnyInteractions -> {
                val retryAction = if (state.error.resId == R.string.person_detail_error_load_failed) {
                    onRetry
                } else {
                    null
                }
                ErrorState(
                    title = stringResource(R.string.error_generic_title),
                    message = uiMessageStringResource(requireNotNull(state.error)),
                    onRetry = retryAction,
                    modifier = Modifier.padding(padding),
                )
            }
            !hasAnyInteractions -> PersonDetailEmpty(state = state, padding = padding)
            else -> PersonDetailList(
                state = state,
                padding = padding,
                onEventTap = onEventTap,
                onLoadMoreTimeline = onLoadMoreTimeline,
                onOpenPersonActionEvidence = onOpenPersonActionEvidence,
                onCompletePersonAction = onCompletePersonAction,
                onDismissPersonAction = onDismissPersonAction,
                onRemindPersonAction = onRemindPersonAction,
                onOpenPersonActionDraft = onOpenPersonActionDraft,
                onFirstMemoryFollowUpAction = onFirstMemoryFollowUpAction,
                onRetryManualMemorySync = onRetryManualMemorySync,
            )
        }
    }
    state.evidenceDetail?.let { detail ->
        PersonActionEvidenceDialog(
            detail = detail,
            onDismiss = onDismissPersonActionEvidence,
        )
    }
    state.draftSheet?.let { draftState ->
        val draftEvidenceAction = state.topActions
            .firstOrNull { it.id == draftState.actionItemId }
            ?.evidence
            ?.takeIf { !it.kind.isNullOrBlank() && !it.id.isNullOrBlank() }
            ?.let { evidence ->
                {
                    onOpenPersonActionEvidence(
                        draftState.actionItemId,
                        evidence.kind,
                        evidence.id,
                    )
                }
            }
        PersonActionDraftDialog(
            state = draftState,
            onSubjectChange = onDraftSubjectChange,
            onBodyChange = onDraftBodyChange,
            onRetry = onRetryPersonActionDraft,
            onOpenEvidence = draftEvidenceAction,
            onDismiss = onDismissPersonActionDraft,
        )
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
                personId = state.personId,
                eventCount = state.eventCount,
                emailInteractionCount = state.emailInteractionCount,
                callInteractionCount = state.callInteractionCount,
                meetingCount = state.meetingCount,
                pendingCommitmentCount = state.pendingCommitmentCount,
                relationshipStartedAt = state.relationshipStartedAt,
                lastInteractionAt = state.lastInteractionAt,
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
    onLoadMoreTimeline: () -> Unit,
    onOpenPersonActionEvidence: (String, String?, String?) -> Unit,
    onCompletePersonAction: (String) -> Unit,
    onDismissPersonAction: (String) -> Unit,
    onRemindPersonAction: (String) -> Unit,
    onOpenPersonActionDraft: (String) -> Unit,
    onFirstMemoryFollowUpAction: (FirstMemoryFollowUpAction) -> Unit,
    onRetryManualMemorySync: () -> Unit,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(PersonTimelineFilter.ALL) }
    var expandedThreadKey by rememberSaveable { mutableStateOf<String?>(null) }
    val timelineItems = remember(selectedFilter, state.timelineItems) {
        state.timelineItems.filter(selectedFilter::matches)
    }
    val timelineHeader = stringResource(
        R.string.person_detail_timeline_section_fmt,
        timelineItems.size,
    )
    val kstDayTick = LocalKstDayTick.current
    val recallCue = remember(state.topActions, state.sourceEventCards, kstDayTick) {
        buildPersonRecallCue(
            sourceCards = state.sourceEventCards,
            topActions = state.topActions,
            now = Clock.System.now(),
        )
    }

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
                personId = state.personId,
                eventCount = state.eventCount,
                emailInteractionCount = state.emailInteractionCount,
                callInteractionCount = state.callInteractionCount,
                meetingCount = state.meetingCount,
                pendingCommitmentCount = state.pendingCommitmentCount,
                relationshipStartedAt = state.relationshipStartedAt,
                lastInteractionAt = state.lastInteractionAt,
            )
        }
        if (state.topActions.isNotEmpty()) {
            item(key = "next-actions") {
                PersonNextActionsPanel(
                    actions = state.topActions,
                    sourceCards = state.sourceEventCards,
                    loadingEvidenceActionId = state.loadingEvidenceActionId,
                    loadingReminderActionId = state.loadingReminderActionId,
                    loadingCompleteActionId = state.loadingCompleteActionId,
                    loadingDismissActionId = state.loadingDismissActionId,
                    loadingDraftActionId = state.loadingDraftActionId,
                    onActionClick = { action ->
                        action.resolveRawEventId(state.sourceEventCards)?.let(onEventTap)
                    },
                    onOpenEvidence = onOpenPersonActionEvidence,
                    onComplete = onCompletePersonAction,
                    onDismiss = onDismissPersonAction,
                    onRemind = onRemindPersonAction,
                    onOpenDraft = onOpenPersonActionDraft,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        state.manualMemorySyncStatus?.let { status ->
            item(key = "manual-memory-sync-status") {
                ManualMemorySyncStatusPanel(
                    status = status,
                    retrying = state.retryingManualMemorySync,
                    onRetry = onRetryManualMemorySync,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (recallCue != null) {
            item(key = "relationship-recall") {
                PersonRelationshipRecallPanel(
                    cue = recallCue,
                    onEventTap = onEventTap,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        val firstMemoryCards = state.sourceEventCards.filter { it.firstMemoryOrigin != null }
        if (firstMemoryCards.isNotEmpty()) {
            item(key = "first-memory-recommendations") {
                PersonFirstMemoryRecommendationPanel(
                    origin = requireNotNull(firstMemoryCards.first().firstMemoryOrigin),
                    onActionClick = onFirstMemoryFollowUpAction,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        item(key = "timeline-filters") {
            TimelineFilterRow(
                selectedFilter = selectedFilter,
                onFilterSelect = { selectedFilter = it },
            )
        }
        item(key = "header-timeline") { SectionHeader(text = timelineHeader) }
        if (timelineItems.isNotEmpty()) {
            items(
                items = timelineItems,
                key = { item -> item.key },
            ) { item ->
                when (item) {
                    is PersonTimelineItem.SourceEvent -> {
                        SourceEventCardRow(
                            card = item.card,
                            onEventTap = onEventTap,
                            modifier = Modifier.fillMaxWidth(),
                            expanded = expandedThreadKey == item.card.sourceEventKey,
                            onThreadToggle = { key ->
                                expandedThreadKey = if (expandedThreadKey == key) null else key
                            },
                        )
                    }
                    is PersonTimelineItem.ScheduleCandidate -> {
                        ScheduleCandidateTimelineRow(
                            item = item,
                            onEventTap = onEventTap,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is PersonTimelineItem.ConfirmedSchedule -> {
                        ConfirmedScheduleTimelineRow(
                            item = item,
                            onEventTap = onEventTap,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        } else {
            item(key = "timeline-empty") {
                EmptyState(title = stringResource(R.string.person_detail_timeline_filter_empty))
            }
        }
        if (state.canLoadMoreTimeline) {
            item(key = "load-more-timeline") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    BecalmActionPill(
                        text = stringResource(R.string.person_detail_load_more),
                        onClick = onLoadMoreTimeline,
                        trailingChevron = true,
                        variant = BecalmActionPillVariant.Neutral,
                        modifier = Modifier.testTag("person-detail-load-more"),
                    )
                }
            }
        }
    }
}

// ─── Timeline helpers ─────────────────────────────────────────────────────────

private const val PERSON_DETAIL_VISIBLE_ACTION_LIMIT = 3
private const val RELATIONSHIP_RECALL_IDLE_DAYS = 14

private data class PersonRecallCue(
    val daysSinceLastInteraction: Int,
    val lastCard: SourceEventCardProjection,
)

@Composable
private fun CompactInsightPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.76f)),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun CompactSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f),
        )
    }
}

private enum class MiniActionStyle {
    Solid,
    Outline,
    Ghost,
}

private data class MiniActionColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

@Composable
private fun MiniActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MiniActionStyle = MiniActionStyle.Outline,
    icon: ImageVector? = null,
    trailingChevron: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = miniActionColors(style)
    val interactive = enabled && !loading
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 32.dp)
            .then(if (enabled) Modifier else Modifier.alpha(0.38f))
            .clickable(enabled = interactive, role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = colors.content,
                    strokeWidth = 2.dp,
                )
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailingChevron) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = colors.content,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun miniActionColors(style: MiniActionStyle): MiniActionColors =
    when (style) {
        MiniActionStyle.Solid -> MiniActionColors(
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            border = MaterialTheme.colorScheme.primary,
        )
        MiniActionStyle.Outline -> MiniActionColors(
            container = MaterialTheme.colorScheme.surface,
            content = MaterialTheme.colorScheme.primary,
            border = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        )
        MiniActionStyle.Ghost -> MiniActionColors(
            container = Color.Transparent,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
        )
    }

@Composable
private fun ActionOrb(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "AI",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private fun buildPersonRecallCue(
    sourceCards: List<SourceEventCardProjection>,
    topActions: List<PersonActionItemUi>,
    now: Instant,
): PersonRecallCue? {
    if (topActions.isNotEmpty()) return null
    val lastPastCard = sourceCards
        .filter { it.occurredAt <= now }
        .maxByOrNull { it.occurredAt }
        ?: return null
    val daysSinceLastInteraction = lastPastCard.occurredAt
        .toLocalDateTime(KST)
        .date
        .daysUntil(now.toLocalDateTime(KST).date)
    return if (daysSinceLastInteraction >= RELATIONSHIP_RECALL_IDLE_DAYS) {
        PersonRecallCue(
            daysSinceLastInteraction = daysSinceLastInteraction,
            lastCard = lastPastCard,
        )
    } else {
        null
    }
}

@Composable
private fun PersonRelationshipRecallPanel(
    cue: PersonRecallCue,
    onEventTap: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastRecordTitle = cue.lastCard.title
        ?: cue.lastCard.snippet
        ?: stringResource(R.string.person_detail_recall_last_record)
    CompactInsightPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-relationship-recall"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.person_detail_recall_title),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = stringResource(
                    R.string.person_detail_recall_body_fmt,
                    cue.daysSinceLastInteraction,
                    lastRecordTitle,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cue.lastCard.rawEventId?.let { rawEventId ->
                MiniActionButton(
                    text = stringResource(R.string.person_detail_recall_open_last),
                    onClick = { onEventTap(rawEventId) },
                    style = MiniActionStyle.Outline,
                    trailingChevron = true,
                    modifier = Modifier.testTag("person-detail-recall-open-last"),
                )
            }
        }
    }
}

@Composable
private fun ManualMemorySyncStatusPanel(
    status: ManualMemorySyncStatusUi,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleRes = when (status.kind) {
        ManualMemorySyncStatusKind.PENDING -> R.string.person_detail_manual_memory_sync_pending_title
        ManualMemorySyncStatusKind.FAILED -> R.string.person_detail_manual_memory_sync_failed_title
    }
    val bodyRes = when (status.kind) {
        ManualMemorySyncStatusKind.PENDING -> R.string.person_detail_manual_memory_sync_pending_body
        ManualMemorySyncStatusKind.FAILED -> R.string.person_detail_manual_memory_sync_failed_body
    }
    RecommendationPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-manual-memory-sync"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
            )
            BecalmActionPill(
                text = stringResource(
                    if (retrying) {
                        R.string.person_detail_manual_memory_sync_retrying
                    } else {
                        R.string.person_detail_manual_memory_sync_retry
                    },
                ),
                onClick = onRetry,
                enabled = !retrying,
                loading = retrying,
                icon = Icons.Outlined.Refresh,
                modifier = Modifier.testTag("person-detail-manual-memory-sync-retry"),
            )
        }
    }
}

@Composable
private fun PersonFirstMemoryRecommendationPanel(
    origin: String,
    onActionClick: (FirstMemoryFollowUpAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = firstMemoryFollowUpActionsFor(origin)
    if (actions.isEmpty()) return
    RecommendationPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-first-memory-recommendation"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.person_detail_first_memory_recommendation_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            actions.forEach { action ->
                RecommendationActionRow(
                    text = stringResource(action.labelRes),
                    testTag = action.testTag,
                    onClick = { onActionClick(action) },
                )
            }
        }
    }
}

@Composable
private fun RecommendationActionRow(
    text: String,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics {
                contentDescription = text
            }
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.52f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.46f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PersonNextActionsPanel(
    actions: List<PersonActionItemUi>,
    sourceCards: List<SourceEventCardProjection>,
    loadingEvidenceActionId: String?,
    loadingReminderActionId: String?,
    loadingCompleteActionId: String?,
    loadingDismissActionId: String?,
    loadingDraftActionId: String?,
    onActionClick: (PersonActionItemUi) -> Unit,
    onOpenEvidence: (String, String?, String?) -> Unit,
    onComplete: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRemind: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-next-action-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactSectionHeader(text = stringResource(R.string.person_detail_next_action_title))
        CompactInsightPanel {
            val visibleActions = actions.take(PERSON_DETAIL_VISIBLE_ACTION_LIMIT)
            visibleActions.firstOrNull()?.let { action ->
                PersonActionRecommendationRow(
                    action = action,
                    sourceCards = sourceCards,
                    primary = true,
                    loadingEvidenceActionId = loadingEvidenceActionId,
                    loadingReminderActionId = loadingReminderActionId,
                    loadingCompleteActionId = loadingCompleteActionId,
                    loadingDismissActionId = loadingDismissActionId,
                    loadingDraftActionId = loadingDraftActionId,
                    onActionClick = onActionClick,
                    onOpenEvidence = onOpenEvidence,
                    onComplete = onComplete,
                    onDismiss = onDismiss,
                    onRemind = onRemind,
                    onOpenDraft = onOpenDraft,
                )
            }
            val secondaryActions = visibleActions.drop(1)
            if (secondaryActions.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.person_detail_secondary_actions_title_fmt, secondaryActions.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("person-detail-secondary-actions-title"),
                )
                Column(
                    modifier = Modifier.testTag("person-detail-secondary-actions"),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    secondaryActions.forEach { action ->
                        PersonActionRecommendationRow(
                            action = action,
                            sourceCards = sourceCards,
                            primary = false,
                            loadingEvidenceActionId = loadingEvidenceActionId,
                            loadingReminderActionId = loadingReminderActionId,
                            loadingCompleteActionId = loadingCompleteActionId,
                            loadingDismissActionId = loadingDismissActionId,
                            loadingDraftActionId = loadingDraftActionId,
                            onActionClick = onActionClick,
                            onOpenEvidence = onOpenEvidence,
                            onComplete = onComplete,
                            onDismiss = onDismiss,
                            onRemind = onRemind,
                            onOpenDraft = onOpenDraft,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonActionRecommendationRow(
    action: PersonActionItemUi,
    sourceCards: List<SourceEventCardProjection>,
    primary: Boolean,
    loadingEvidenceActionId: String?,
    loadingReminderActionId: String?,
    loadingCompleteActionId: String?,
    loadingDismissActionId: String?,
    loadingDraftActionId: String?,
    onActionClick: (PersonActionItemUi) -> Unit,
    onOpenEvidence: (String, String?, String?) -> Unit,
    onComplete: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRemind: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rawEventId = action.resolveRawEventId(sourceCards)
    val canOpenRawEvent = rawEventId != null
    val canOpenEvidence = action.hasEvidenceLookup()
    val loadingEvidence = loadingEvidenceActionId == action.id
    val loadingReminder = loadingReminderActionId == action.id
    val loadingComplete = loadingCompleteActionId == action.id
    val loadingDismiss = loadingDismissActionId == action.id
    val loadingDraft = loadingDraftActionId == action.id
    val actionBusy = loadingEvidence || loadingReminder || loadingComplete || loadingDismiss || loadingDraft
    val clickAction = {
        val evidence = action.evidence
        val evidenceKind = evidence?.kind
        val evidenceId = evidence?.id
        if (rawEventId != null) {
            onActionClick(action)
        } else if (evidenceKind != null && evidenceId != null) {
            onOpenEvidence(action.id, evidenceKind, evidenceId)
        } else {
            onActionClick(action)
        }
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-action-${action.id}")
            .clickable(
                enabled = (canOpenEvidence || canOpenRawEvent) && !loadingComplete && !loadingDismiss && !loadingDraft,
                role = Role.Button,
                onClick = clickAction,
            ),
        shape = if (primary) MaterialTheme.shapes.medium else MaterialTheme.shapes.small,
        color = if (primary) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
        },
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = if (primary) {
            null
        } else {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .testTag(
                    if (primary) {
                        "person-detail-primary-action"
                    } else {
                        "person-detail-secondary-action-${action.id}"
                    },
                )
                .padding(
                    horizontal = if (primary) 0.dp else 12.dp,
                    vertical = if (primary) 0.dp else 8.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (primary) 10.dp else 5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (primary) {
                    ActionOrb()
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.person_detail_next_action_kind_fmt,
                            action.primaryVerb.takeIf { it.isNotBlank() } ?: action.actionKind,
                        ),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = action.title,
                        style = if (primary) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = action.shortReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canOpenEvidence || canOpenRawEvent) {
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = if (loadingEvidence) 0.56f else 1f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (primary) {
                PersonPrimaryActionControls(
                    action = action,
                    canOpenEvidence = canOpenEvidence || canOpenRawEvent,
                    loadingEvidence = loadingEvidence,
                    loadingReminder = loadingReminder,
                    loadingComplete = loadingComplete,
                    loadingDismiss = loadingDismiss,
                    loadingDraft = loadingDraft,
                    actionBusy = actionBusy,
                    onOpenEvidence = clickAction,
                    onComplete = onComplete,
                    onDismiss = onDismiss,
                    onRemind = onRemind,
                    onOpenDraft = onOpenDraft,
                    modifier = Modifier.align(Alignment.End),
                )
            } else {
                PersonSecondaryActionControls(
                    action = action,
                    loadingReminder = loadingReminder,
                    loadingComplete = loadingComplete,
                    loadingDismiss = loadingDismiss,
                    loadingDraft = loadingDraft,
                    actionBusy = actionBusy,
                    onComplete = onComplete,
                    onDismiss = onDismiss,
                    onRemind = onRemind,
                    onOpenDraft = onOpenDraft,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun PersonPrimaryActionControls(
    action: PersonActionItemUi,
    canOpenEvidence: Boolean,
    loadingEvidence: Boolean,
    loadingReminder: Boolean,
    loadingComplete: Boolean,
    loadingDismiss: Boolean,
    loadingDraft: Boolean,
    actionBusy: Boolean,
    onOpenEvidence: () -> Unit,
    onComplete: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRemind: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val draftKind = action.supportedDraftKind().takeIf { PERSON_ACTION_DRAFT_CTA_ENABLED }
            if (draftKind != null) {
                MiniActionButton(
                    text = stringResource(draftPrimaryActionLabelRes(draftKind)),
                    onClick = { onOpenDraft(action.id) },
                    enabled = !actionBusy,
                    loading = loadingDraft,
                    style = MiniActionStyle.Solid,
                    modifier = Modifier.testTag("person-detail-action-draft-${action.id}"),
                )
            }
            if (canOpenEvidence) {
                MiniActionButton(
                    text = stringResource(R.string.person_action_evidence_view),
                    onClick = onOpenEvidence,
                    enabled = !actionBusy || loadingEvidence,
                    loading = loadingEvidence,
                    style = MiniActionStyle.Outline,
                    trailingChevron = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("person-detail-action-evidence-${action.id}"),
                )
            }
            MiniActionButton(
                text = stringResource(R.string.commitment_action_complete),
                onClick = { onComplete(action.id) },
                enabled = !actionBusy || loadingComplete,
                loading = loadingComplete,
                style = MiniActionStyle.Solid,
                modifier = Modifier
                    .weight(1f)
                    .testTag("person-detail-action-complete-${action.id}"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniActionButton(
                text = stringResource(R.string.schedule_action_dismiss),
                onClick = { onDismiss(action.id) },
                enabled = !actionBusy || loadingDismiss,
                loading = loadingDismiss,
                icon = Icons.Outlined.Close,
                style = MiniActionStyle.Ghost,
                modifier = Modifier.alpha(if (loadingDismiss) 0.56f else 1f),
            )
            if (action.shouldOfferReminder()) {
                MiniActionButton(
                    text = stringResource(R.string.commitment_action_remind),
                    onClick = { onRemind(action.id) },
                    enabled = !actionBusy || loadingReminder,
                    loading = loadingReminder,
                    icon = Icons.Outlined.Notifications,
                    style = MiniActionStyle.Ghost,
                    modifier = Modifier.alpha(if (loadingReminder) 0.56f else 1f),
                )
            }
        }
    }
}

@Composable
private fun PersonSecondaryActionControls(
    action: PersonActionItemUi,
    loadingReminder: Boolean,
    loadingComplete: Boolean,
    loadingDismiss: Boolean,
    loadingDraft: Boolean,
    actionBusy: Boolean,
    showDraft: Boolean = true,
    onComplete: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onRemind: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiniActionButton(
            text = stringResource(R.string.schedule_action_dismiss),
            onClick = { onDismiss(action.id) },
            enabled = !actionBusy,
            loading = loadingDismiss,
            icon = Icons.Outlined.Close,
            style = MiniActionStyle.Ghost,
            modifier = Modifier.alpha(if (loadingDismiss) 0.56f else 1f),
        )
        if (action.shouldOfferReminder()) {
            MiniActionButton(
                text = stringResource(R.string.commitment_action_remind),
                onClick = { onRemind(action.id) },
                enabled = !actionBusy,
                loading = loadingReminder,
                icon = Icons.Outlined.Notifications,
                style = MiniActionStyle.Ghost,
                modifier = Modifier.alpha(if (loadingReminder) 0.56f else 1f),
            )
        }
        if (showDraft && PERSON_ACTION_DRAFT_CTA_ENABLED && action.supportedDraftKind() != null) {
            MiniActionButton(
                text = stringResource(R.string.person_action_draft_action),
                onClick = { onOpenDraft(action.id) },
                enabled = !actionBusy,
                trailingChevron = true,
                modifier = Modifier
                    .alpha(if (loadingDraft) 0.56f else 1f)
                    .testTag("person-detail-action-draft-secondary-${action.id}"),
            )
        }
        MiniActionButton(
            text = stringResource(R.string.commitment_action_complete),
            onClick = { onComplete(action.id) },
            enabled = !actionBusy || loadingComplete,
            loading = loadingComplete,
            style = MiniActionStyle.Outline,
            modifier = Modifier.testTag("person-detail-action-complete-secondary-${action.id}"),
        )
    }
}

@StringRes
private fun draftPrimaryActionLabelRes(draftKind: String): Int =
    when (draftKind) {
        "reply" -> R.string.person_action_draft_primary_reply
        "reconnect_person" -> R.string.person_action_draft_primary_reconnect
        else -> R.string.person_action_draft_primary_follow_up
    }

private const val PERSON_ACTION_DRAFT_CTA_ENABLED = false

private fun PersonActionItemUi.resolveRawEventId(
    sourceCards: List<SourceEventCardProjection>,
): String? {
    val directCandidates = listOfNotNull(
        sourceEventId.cleanId(),
        sourceRef.rawIdFromSourceRef(),
        evidence?.sourceRef.rawIdFromSourceRef(),
        evidence?.id.takeIf { evidence?.kind == "source_event" }.cleanId(),
    )
    val refs = listOfNotNull(
        sourceRef.cleanId(),
        evidence?.sourceRef.cleanId(),
        evidence?.id.takeIf { evidence?.kind == "source_event" }.cleanId(),
    )
    val rawKeyCandidates = directCandidates.map { "raw:$it" }
    val typedRefCandidates = refs.map { ref -> "${sourceType.orEmpty()}:$ref" }
    val matchingCard = sourceCards.firstOrNull { card ->
        card.rawEventId in directCandidates ||
            card.sourceEventKey in refs ||
            card.sourceEventKey in rawKeyCandidates ||
            card.sourceEventKey in typedRefCandidates ||
            card.relatedSourceEventKeys.any { key ->
                key in refs || key in rawKeyCandidates || key in typedRefCandidates
            }
    }
    return directCandidates.firstOrNull { candidate ->
        matchingCard?.rawEventId == candidate ||
            matchingCard?.relatedSourceEventKeys?.contains("raw:$candidate") == true
    }
        ?: matchingCard?.rawEventId
        ?: directCandidates.firstOrNull()
}

private fun PersonActionItemUi.hasEvidenceLookup(): Boolean =
    evidence?.let { it.kind != null && it.id != null } == true

private fun String?.cleanId(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.rawIdFromSourceRef(): String? =
    cleanId()
        ?.takeIf { it.startsWith("raw:") }
        ?.removePrefix("raw:")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private enum class PersonTimelineFilter {
    ALL,
    EMAIL,
    CALL,
    MEETING,
}

@Composable
private fun TimelineFilterRow(
    selectedFilter: PersonTimelineFilter,
    onFilterSelect: (PersonTimelineFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = listOf(
        PersonTimelineFilter.ALL to stringResource(R.string.person_detail_filter_all),
        PersonTimelineFilter.CALL to stringResource(R.string.person_detail_filter_call),
        PersonTimelineFilter.EMAIL to stringResource(R.string.person_detail_filter_email),
        PersonTimelineFilter.MEETING to stringResource(R.string.person_detail_filter_meeting),
    )
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-source-filters"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters) { (filter, label) ->
            val selected = selectedFilter == filter
            Surface(
                modifier = Modifier
                    .defaultMinSize(minHeight = 30.dp)
                    .testTag("person-detail-filter-${filter.name.lowercase()}")
                    .clickable(role = Role.Button) { onFilterSelect(filter) },
                shape = MaterialTheme.shapes.extraSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun PersonTimelineFilter.matches(item: PersonTimelineItem): Boolean = when (this) {
    PersonTimelineFilter.ALL -> true
    PersonTimelineFilter.EMAIL -> item is PersonTimelineItem.SourceEvent && item.card.sourceType.isEmailSource()
    PersonTimelineFilter.CALL -> item is PersonTimelineItem.SourceEvent && item.card.sourceType.isCallSource()
    PersonTimelineFilter.MEETING -> when (item) {
        is PersonTimelineItem.SourceEvent -> item.card.sourceType.isMeetingTimelineSource()
        is PersonTimelineItem.ScheduleCandidate -> true
        is PersonTimelineItem.ConfirmedSchedule -> true
    }
}

@Composable
private fun SectionHeader(text: String) {
    CompactSectionHeader(
        text = text,
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
                        contentDescription = stringResource(R.string.action_back),
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
                        personId = "person-alice",
                        eventCount = 2,
                        emailInteractionCount = 1,
                        callInteractionCount = 0,
                        meetingCount = 1,
                        pendingCommitmentCount = 1,
                    )
                }
                items(
                    listOf(
                        SourceEventCardProjection(
                            sourceEventKey = "preview-meeting",
                            sourceType = "google_calendar",
                            rawEventId = null,
                            occurredAt = kotlinx.datetime.Clock.System.now(),
                            title = "Q2 Planning Meeting",
                            snippet = "Send contract draft after the meeting.",
                            myActions = listOf(
                                PersonDetailCommitmentSummary(
                                    title = "Send contract draft",
                                    itemType = CommitmentItemType.ACTION,
                                    direction = "give",
                                    status = "pending",
                                ),
                            ),
                        ),
                    ),
                ) { card ->
                    SourceEventCardRow(
                        card = card,
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
