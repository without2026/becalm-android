package com.becalm.android.ui.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.ContactRow
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.MainTabHeaderActions
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
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.FirstMemoryActivationContent
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
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
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.persons_title),
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
            if (state.showOfflineBadge) {
                OfflineBadge(lastSyncAt = state.offlineLastSyncAt)
            }
            if (hasUnassignedEvents) {
                MatchingRequiredBanner(
                    count = state.unassignedEvents.size,
                    onClick = onOpenUnassigned,
                )
            }
            if (showSearch) {
                BecalmTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.persons_search_placeholder),
                    leadingIcon = Icons.Filled.Search,
                    compact = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("persons-search-input")
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            when {
                state.loading -> {
                    PersonListSkeleton()
                }
                state.people.isEmpty() -> {
                    if (hasUnassignedEvents) {
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
        PersonSectionKind.RECENT_CONTACTS -> "recent-people"
    }

private val PersonSectionKind.titleRes: Int
    get() = when (this) {
        PersonSectionKind.PENDING_COMMITMENTS -> R.string.persons_section_pending_commitments
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
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

@Composable
private fun PersonRowItem(
    person: PersonRow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayLabel = person.displayLabel.ifBlank { stringResource(R.string.persons_unidentified) }
    ContactRow(
        headline = displayLabel,
        metadata = person.interactionCount
            .takeIf { it > 0 }
            ?.let { stringResource(R.string.persons_interactions_count, it) },
        supportingText = person.topAction?.title?.takeIf { it.isNotBlank() }
            ?: person.lastInteractionSnippet?.takeIf { it.isNotBlank() },
        attentionLabel = person.topAction?.primaryVerb?.takeIf { it.isNotBlank() }
            ?: person.pendingCommitmentCount
            .takeIf { it > 0 }
            ?.let { stringResource(R.string.persons_pending_commitments_fmt, it) },
        onClick = onClick,
        modifier = modifier,
    ) {
        PersonAvatar(seed = displayLabel)
    }
}

@Composable
private fun PersonAvatar(seed: String) {
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.64f),
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f),
    )
    val index = (seed.hashCode() and Int.MAX_VALUE) % colors.size
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors[index])
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitial(seed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
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
