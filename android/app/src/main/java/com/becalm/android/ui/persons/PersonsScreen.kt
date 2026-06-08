package com.becalm.android.ui.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.ui.actions.PersonActionFeedStatusLine
import com.becalm.android.ui.actions.personActionFeedCompactStatusMessage
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTopChrome
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.MainTabHeaderActions
import com.becalm.android.ui.components.MainTabCompactSourceAttentionLine
import com.becalm.android.ui.components.MainTabStatusHeader
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.SkeletonBlock
import com.becalm.android.ui.components.becalmSkeletonColor
import com.becalm.android.ui.components.hasSourceWarningForCompactLine
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
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.FirstMemoryActivationContent
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/**
 * Persons list screen — enriched display names and interaction counts.
 *
 * spec: SRC-001, SRC-002
 *
 * Primary VM: [PersonsViewModel]
 * Navigation entry: [BecalmRoute.Persons]
 * Navigation exit: [BecalmRoute.PersonDetail] on person tap
 */
@Composable
public fun PersonsScreen(
    navController: NavHostController,
    viewModel: PersonsViewModel = hiltViewModel(),
    evidenceImportViewModel: EvidenceImportViewModel = hiltViewModel(),
    headerViewModel: MainTabHeaderViewModel = hiltViewModel(),
    showShareImportNotice: Boolean = false,
    onShareImportNoticeShown: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
    val headerState by headerViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val shareImportMessage = if (showShareImportNotice) {
        stringResource(R.string.share_import_completed_message)
    } else {
        null
    }
    HandleSnackbarMessage(shareImportMessage, snackbarHostState, onShareImportNoticeShown)
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)
    val importMessage = evidenceImportState.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(importMessage, snackbarHostState, evidenceImportViewModel::onMessageShown)
    LaunchedEffect(viewModel.effects) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PersonsEffect.NavigateToPersonDetail ->
                    navController.navigate(BecalmRoute.PersonDetail(effect.personId).path)
            }
        }
    }
    LaunchedEffect(evidenceImportState.foregroundReviewRequestKey, state.unassignedEvents.isNotEmpty()) {
        val requestKey = evidenceImportState.foregroundReviewRequestKey ?: return@LaunchedEffect
        if (state.unassignedEvents.isEmpty()) return@LaunchedEffect
        navController.navigate(BecalmRoute.PersonsUnassigned.path) {
            launchSingleTop = true
        }
        evidenceImportViewModel.onForegroundReviewOpened(requestKey)
    }

    val evidenceImportActions = rememberEvidenceImportActions(evidenceImportViewModel)

    PersonsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onPersonClick = { personId ->
            viewModel.onPersonSelected(personId)
            navController.navigate(BecalmRoute.PersonDetail(personId).path)
        },
        onOpenUnassigned = {
            navController.navigate(BecalmRoute.PersonsUnassigned.path)
        },
        onOpenSettings = { navController.navigate(BecalmRoute.Settings.path) },
        headerState = headerState,
        onOpenSources = {
            navController.navigate(BecalmRoute.SettingsSources.path) {
                launchSingleTop = true
            }
        },
        onOpenSource = { sourceType ->
            navController.navigate(BecalmRoute.SourceDetail(sourceType).path) {
                launchSingleTop = true
            }
        },
        onOpenProcessingStatus = {
            navController.navigate(BecalmRoute.ProcessingStatus.path)
        },
        onMessageScreenshotImport = evidenceImportActions.openMessageScreenshotPicker,
        onMeetingAudioImport = evidenceImportActions.openMeetingAudioPicker,
        evidenceImportState = evidenceImportState,
        onConsentRequiredClick = { navController.navigate(BecalmRoute.Settings.path) },
        onMeetingSelfSpeakerSelected = evidenceImportViewModel::onMeetingSelfSpeakerSelected,
        onMeetingCounterpartySpeakerSelected = evidenceImportViewModel::onMeetingCounterpartySpeakerSelected,
        onMeetingSpeakerReviewConfirmed = evidenceImportViewModel::onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = evidenceImportViewModel::onMeetingSpeakerReviewCancelled,
        onMeetingSpeakerReviewAction = evidenceImportViewModel::onMeetingSpeakerReviewAction,
        onMeetingPreviewLoadingCancelled = evidenceImportViewModel::onMeetingPreviewLoadingCancelled,
        onRetryFailedImports = evidenceImportViewModel::onRetryFailedImports,
        onFirstMemoryOriginChange = viewModel::onFirstMemoryOriginChange,
        onFirstMemoryPersonNameChange = viewModel::onFirstMemoryPersonNameChange,
        onFirstMemoryPromiseTextChange = viewModel::onFirstMemoryPromiseTextChange,
        onFirstMemoryKindChange = viewModel::onFirstMemoryKindChange,
        onFirstMemoryDueHintChange = viewModel::onFirstMemoryDueHintChange,
        onSaveFirstMemory = viewModel::onSaveFirstMemory,
    )
}

