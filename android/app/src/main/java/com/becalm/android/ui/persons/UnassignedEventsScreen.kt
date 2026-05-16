package com.becalm.android.ui.persons

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.ContactRow
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.IngestionTimestamp
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Unassigned events screen — raw events where `person_ref IS NULL`.
 *
 * Reuses [PersonsViewModel] which provides the enrichment list. Unassigned events
 * are events without a resolved person_ref — currently shown as an empty state
 * until a dedicated DAO query (SRC-008 extension) is available.
 *
 * spec: SRC-008
 *
 * Primary VM: [PersonsViewModel]
 * Navigation entry: [BecalmRoute.PersonsUnassigned]
 * Navigation exit: back to [BecalmRoute.Persons]
 */
@Composable
public fun UnassignedEventsScreen(
    navController: NavHostController,
    viewModel: PersonsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)

    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    BecalmScaffold(
        title = stringResource(R.string.person_match_review_heading),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    ) { padding ->
        UnassignedEventsContent(
            loading = state.loading,
            unassignedEvents = state.unassignedEvents,
            matchChoices = state.matchChoices,
            onManualMatch = viewModel::onManualMatch,
            onSelfMatch = viewModel::onSelfMatch,
            onNotSelfMatch = viewModel::onNotSelfMatch,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun UnassignedEventsContent(
    loading: Boolean,
    unassignedEvents: List<UnassignedEventSummary>,
    modifier: Modifier = Modifier,
    matchChoices: List<PersonMatchChoiceRow> = emptyList(),
    onManualMatch: (UnassignedEventSummary, String, String) -> Unit = { _, _, _ -> },
    onSelfMatch: (UnassignedEventSummary) -> Unit = {},
    onNotSelfMatch: (UnassignedEventSummary) -> Unit = {},
) {
    var filter by remember { mutableStateOf(MatchQueueFilter.RECOMMENDED) }
    var completedIds by remember { mutableStateOf(setOf<String>()) }
    var laterIds by remember { mutableStateOf(setOf<String>()) }

    val activeEvents = unassignedEvents.filterNot { it.id in completedIds }
    val recommendedCount = activeEvents.count { it.id !in laterIds && it.bestCandidate() != null }
    val manualCount = activeEvents.count { it.id !in laterIds && it.bestCandidate() == null }
    val laterCount = activeEvents.count { it.id in laterIds }
    val effectiveFilter = when {
        filter == MatchQueueFilter.RECOMMENDED && recommendedCount == 0 && manualCount > 0 ->
            MatchQueueFilter.MANUAL
        filter == MatchQueueFilter.MANUAL && manualCount == 0 && recommendedCount > 0 ->
            MatchQueueFilter.RECOMMENDED
        else -> filter
    }
    val visibleEvents = activeEvents.filter { event ->
        when (effectiveFilter) {
            MatchQueueFilter.RECOMMENDED -> event.id !in laterIds && event.bestCandidate() != null
            MatchQueueFilter.MANUAL -> event.id !in laterIds && event.bestCandidate() == null
            MatchQueueFilter.LATER -> event.id in laterIds
        }
    }

    when {
        loading -> {
            BecalmSheetSkeleton(modifier = modifier)
        }

        activeEvents.isEmpty() -> {
            EmptyState(
                title = stringResource(R.string.persons_unassigned_empty_title),
                message = stringResource(R.string.persons_unassigned_empty_message),
                icon = Icons.AutoMirrored.Filled.List,
                modifier = modifier,
            )
        }

        else -> {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = modifier.fillMaxSize(),
            ) {
                item(key = "match-review-header") {
                    MatchReviewHeader(
                        remainingCount = activeEvents.size,
                        recommendedCount = recommendedCount,
                        manualCount = manualCount,
                        laterCount = laterCount,
                        filter = effectiveFilter,
                        onFilterChange = { filter = it },
                    )
                }
                if (visibleEvents.isEmpty()) {
                    item(key = "match-review-empty-filter") {
                        MatchReviewEmptyFilter(filter = effectiveFilter)
                    }
                }
                items(items = visibleEvents, key = { it.id }) { event ->
                    PersonMatchReviewCard(
                        event = event,
                        matchChoices = matchChoices,
                        onConfirm = { anchor, nickname ->
                            onManualMatch(event, anchor, nickname)
                            completedIds = completedIds + event.id
                            laterIds = laterIds - event.id
                        },
                        onLater = {
                            laterIds = laterIds + event.id
                            if (filter != MatchQueueFilter.LATER) {
                                filter = MatchQueueFilter.RECOMMENDED
                            }
                        },
                        onSelf = {
                            onSelfMatch(event)
                            completedIds = completedIds + event.id
                            laterIds = laterIds - event.id
                        },
                        onNotSelf = {
                            onNotSelfMatch(event)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchReviewHeader(
    remainingCount: Int,
    recommendedCount: Int,
    manualCount: Int,
    laterCount: Int,
    filter: MatchQueueFilter,
    onFilterChange: (MatchQueueFilter) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.person_match_review_heading),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.person_match_review_summary, remainingCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            MatchFilterChip(
                selected = filter == MatchQueueFilter.RECOMMENDED,
                label = stringResource(R.string.person_match_filter_recommended, recommendedCount),
                onClick = { onFilterChange(MatchQueueFilter.RECOMMENDED) },
            )
            MatchFilterChip(
                selected = filter == MatchQueueFilter.MANUAL,
                label = stringResource(R.string.person_match_filter_manual, manualCount),
                onClick = { onFilterChange(MatchQueueFilter.MANUAL) },
            )
            MatchFilterChip(
                selected = filter == MatchQueueFilter.LATER,
                label = stringResource(R.string.person_match_filter_later, laterCount),
                onClick = { onFilterChange(MatchQueueFilter.LATER) },
            )
        }
    }
}

@Composable
private fun MatchFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
            )
        },
    )
}

@Composable
private fun MatchReviewEmptyFilter(filter: MatchQueueFilter) {
    val message = when (filter) {
        MatchQueueFilter.RECOMMENDED -> R.string.person_match_empty_recommended
        MatchQueueFilter.MANUAL -> R.string.person_match_empty_manual
        MatchQueueFilter.LATER -> R.string.person_match_empty_later
    }
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    )
}

