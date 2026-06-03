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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.ui.actions.PersonActionItemUi
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
    onFirstMemoryFollowUpAction: (FirstMemoryFollowUpAction) -> Unit = {},
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
                onFirstMemoryFollowUpAction = onFirstMemoryFollowUpAction,
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
    onFirstMemoryFollowUpAction: (FirstMemoryFollowUpAction) -> Unit,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(PersonTimelineFilter.ALL) }
    val sourceCards = remember(selectedFilter, state.sourceEventCards) {
        state.sourceEventCards.filter(selectedFilter::matches)
    }
    val timelineHeader = stringResource(
        R.string.person_detail_timeline_section_fmt,
        sourceCards.size,
    )

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
        if (state.topActions.isNotEmpty()) {
            item(key = "next-actions") {
                PersonNextActionsPanel(
                    actions = state.topActions,
                    onActionClick = { action ->
                        action.sourceEventId?.let(onEventTap)
                    },
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
    onActionClick: (PersonActionItemUi) -> Unit,
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
            actions.take(3).forEach { action ->
                PersonActionRecommendationRow(
                    action = action,
                    onClick = { onActionClick(action) },
                )
            }
        }
    }
}

@Composable
private fun PersonActionRecommendationRow(
    action: PersonActionItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("person-detail-action-${action.id}")
            .clickable(
                enabled = action.sourceEventId != null,
                role = Role.Button,
                onClick = onClick,
            ),
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
            action.sourceType?.let { EventSourceBadge(sourceType = it) }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.bodyMedium,
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
            Text(
                text = action.primaryVerb,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
