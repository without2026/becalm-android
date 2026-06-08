package com.becalm.android.ui.persons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
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
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.EventSourceBadge
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
        title = state.displayName ?: stringResource(R.string.persons_unidentified),
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
        val hasAnyInteractions = state.sourceEventCards.isNotEmpty()
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
    val sourceCards = remember(selectedFilter, state.sourceEventCards) {
        state.sourceEventCards.filter(selectedFilter::matches)
    }
    val timelineHeader = stringResource(
        R.string.person_detail_timeline_section_fmt,
        sourceCards.size,
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
        val firstMemoryCards = sourceCards.filter { it.firstMemoryOrigin != null }
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
        if (sourceCards.isNotEmpty()) {
            items(
                items = sourceCards,
                key = { card -> card.sourceEventKey },
            ) { card ->
                SourceEventCardRow(
                    card = card,
                    onEventTap = onEventTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            item(key = "timeline-empty") {
                EmptyState(title = stringResource(R.string.person_detail_timeline_filter_empty))
            }
        }
        if (state.canLoadMoreTimeline) {
            item(key = "load-more-timeline") {
                TextButton(
                    onClick = onLoadMoreTimeline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("person-detail-load-more"),
                ) {
                    Text(text = stringResource(R.string.person_detail_load_more))
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
    RecommendationPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-relationship-recall"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.person_detail_recall_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.person_detail_recall_body_fmt,
                    cue.daysSinceLastInteraction,
                    lastRecordTitle,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
            )
            cue.lastCard.rawEventId?.let { rawEventId ->
                TextButton(
                    onClick = { onEventTap(rawEventId) },
                    modifier = Modifier.testTag("person-detail-recall-open-last"),
                ) {
                    Text(text = stringResource(R.string.person_detail_recall_open_last))
                }
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
            TextButton(
                onClick = onRetry,
                enabled = !retrying,
                modifier = Modifier.testTag("person-detail-manual-memory-sync-retry"),
            ) {
                Text(
                    text = stringResource(
                        if (retrying) {
                            R.string.person_detail_manual_memory_sync_retrying
                        } else {
                            R.string.person_detail_manual_memory_sync_retry
                        },
                    ),
                )
            }
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
    RecommendationPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-next-action-panel"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.person_detail_next_action_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
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
    val canOpenRawEvent = action.resolveRawEventId(sourceCards) != null
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
        if (evidenceKind != null && evidenceId != null) {
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
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
        },
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (primary) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
            },
        ),
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
                    horizontal = if (primary) 14.dp else 12.dp,
                    vertical = if (primary) 12.dp else 8.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (primary) 8.dp else 5.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                action.sourceType?.let { EventSourceBadge(sourceType = it) }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = action.title,
                        style = if (primary) {
                            MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = action.shortReason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
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
                    canOpenEvidence = canOpenEvidence,
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
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val draftKind = action.supportedDraftKind()
            if (draftKind != null) {
                BecalmButton(
                    text = stringResource(draftPrimaryActionLabelRes(draftKind)),
                    onClick = { onOpenDraft(action.id) },
                    enabled = !actionBusy,
                    loading = loadingDraft,
                    variant = BecalmButtonVariant.Primary,
                    modifier = Modifier.testTag("person-detail-action-draft-${action.id}"),
                )
            }
            if (canOpenEvidence) {
                BecalmButton(
                    text = stringResource(R.string.person_action_evidence_view),
                    onClick = onOpenEvidence,
                    enabled = !actionBusy,
                    loading = loadingEvidence,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier.testTag("person-detail-action-evidence-${action.id}"),
                )
            }
        }
        PersonSecondaryActionControls(
            action = action,
            loadingReminder = loadingReminder,
            loadingComplete = loadingComplete,
            loadingDismiss = loadingDismiss,
            loadingDraft = loadingDraft,
            actionBusy = actionBusy,
            showDraft = false,
            onComplete = onComplete,
            onDismiss = onDismiss,
            onRemind = onRemind,
            onOpenDraft = onOpenDraft,
        )
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
        TextButton(
            onClick = { onDismiss(action.id) },
            enabled = !actionBusy,
            modifier = Modifier.alpha(if (loadingDismiss) 0.56f else 1f),
        ) {
            Text(text = stringResource(R.string.schedule_action_dismiss))
        }
        if (action.shouldOfferReminder()) {
            TextButton(
                onClick = { onRemind(action.id) },
                enabled = !actionBusy,
                modifier = Modifier.alpha(if (loadingReminder) 0.56f else 1f),
            ) {
                Text(text = stringResource(R.string.commitment_action_remind))
            }
        }
        if (showDraft && action.supportedDraftKind() != null) {
            TextButton(
                onClick = { onOpenDraft(action.id) },
                enabled = !actionBusy,
                modifier = Modifier
                    .alpha(if (loadingDraft) 0.56f else 1f)
                    .testTag("person-detail-action-draft-secondary-${action.id}"),
            ) {
                Text(text = stringResource(R.string.person_action_draft_action))
            }
        }
        TextButton(
            onClick = { onComplete(action.id) },
            enabled = !actionBusy,
            modifier = Modifier.alpha(if (loadingComplete) 0.56f else 1f),
        ) {
            Text(text = stringResource(R.string.commitment_action_complete))
        }
    }
}

@StringRes
private fun draftPrimaryActionLabelRes(draftKind: String): Int =
    when (draftKind) {
        "reply" -> R.string.person_action_draft_primary_reply
        "reconnect_person" -> R.string.person_action_draft_primary_reconnect
        else -> R.string.person_action_draft_primary_follow_up
    }

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
    return sourceCards.firstOrNull { card ->
        card.rawEventId in directCandidates ||
            card.sourceEventKey in refs ||
            card.sourceEventKey in directCandidates.map { "raw:$it" } ||
            card.sourceEventKey in refs.map { ref -> "${sourceType.orEmpty()}:$ref" }
    }?.rawEventId
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
        PersonTimelineFilter.EMAIL to stringResource(R.string.person_detail_filter_email),
        PersonTimelineFilter.CALL to stringResource(R.string.person_detail_filter_call),
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
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onFilterSelect(filter) },
                label = {
                    Text(text = label, style = MaterialTheme.typography.labelMedium)
                },
                shape = MaterialTheme.shapes.extraSmall,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.testTag("person-detail-filter-${filter.name.lowercase()}"),
            )
        }
    }
}

private fun PersonTimelineFilter.matches(card: SourceEventCardProjection): Boolean = when (this) {
    PersonTimelineFilter.ALL -> true
    PersonTimelineFilter.EMAIL -> card.sourceType.isEmailSource()
    PersonTimelineFilter.CALL -> card.sourceType.isCallSource()
    PersonTimelineFilter.MEETING -> card.sourceType.isMeetingTimelineSource()
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
