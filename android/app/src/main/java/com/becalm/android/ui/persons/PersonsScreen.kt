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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.ContactRow
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.MainTabHeaderActions
import com.becalm.android.ui.components.MainTabStatusHeader
import com.becalm.android.ui.components.SkeletonBlock
import com.becalm.android.ui.components.becalmSkeletonColor
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.evidence.EvidenceImportFloatingActionButton
import com.becalm.android.ui.evidence.EvidenceImportSheet
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.becalm.android.ui.evidence.ManualTextEvidenceDialog
import com.becalm.android.ui.evidence.rememberEvidenceImportActions
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.MainTabHeaderViewModel
import com.becalm.android.ui.navigation.BecalmRoute
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
    headerViewModel: MainTabHeaderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val evidenceImportState by evidenceImportViewModel.state.collectAsStateWithLifecycle()
    val headerState by headerViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)
    val importMessage = evidenceImportState.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(importMessage, snackbarHostState, evidenceImportViewModel::onMessageShown)

    val evidenceImportActions = rememberEvidenceImportActions(evidenceImportViewModel)

    PersonsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onQueryChange = viewModel::onQueryChange,
        onPersonClick = { personId ->
            navController.navigate(BecalmRoute.PersonDetail(personId).path)
        },
        onOpenUnassigned = {
            navController.navigate(BecalmRoute.PersonsUnassigned.path)
        },
        headerState = headerState,
        onOpenSettings = { navController.navigate(BecalmRoute.Settings.path) },
        onMessageScreenshotImport = evidenceImportActions.openMessageScreenshotPicker,
        onMeetingAudioImport = evidenceImportActions.openMeetingAudioPicker,
        onMeetingTranscriptImport = evidenceImportActions.openMeetingTranscriptPicker,
        onManualTextImport = evidenceImportActions.submitManualText,
    )
}

@Composable
public fun PersonsScreenContent(
    state: PersonsUiState,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onPersonClick: (String) -> Unit,
    onOpenUnassigned: () -> Unit = {},
    headerState: MainTabHeaderState = MainTabHeaderState(),
    onOpenSettings: () -> Unit = {},
    onMessageScreenshotImport: () -> Unit = {},
    onMeetingAudioImport: () -> Unit = {},
    onMeetingTranscriptImport: () -> Unit = {},
    onManualTextImport: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var showManualTextDialog by rememberSaveable { mutableStateOf(false) }
    val hasUnassignedEvents = state.unassignedEvents.isNotEmpty()
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
            EvidenceImportFloatingActionButton(onClick = { showImportSheet = true })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            MainTabStatusHeader(state = headerState)
            if (state.showOfflineBadge) {
                OfflineBadge(lastSyncAt = state.offlineLastSyncAt)
            }
            if (hasUnassignedEvents) {
                MatchingRequiredBanner(
                    count = state.unassignedEvents.size,
                    onClick = onOpenUnassigned,
                )
            }
            BecalmTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.persons_search_placeholder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("persons-search-input")
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
                        EmptyState(
                            title = stringResource(R.string.persons_empty_title),
                            message = stringResource(R.string.persons_empty_message),
                            icon = Icons.Filled.Person,
                        )
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

    if (showImportSheet) {
        EvidenceImportSheet(
            onDismiss = { showImportSheet = false },
            onMessageScreenshotImport = {
                showImportSheet = false
                onMessageScreenshotImport()
            },
            onMeetingAudioImport = {
                showImportSheet = false
                onMeetingAudioImport()
            },
            onMeetingTranscriptImport = {
                showImportSheet = false
                onMeetingTranscriptImport()
            },
            onManualTextImport = {
                showImportSheet = false
                showManualTextDialog = true
            },
        )
    }
    if (showManualTextDialog) {
        ManualTextEvidenceDialog(
            onDismiss = { showManualTextDialog = false },
            onSubmit = {
                showManualTextDialog = false
                onManualTextImport(it)
            },
        )
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
    ContactRow(
        headline = buildDisplayHeadline(person),
        metadata = person.interactionCount
            .takeIf { it > 0 }
            ?.let { stringResource(R.string.persons_interactions_count, it) },
        attentionLabel = person.pendingCommitmentCount
            .takeIf { it > 0 }
            ?.let { stringResource(R.string.persons_pending_commitments_fmt, it) },
        onClick = onClick,
        modifier = modifier,
    ) {
        PersonAvatar(person = person)
    }
}

@Composable
private fun PersonAvatar(person: PersonRow) {
    val seed = person.displayLabel.ifBlank { person.personId }
    val colors = listOf(
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer,
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

private fun buildDisplayHeadline(person: PersonRow): String {
    return person.displayLabel
}

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
                    placeholder = "Search people…",
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