@Composable
public fun PersonsScreenContent(
    state: PersonsUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenUnassigned: () -> Unit = {},
    headerState: MainTabHeaderState = MainTabHeaderState(),
    onOpenSettings: () -> Unit = {},
    onOpenSources: () -> Unit = onOpenSettings,
    onOpenSource: ((String) -> Unit)? = null,
    onOpenProcessingStatus: () -> Unit = {},
    onMessageScreenshotImport: () -> Unit = {},
    onMeetingAudioImport: () -> Unit = {},
    evidenceImportState: EvidenceImportUiState = EvidenceImportUiState(),
    onConsentRequiredClick: () -> Unit = onOpenSettings,
    onMeetingSelfSpeakerSelected: (String) -> Unit = {},
    onMeetingCounterpartySpeakerSelected: (String) -> Unit = {},
    onMeetingSpeakerReviewConfirmed: () -> Unit = {},
    onMeetingSpeakerReviewCancelled: () -> Unit = {},
    onMeetingSpeakerReviewAction: () -> Unit = {},
    onMeetingPreviewLoadingCancelled: () -> Unit = {},
    onRetryFailedImports: () -> Unit = {},
    onFirstMemoryOriginChange: (FirstMemoryOrigin) -> Unit = {},
    onFirstMemoryPersonNameChange: (String) -> Unit = {},
    onFirstMemoryPromiseTextChange: (String) -> Unit = {},
    onFirstMemoryKindChange: (FirstMemoryKind) -> Unit = {},
    onFirstMemoryDueHintChange: (String) -> Unit = {},
    onSaveFirstMemory: () -> Unit = {},
) {
    val evidenceImportController = rememberEvidenceImportSheetController()
    val hasUnassignedEvents = state.unassignedEvents.isNotEmpty()
    val showSearch = state.loading ||
        state.people.isNotEmpty() ||
        hasUnassignedEvents ||
        state.query.isNotBlank()
    val showActionFirstHeader = !state.loading &&
        (state.people.isNotEmpty() || state.query.isNotBlank())
    val hasActionRows = state.people.any { it.topAction != null }
    val showCompactMatchingRequired = !state.loading && hasUnassignedEvents && hasActionRows
    val showEvidenceImportFab = !state.loading &&
        (!hasActionRows || state.query.isNotBlank() || (hasUnassignedEvents && !showCompactMatchingRequired))
    val hasSourceWarning = headerState.hasSourceWarningForCompactLine()
    val actionFeedStatus = state.actionFeedStatus
    val actionFeedStatusMessage = actionFeedStatus?.let { personActionFeedCompactStatusMessage(it) }
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.persons_title),
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
            if (state.showOfflineBadge) {
                OfflineBadge(lastSyncAt = state.offlineLastSyncAt)
            }
            if (hasUnassignedEvents && !showCompactMatchingRequired) {
                MatchingRequiredBanner(
                    count = state.unassignedEvents.size,
                    onClick = onOpenUnassigned,
                )
            }
            if (showActionFirstHeader) {
                PersonsActionFirstHeader()
            }
            if (showSearch) {
                BecalmTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.persons_search_placeholder),
                    leadingIcon = Icons.Filled.Search,
                    trailingIcon = if (state.query.isNotBlank()) {
                        {
                            IconButton(
                                onClick = { onQueryChange("") },
                                modifier = Modifier.testTag("persons-search-clear"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.persons_search_clear),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("persons-search-input")
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (showCompactMatchingRequired) {
                MatchingRequiredStatusLine(
                    count = state.unassignedEvents.size,
                    onClick = onOpenUnassigned,
                )
            }
            if (hasSourceWarning) {
                MainTabCompactSourceAttentionLine(
                    state = headerState,
                    onOpenSources = onOpenSources,
                    onOpenSource = onOpenSource,
                    supportingStatusText = actionFeedStatusMessage,
                    onOpenSupportingStatus = onOpenProcessingStatus,
                    testTagPrefix = "persons-source",
                )
            } else if (actionFeedStatus != null) {
                PersonActionFeedStatusLine(
                    status = actionFeedStatus,
                    onOpenProcessingStatus = onOpenProcessingStatus,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    testTag = "persons-action-feed-statusline",
                    actionTestTag = "persons-action-feed-status-action",
                )
            }
            MainTabStatusHeader(
                state = headerState,
                onOpenSettings = onOpenSettings,
                onOpenSources = onOpenSources,
                onOpenSource = onOpenSource,
                showOverallIndicator = !hasSourceWarning && actionFeedStatus == null,
                showSourceAttentionBanner = false,
                showSourceStatusStrip = !hasSourceWarning && actionFeedStatus == null,
            )
            when {
                state.loading -> {
                    PersonListSkeleton()
                }
                state.people.isEmpty() -> {
                    if (state.query.isNotBlank()) {
                        PersonsSearchEmpty()
                    } else if (hasUnassignedEvents) {
                        PersonList(
                            state = state,
                            onPersonClick = onPersonClick,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("persons-empty-first-memory"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            item(key = "first-memory-empty") {
                                FirstMemoryActivationContent(
                                    state = state.firstMemory,
                                    onOriginChange = onFirstMemoryOriginChange,
                                    onPersonNameChange = onFirstMemoryPersonNameChange,
                                    onPromiseTextChange = onFirstMemoryPromiseTextChange,
                                    onKindChange = onFirstMemoryKindChange,
                                    onDueHintChange = onFirstMemoryDueHintChange,
                                    onSave = onSaveFirstMemory,
                                    onSkip = {},
                                    showSkip = false,
                                )
                            }
                        }
                    }
                }
                else -> {
                    PersonList(
                        state = state,
                        onPersonClick = onPersonClick,
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
        onMeetingCounterpartySpeakerSelected = onMeetingCounterpartySpeakerSelected,
        onMeetingSpeakerReviewConfirmed = onMeetingSpeakerReviewConfirmed,
        onMeetingSpeakerReviewCancelled = onMeetingSpeakerReviewCancelled,
        onMeetingSpeakerReviewAction = onMeetingSpeakerReviewAction,
        onMeetingPreviewLoadingCancelled = onMeetingPreviewLoadingCancelled,
        onRetryFailedImports = onRetryFailedImports,
        onReviewRequiredClick = onOpenUnassigned,
        onStatusDetailsClick = onOpenProcessingStatus,
        onConsentRequiredClick = onConsentRequiredClick,
    )
}

@Composable
private fun MatchingRequiredStatusLine(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(InkMistLine2, RoundedCornerShape(12.dp))
            .border(1.dp, InkMistLine, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("persons-matching-required-statusline"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(InkMistWarn),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = InkMistInk,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append(stringResource(R.string.person_matching_required_status_prefix_fmt, count))
                }
                append(stringResource(R.string.person_matching_required_status_suffix))
            },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = InkMistGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = MaterialTheme.typography.labelMedium.lineHeight,
        )
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier
                .heightIn(min = 32.dp)
                .testTag("persons-matching-required-status-action"),
        ) {
            Text(
                text = stringResource(R.string.person_matching_required_banner_action),
                color = InkMistTake,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

@Composable
private fun MatchingRequiredBanner(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EvidenceCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.person_matching_required_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.person_matching_required_banner_body, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClick) {
                Text(text = stringResource(R.string.person_matching_required_banner_action))
            }
        }
    }
}

@Composable
private fun PersonsSearchEmpty(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("persons-search-empty"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = stringResource(R.string.persons_search_empty),
            modifier = Modifier.padding(top = 28.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = InkMistGray,
        )
    }
}

@Composable
private fun PersonsActionFirstHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.persons_home_headline),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = InkMistInk,
        )
        Text(
            text = stringResource(R.string.persons_home_body),
            style = MaterialTheme.typography.bodySmall,
            color = InkMistGray,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Static placeholder rows shown during the cold-start no-data window. Matches
 * the [PersonRowItem] geometry (avatar circle + name + secondary metadata)
 * so real rows land in place without layout pop. No motion — DESIGN.md
 * Process-Hidden Rule (first-line surface).
 */
@Composable
private fun PersonListSkeleton(modifier: Modifier = Modifier) {
    val avatarColor = becalmSkeletonColor()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(count = 3, key = { index -> "persons-skeleton-$index" }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(avatarColor),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(0.5f).height(14.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(0.3f).height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun PersonList(
    state: PersonsUiState,
    onPersonClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("persons-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        state.personSections.forEach { section ->
            personSection(
                key = section.kind.key,
                titleRes = section.kind.titleRes,
                people = section.people,
                onPersonClick = onPersonClick,
            )
        }
        if (state.unassignedEvents.isNotEmpty()) {
            item(key = "unassigned-spacer") {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
        if (state.unassignedEvents.isNotEmpty()) {
            item(key = "unassigned-header") {
                PersonListSectionHeader(text = stringResource(R.string.persons_unassigned_title))
            }
            items(items = state.unassignedEvents, key = { it.id }) { event ->
                UnassignedEventRow(
                    event = event,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
        }
    }
}

private val PersonSectionKind.key: String
    get() = when (this) {
        PersonSectionKind.PENDING_COMMITMENTS -> "pending-people"
        PersonSectionKind.THIS_WEEK_ACTIONS -> "this-week-people"
        PersonSectionKind.RECENT_CONTACTS -> "recent-people"
    }

private val PersonSectionKind.titleRes: Int
    get() = when (this) {
        PersonSectionKind.PENDING_COMMITMENTS -> R.string.persons_section_pending_commitments
        PersonSectionKind.THIS_WEEK_ACTIONS -> R.string.persons_section_this_week
        PersonSectionKind.RECENT_CONTACTS -> R.string.persons_section_recent_contacts
    }

private fun androidx.compose.foundation.lazy.LazyListScope.personSection(
    key: String,
    titleRes: Int,
    people: List<PersonRow>,
    onPersonClick: (String) -> Unit,
) {
    if (people.isEmpty()) return
    item(key = "$key-header") {
        PersonListSectionHeader(text = stringResource(titleRes))
    }
    items(items = people, key = { "$key-${it.personId}" }) { person ->
        PersonRowItem(
            person = person,
            onClick = { onPersonClick(person.personId) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun PersonListSectionHeader(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = InkMistGray2,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(InkMistLine),
        )
    }
}

@Composable
private fun PersonRowItem(
    person: PersonRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (person.topAction != null) {
        ActionFirstPersonRowItem(
            person = person,
            action = person.topAction,
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    QuietPersonRowItem(
        person = person,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ActionFirstPersonRowItem(
    person: PersonRow,
    action: PersonActionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayLabel = person.displayLabel.ifBlank { stringResource(R.string.persons_unidentified) }
    val dueLabel = action.dueHint?.trim()?.takeIf { it.isNotBlank() }
        ?: action.dueAt?.let { personActionDueLabel(it) }
    val actionLabel = action.rowActionLabel()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("persons-action-row-${action.id}")
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(14.dp))
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrototypePersonAvatar(
                seed = displayLabel,
                dotColor = action.urgencyDotColor(),
            )
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                PersonNameLine(person = person, displayLabel = displayLabel)
                Text(
                    text = listOfNotNull(
                        dueLabel,
                        actionLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = InkMistInk2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = InkMistGray2,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(InkMistLine2),
        )
    }
}

@Composable
private fun QuietPersonRowItem(
    person: PersonRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayLabel = person.displayLabel.ifBlank { stringResource(R.string.persons_unidentified) }
    val supportingText = person.lastInteractionSnippet?.takeIf { it.isNotBlank() }
    val attentionLabel = person.pendingCommitmentCount
        .takeIf { it > 0 }
        ?.let { stringResource(R.string.persons_pending_commitments_fmt, it) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrototypePersonAvatar(seed = displayLabel, dotColor = null)
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                PersonNameLine(person = person, displayLabel = displayLabel)
                val secondary = listOfNotNull(attentionLabel, supportingText).joinToString(" · ")
                if (secondary.isNotBlank()) {
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        color = if (attentionLabel != null) InkMistInk2 else InkMistGray2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                } else {
                    person.interactionCount
                        .takeIf { it > 0 }
                        ?.let { stringResource(R.string.persons_interactions_count, it) }
                        ?.let { meta ->
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                ),
                                color = InkMistGray2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                }
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = InkMistGray2,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun PersonNameLine(
    person: PersonRow,
    displayLabel: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = displayLabel,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = InkMistInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        person.roleLine()?.let { role ->
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = role,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                color = InkMistGray2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PrototypePersonAvatar(
    seed: String,
    dotColor: Color?,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(InkMistAvatar)
            .border(1.dp, InkMistLine, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(seed),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = InkMistGray,
        )
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun personActionDueLabel(dueAt: Instant): String {
    val zone = TimeZone.currentSystemDefault()
    val today = Clock.System.now().toLocalDateTime(zone).date
    val dueDate = dueAt.toLocalDateTime(zone).date
    val days = today.daysUntil(dueDate)
    return when {
        days < 0 -> stringResource(R.string.persons_action_overdue_fmt, -days)
        days == 0 -> stringResource(R.string.persons_action_due_today)
        days == 1 -> stringResource(R.string.persons_action_due_tomorrow)
        else -> stringResource(R.string.persons_action_due_soon_fmt, days)
    }
}

private fun PersonRow.roleLine(): String? =
    listOfNotNull(companyName, jobTitle)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" · ")
        .takeIf(String::isNotBlank)
        ?.let { "· $it" }

private fun PersonActionSummary.rowActionLabel(): String {
    val cleanTitle = title.trim()
    if (cleanTitle.isNotEmpty()) return cleanTitle
    return primaryVerb.trim()
}

private fun PersonActionSummary.urgencyDotColor(): Color = when {
    urgencyScore >= 80.0 -> InkMistWarn
    urgencyScore >= 60.0 -> InkMistGive
    else -> InkMistDone
}

@Composable
private fun UnassignedEventRow(
    event: UnassignedEventSummary,
    modifier: Modifier = Modifier,
) {
    EvidenceCard(
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title ?: stringResource(R.string.raw_event_detail_no_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(sourcePresentationFor(event.sourceType).labelRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OfflineBadge(lastSyncAt: Instant?) {
    val copy = if (lastSyncAt == null) {
        stringResource(R.string.persons_offline_badge_no_sync)
    } else {
        stringResource(R.string.persons_offline_badge_fmt, lastSyncAt.toHourMinuteLabel())
    }
    EvidenceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = copy,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun avatarInitial(seed: String): String =
    seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

private fun Instant.toHourMinuteLabel(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

private val InkMistInk = Color(0xFF1A1F2E)
private val InkMistInk2 = Color(0xFF3A4358)
private val InkMistGray = Color(0xFF5A6478)
private val InkMistGray2 = Color(0xFF9AA4BC)
private val InkMistLine = Color(0xFFE5E9F0)
private val InkMistLine2 = Color(0xFFF0F3F8)
private val InkMistAvatar = Color(0xFFEDEDEE)
private val InkMistWarn = Color(0xFFBC8071)
private val InkMistGive = Color(0xFFC0967A)
private val InkMistTake = Color(0xFF6589A1)
private val InkMistDone = Color(0xFF7E948A)

@PreviewLightDark
@Composable
private fun PreviewPersonsScreenPopulated() {
    BecalmTheme {
        BecalmScaffold(title = "People") { padding ->
            Column(modifier = Modifier.padding(padding)) {
                BecalmTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = stringResource(R.string.persons_search_placeholder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(
                        listOf(
            PersonRow(
                personId = "ref1",
                displayName = "Alice Kim",
                lastInteractionAt = null,
                interactionCount = 12,
                lastInteractionSnippet = "금요일까지 제안서 초안을 보내기로 했어요",
            ),
                            PersonRow(
                                personId = "ref2",
                                displayName = "Bob Lee",
                                lastInteractionAt = null,
                                interactionCount = 5,
                            ),
                            PersonRow(
                                personId = "ref3",
                                displayName = "Carol Park",
                                lastInteractionAt = null,
                                interactionCount = 3,
                            ),
                        ),
                    ) { person ->
                        PersonRowItem(
                            person = person,
                            onClick = {},
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