@Composable
private fun PersonMatchReviewCard(
    event: UnassignedEventSummary,
    matchChoices: List<PersonMatchChoiceRow>,
    onConfirm: (String, String) -> Unit,
    onLater: () -> Unit,
    onSelf: () -> Unit,
    onNotSelf: () -> Unit,
) {
    val candidate = event.bestCandidate()
    var manualOpen by remember(event.id) { mutableStateOf(candidate == null) }
    var personAnchor by remember(event.id) { mutableStateOf(candidate?.anchor.orEmpty()) }
    var selectedNickname by remember(event.id) { mutableStateOf(candidate?.displayName.orEmpty()) }
    var nickname by remember(event.id) { mutableStateOf("") }
    val isSelfSuggestion = candidate?.isSelfSuggestion == true || candidate?.role == "suggested"

    EvidenceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            EventSourceBadge(sourceType = event.sourceType)
            Spacer(modifier = Modifier.weight(1f))
            IngestionTimestamp(timestamp = event.timestamp)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = event.title ?: stringResource(R.string.persons_unidentified),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!event.snippet.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = event.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (candidate != null && !manualOpen) {
            CandidateRecommendation(
                candidate = candidate,
                onSelect = {
                    personAnchor = candidate.anchor
                    selectedNickname = candidate.displayName
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (isSelfSuggestion) {
                BecalmButton(
                    text = stringResource(R.string.person_match_not_self_action),
                    onClick = {
                        personAnchor = ""
                        selectedNickname = ""
                        onNotSelf()
                        manualOpen = true
                    },
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unassigned-match-not-self-${event.id}"),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            BecalmButton(
                text = stringResource(R.string.person_match_self_action),
                onClick = onSelf,
                variant = BecalmButtonVariant.Secondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("unassigned-match-self-${event.id}"),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = onLater) {
                    Text(text = stringResource(R.string.person_match_later_action))
                }
                if (!isSelfSuggestion) {
                    OutlinedButton(
                        onClick = {
                            personAnchor = ""
                            selectedNickname = ""
                            manualOpen = true
                        },
                        modifier = Modifier.testTag("unassigned-match-other-${event.id}"),
                    ) {
                        Text(text = stringResource(R.string.person_match_other_person_action))
                    }
                    BecalmButton(
                        text = stringResource(R.string.person_match_confirm_action),
                        modifier = Modifier.testTag("unassigned-match-confirm-${event.id}"),
                        onClick = {
                            onConfirm(
                                candidate.anchor,
                                selectedNickname.ifBlank { candidate.displayName },
                            )
                        },
                        variant = BecalmButtonVariant.Primary,
                    )
                }
            }
        } else {
            ManualMatchPanel(
                eventId = event.id,
                personAnchor = personAnchor,
                nickname = nickname,
                eventCandidates = event.candidates,
                matchChoices = matchChoices,
                onPersonAnchorChange = { personAnchor = it },
                onNicknameChange = { nickname = it },
                onLater = onLater,
                onSelf = onSelf,
                onConfirm = {
                    onConfirm(
                        personAnchor,
                        nickname.ifBlank { personAnchor },
                    )
                },
            )
        }
    }
}

@Composable
private fun CandidateRecommendation(
    candidate: PersonMatchCandidateSummary,
    onSelect: () -> Unit,
) {
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
    ) {
        Text(
            text = stringResource(R.string.person_match_recommendation_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        TextButton(
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.person_match_candidate_with_confidence,
                        candidate.displayName,
                        (candidate.confidence * 100).toInt().coerceIn(0, 100),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!candidate.detail.isNullOrBlank()) {
                    Text(
                        text = candidate.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidate.reasons.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.person_match_reasons, candidate.reasons.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!candidate.evidence.isNullOrBlank()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.person_match_evidence, candidate.evidence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualMatchPanel(
    eventId: String,
    personAnchor: String,
    nickname: String,
    eventCandidates: List<PersonMatchCandidateSummary>,
    matchChoices: List<PersonMatchChoiceRow>,
    onPersonAnchorChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onLater: () -> Unit,
    onSelf: () -> Unit,
    onConfirm: () -> Unit,
) {
    val normalizedQuery = personAnchor.trim()
    val candidateChoices = eventCandidates
        .filterNot { it.isSelfSuggestion }
        .map { candidate ->
            PersonMatchChoiceRow(
                anchor = candidate.anchor,
                displayName = candidate.displayName,
                detail = candidate.detail ?: candidate.evidence,
                hasInteractions = true,
                kind = PersonMatchChoiceKind.CANDIDATE,
            )
        }
    val visibleChoices = (candidateChoices + matchChoices)
        .distinctBy(PersonMatchChoiceRow::anchor)
        .filter { choice ->
            normalizedQuery.isBlank() ||
                choice.displayName.contains(normalizedQuery, ignoreCase = true) ||
                choice.anchor.contains(normalizedQuery, ignoreCase = true) ||
                choice.detail?.contains(normalizedQuery, ignoreCase = true) == true
        }
        .take(MAX_MANUAL_MATCH_CHOICES)

    Text(
        text = stringResource(R.string.person_match_manual_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))
    BecalmTextField(
        value = personAnchor,
        onValueChange = onPersonAnchorChange,
        placeholder = stringResource(R.string.persons_manual_match_search_hint),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unassigned-match-anchor-$eventId"),
    )
    if (visibleChoices.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.person_match_existing_people_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            visibleChoices.forEach { choice ->
                val selected = choice.anchor == personAnchor
                ContactRow(
                    headline = choice.displayName,
                    metadata = choice.detail ?: choice.anchor
                        .takeUnless { it == choice.displayName }
                        ?.takeIf(::isDisplayableManualMatchAnchor),
                    attentionLabel = when {
                        selected -> stringResource(R.string.person_match_selected_label)
                        choice.kind == PersonMatchChoiceKind.CANDIDATE ->
                            stringResource(R.string.person_match_candidate_label)
                        choice.hasInteractions || choice.kind == PersonMatchChoiceKind.EXISTING_PERSON ->
                            stringResource(R.string.person_match_existing_person_label)
                        else -> stringResource(R.string.person_match_contact_label)
                    },
                    onClick = {
                        onPersonAnchorChange(choice.anchor)
                        onNicknameChange(choice.displayName)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unassigned-match-choice-$eventId-${choice.anchor}"),
                ) {
                    MatchChoiceAvatar(seed = choice.displayName)
                }
            }
        }
    } else if (normalizedQuery.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.person_match_no_existing_people),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    BecalmTextField(
        value = nickname,
        onValueChange = onNicknameChange,
        placeholder = stringResource(R.string.persons_manual_match_nickname_hint),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unassigned-match-nickname-$eventId"),
    )
    Spacer(modifier = Modifier.height(8.dp))
    BecalmButton(
        text = stringResource(R.string.person_match_self_action),
        onClick = onSelf,
        variant = BecalmButtonVariant.Secondary,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unassigned-match-self-$eventId"),
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = onLater) {
            Text(text = stringResource(R.string.person_match_later_action))
        }
        OutlinedButton(
            enabled = personAnchor.isNotBlank(),
            onClick = onConfirm,
        ) {
            Text(text = stringResource(R.string.persons_manual_add_person_action))
        }
        BecalmButton(
            text = stringResource(R.string.persons_manual_match_action),
            enabled = personAnchor.isNotBlank(),
            onClick = onConfirm,
            variant = BecalmButtonVariant.Primary,
        )
    }
}

@Composable
private fun MatchChoiceAvatar(seed: String) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private enum class MatchQueueFilter {
    RECOMMENDED,
    MANUAL,
    LATER,
}

private const val MAX_MANUAL_MATCH_CHOICES = 24

private fun isDisplayableManualMatchAnchor(anchor: String): Boolean =
    anchor.contains("@") || anchor.startsWith("+")

private fun UnassignedEventSummary.bestCandidate(): PersonMatchCandidateSummary? =
    candidates.firstOrNull()
        ?: suggestedLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                PersonMatchCandidateSummary(
                    anchor = it,
                    displayName = it,
                    detail = null,
                    role = "suggested",
                    evidence = null,
                    confidence = 0.72,
                )
            }

@PreviewLightDark
@Composable
private fun PreviewUnassignedEventsEmpty() {
    BecalmTheme {
        BecalmScaffold(
            title = "Unassigned Events",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
        ) { padding ->
            EmptyState(
                title = "No unassigned events",
                message = "All events have been matched to a person.",
                icon = Icons.AutoMirrored.Filled.List,
                modifier = Modifier.padding(padding),
            )
        }
    }
}
