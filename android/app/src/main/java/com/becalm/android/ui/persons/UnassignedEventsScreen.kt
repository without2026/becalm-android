package com.becalm.android.ui.persons

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
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
import com.becalm.android.domain.person.PersonIdentityResolver
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
import com.becalm.android.ui.components.sourcePresentationFor
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
            savingMatchEventIds = state.savingMatchEventIds,
            resolvedMatchEventIds = state.resolvedMatchEventIds,
            notSelfMatchEventIds = state.notSelfMatchEventIds,
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
    savingMatchEventIds: Set<String> = emptySet(),
    resolvedMatchEventIds: Set<String> = emptySet(),
    notSelfMatchEventIds: Set<String> = emptySet(),
) {
    var filter by remember { mutableStateOf(MatchQueueFilter.RECOMMENDED) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var laterIds by remember { mutableStateOf(setOf<String>()) }
    var selectedMatchEventIds by remember { mutableStateOf(setOf<String>()) }

    val activeEvents = unassignedEvents.filterNot { it.id in resolvedMatchEventIds }
    val sourceCounts = activeEvents
        .groupingBy { it.sourceType }
        .eachCount()
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key to it.value }
    val effectiveSourceFilter = sourceFilter.takeIf { selected ->
        sourceCounts.any { (sourceType, _) -> sourceType == selected }
    }
    val sourceScopedEvents = activeEvents.filter { event ->
        effectiveSourceFilter == null || event.sourceType == effectiveSourceFilter
    }
    val recommendedCount = sourceScopedEvents.count { it.id !in laterIds && it.bestCandidate() != null }
    val manualCount = sourceScopedEvents.count { it.id !in laterIds && it.bestCandidate() == null }
    val laterCount = sourceScopedEvents.count { it.id in laterIds }
    val effectiveFilter = when {
        filter == MatchQueueFilter.RECOMMENDED && recommendedCount == 0 && manualCount > 0 ->
            MatchQueueFilter.MANUAL
        filter == MatchQueueFilter.MANUAL && manualCount == 0 && recommendedCount > 0 ->
            MatchQueueFilter.RECOMMENDED
        else -> filter
    }
    val visibleEvents = sourceScopedEvents.filter { event ->
        when (effectiveFilter) {
            MatchQueueFilter.RECOMMENDED -> event.id !in laterIds && event.bestCandidate() != null
            MatchQueueFilter.MANUAL -> event.id !in laterIds && event.bestCandidate() == null
            MatchQueueFilter.LATER -> event.id in laterIds
        }
    }
    val bulkConfirmableIds = sourceScopedEvents
        .filter { it.id !in laterIds && it.bulkConfirmPayload() != null }
        .mapTo(mutableSetOf()) { it.id }
    val visibleConfirmableIds = visibleEvents
        .filter { it.id in bulkConfirmableIds }
        .mapTo(mutableSetOf()) { it.id }
    val selectedVisibleIds = selectedMatchEventIds.intersect(visibleConfirmableIds)
    val confirmableClusterCount = sourceScopedEvents
        .filter { it.id in bulkConfirmableIds }
        .map(UnassignedEventSummary::matchClusterKey)
        .distinct()
        .size
    val visibleClusterSizes = visibleEvents
        .groupingBy(UnassignedEventSummary::matchClusterKey)
        .eachCount()

    LaunchedEffect(activeEvents.size, laterIds, resolvedMatchEventIds, savingMatchEventIds, effectiveSourceFilter) {
        val validIds = activeEvents.mapTo(mutableSetOf()) { it.id }
        selectedMatchEventIds = selectedMatchEventIds.intersect(validIds)
    }

    fun confirmSelectedMatches() {
        val selectedIds = selectedMatchEventIds.intersect(visibleConfirmableIds)
        visibleEvents
            .filter { it.id in selectedIds }
            .forEach { event ->
                event.bulkConfirmPayload()?.let { payload ->
                    onManualMatch(event, payload.anchor, payload.displayName)
                }
            }
        laterIds = laterIds - selectedIds
        selectedMatchEventIds = selectedMatchEventIds - selectedIds
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
            Box(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = if (selectedVisibleIds.isNotEmpty()) 108.dp else 16.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "match-review-header") {
                        MatchReviewHeader(
                            remainingCount = activeEvents.size,
                            recommendedCount = recommendedCount,
                            manualCount = manualCount,
                            laterCount = laterCount,
                            confirmableCount = bulkConfirmableIds.size,
                            confirmableClusterCount = confirmableClusterCount,
                            filter = effectiveFilter,
                            sourceCounts = sourceCounts,
                            selectedSource = effectiveSourceFilter,
                            onFilterChange = { filter = it },
                            onSourceChange = { sourceFilter = it },
                        )
                    }
                    if (effectiveFilter == MatchQueueFilter.RECOMMENDED && visibleConfirmableIds.isNotEmpty()) {
                        item(key = "match-review-bulk") {
                            MatchBulkActionPanel(
                                confirmableCount = visibleConfirmableIds.size,
                                selectedCount = selectedVisibleIds.size,
                                onSelectAll = {
                                    selectedMatchEventIds = selectedMatchEventIds + visibleConfirmableIds
                                },
                                onClear = {
                                    selectedMatchEventIds = selectedMatchEventIds - visibleConfirmableIds
                                },
                                onConfirmSelected = ::confirmSelectedMatches,
                            )
                        }
                    }
                    if (visibleEvents.isEmpty()) {
                        item(key = "match-review-empty-filter") {
                            MatchReviewEmptyFilter(filter = effectiveFilter)
                        }
                    }
                    items(items = visibleEvents, key = { it.id }) { event ->
                        PersonMatchReviewCard(
                            event = event,
                            relatedEventCount = visibleClusterSizes[event.matchClusterKey()] ?: 1,
                            matchChoices = matchChoices,
                            saving = event.id in savingMatchEventIds,
                            notSelfRejected = event.id in notSelfMatchEventIds,
                            selectionEnabled = event.id in visibleConfirmableIds,
                            selected = event.id in selectedMatchEventIds,
                            onSelectedChange = { selected ->
                                selectedMatchEventIds = if (selected) {
                                    selectedMatchEventIds + event.id
                                } else {
                                    selectedMatchEventIds - event.id
                                }
                            },
                            onConfirm = { anchor, nickname ->
                                onManualMatch(event, anchor, nickname)
                                laterIds = laterIds - event.id
                                selectedMatchEventIds = selectedMatchEventIds - event.id
                            },
                            onLater = {
                                laterIds = laterIds + event.id
                                selectedMatchEventIds = selectedMatchEventIds - event.id
                                if (filter != MatchQueueFilter.LATER) {
                                    filter = MatchQueueFilter.RECOMMENDED
                                }
                            },
                            onSelf = {
                                onSelfMatch(event)
                                laterIds = laterIds - event.id
                                selectedMatchEventIds = selectedMatchEventIds - event.id
                            },
                            onNotSelf = {
                                onNotSelfMatch(event)
                            },
                        )
                    }
                }
                if (selectedVisibleIds.isNotEmpty()) {
                    MatchBulkBottomBar(
                        selectedCount = selectedVisibleIds.size,
                        onClear = {
                            selectedMatchEventIds = selectedMatchEventIds - visibleConfirmableIds
                        },
                        onConfirmSelected = ::confirmSelectedMatches,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 16.dp),
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
    confirmableCount: Int,
    confirmableClusterCount: Int,
    filter: MatchQueueFilter,
    sourceCounts: List<Pair<String, Int>>,
    selectedSource: String?,
    onFilterChange: (MatchQueueFilter) -> Unit,
    onSourceChange: (String?) -> Unit,
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
        if (confirmableCount > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.person_match_review_batch_summary,
                    confirmableCount,
                    confirmableClusterCount,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
        if (sourceCounts.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                MatchFilterChip(
                    selected = selectedSource == null,
                    label = stringResource(R.string.person_match_source_all, remainingCount),
                    onClick = { onSourceChange(null) },
                )
                sourceCounts.forEach { (sourceType, count) ->
                    MatchFilterChip(
                        selected = selectedSource == sourceType,
                        label = "${stringResource(sourcePresentationFor(sourceType).labelRes)} $count",
                        onClick = { onSourceChange(sourceType) },
                    )
                }
            }
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
private fun MatchBulkActionPanel(
    confirmableCount: Int,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onConfirmSelected: () -> Unit,
) {
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        contentPadding = PaddingValues(14.dp),
    ) {
        Text(
            text = stringResource(R.string.person_match_bulk_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.person_match_bulk_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = if (selectedCount == confirmableCount) onClear else onSelectAll) {
                Text(
                    text = stringResource(
                        if (selectedCount == confirmableCount) {
                            R.string.person_match_bulk_clear
                        } else {
                            R.string.person_match_bulk_select_all
                        },
                    ),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            BecalmButton(
                text = stringResource(R.string.person_match_bulk_confirm, selectedCount),
                enabled = selectedCount > 0,
                onClick = onConfirmSelected,
                variant = BecalmButtonVariant.Primary,
            )
        }
    }
}

@Composable
private fun MatchBulkBottomBar(
    selectedCount: Int,
    onClear: () -> Unit,
    onConfirmSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    QuietPanel(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.person_match_batch_selected, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.person_match_bulk_clear))
            }
            BecalmButton(
                text = stringResource(R.string.person_match_bulk_confirm, selectedCount),
                onClick = onConfirmSelected,
                variant = BecalmButtonVariant.Primary,
            )
        }
    }
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
    relatedEventCount: Int,
    matchChoices: List<PersonMatchChoiceRow>,
    saving: Boolean,
    notSelfRejected: Boolean,
    selectionEnabled: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
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
    val candidateDisplayName = safeManualMatchDisplayName(
        selectedNickname.ifBlank { candidate?.displayName.orEmpty() },
    )

    LaunchedEffect(event.id, notSelfRejected) {
        if (notSelfRejected) {
            personAnchor = ""
            selectedNickname = ""
            manualOpen = true
        }
    }

    EvidenceCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (selectionEnabled) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                    enabled = !saving,
                    modifier = Modifier.testTag("unassigned-match-select-${event.id}"),
                )
            }
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
        if (event.isSpeakerReviewCandidate) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.person_match_meeting_speaker_candidate_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (relatedEventCount > 1) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.person_match_cluster_hint, relatedEventCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (candidate != null && !manualOpen) {
            CandidateRecommendation(
                candidate = candidate,
                isSelfSuggestion = isSelfSuggestion,
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
                        onNotSelf()
                    },
                    enabled = !saving,
                    loading = saving,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unassigned-match-not-self-${event.id}"),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (isSelfSuggestion) {
                BecalmButton(
                    text = stringResource(R.string.person_match_self_action),
                    onClick = onSelf,
                    enabled = !saving,
                    loading = saving,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unassigned-match-self-${event.id}"),
                )
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                BecalmButton(
                    text = stringResource(R.string.person_match_confirm_action),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unassigned-match-confirm-${event.id}"),
                    enabled = !saving && candidateDisplayName != null,
                    loading = saving,
                    onClick = {
                        onConfirm(
                            candidate.anchor,
                            candidateDisplayName.orEmpty(),
                        )
                    },
                    variant = BecalmButtonVariant.Primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = onLater, enabled = !saving) {
                    Text(text = stringResource(R.string.person_match_later_action))
                }
                if (!isSelfSuggestion) {
                    OutlinedButton(
                        enabled = !saving,
                        onClick = {
                            personAnchor = ""
                            selectedNickname = ""
                            manualOpen = true
                        },
                        modifier = Modifier.testTag("unassigned-match-other-${event.id}"),
                    ) {
                        Text(text = stringResource(R.string.person_match_other_person_action))
                    }
                }
            }
        } else {
            ManualMatchPanel(
                eventId = event.id,
                personAnchor = personAnchor,
                nickname = nickname,
                eventCandidates = event.candidates,
                matchChoices = matchChoices,
                selfRejected = notSelfRejected,
                onPersonAnchorChange = { personAnchor = it },
                onNicknameChange = { nickname = it },
                onLater = onLater,
                onSelf = onSelf,
                saving = saving,
                onConfirm = { anchor, displayName ->
                    onConfirm(
                        anchor,
                        displayName,
                    )
                },
            )
        }
    }
}

@Composable
private fun CandidateRecommendation(
    candidate: PersonMatchCandidateSummary,
    isSelfSuggestion: Boolean,
    onSelect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f), MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onSelect)
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(
                if (isSelfSuggestion) {
                    R.string.person_match_self_recommendation_label
                } else {
                    R.string.person_match_recommendation_label
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            MatchChoiceAvatar(seed = candidate.displayName)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.displayName,
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

private data class BulkConfirmPayload(
    val anchor: String,
    val displayName: String,
)

@Composable
private fun ManualMatchPanel(
    eventId: String,
    personAnchor: String,
    nickname: String,
    eventCandidates: List<PersonMatchCandidateSummary>,
    matchChoices: List<PersonMatchChoiceRow>,
    selfRejected: Boolean,
    onPersonAnchorChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
    onLater: () -> Unit,
    onSelf: () -> Unit,
    saving: Boolean,
    onConfirm: (String, String) -> Unit,
) {
    val normalizedQuery = personAnchor.trim()
    val candidateChoices = eventCandidates
        .filterNot { it.isSelfSuggestion }
        .map { candidate ->
            PersonMatchChoiceRow(
                anchor = candidate.anchor,
                displayName = candidate.displayName,
                detail = candidate.detail ?: candidate.evidence?.let(::sanitizeChoiceDetail),
                hasInteractions = true,
                kind = PersonMatchChoiceKind.CANDIDATE,
            )
        }
    val allChoices = (candidateChoices + matchChoices)
        .distinctBy(PersonMatchChoiceRow::anchor)
    val visibleChoices = allChoices
        .filter { choice ->
            normalizedQuery.isBlank() ||
                choice.displayName.contains(normalizedQuery, ignoreCase = true) ||
                choice.anchor.contains(normalizedQuery, ignoreCase = true) ||
                choice.detail?.contains(normalizedQuery, ignoreCase = true) == true
        }
    val choiceSections = listOf(
        ManualMatchChoiceSection(
            title = stringResource(R.string.person_match_candidate_section_label),
            choices = visibleChoices
                .filter { it.kind == PersonMatchChoiceKind.CANDIDATE }
                .take(MAX_MANUAL_MATCH_CHOICES_PER_SECTION),
        ),
        ManualMatchChoiceSection(
            title = stringResource(R.string.person_match_contacts_section_label),
            choices = visibleChoices
                .filter { it.kind == PersonMatchChoiceKind.CONTACT }
                .sortedBy { it.displayName }
                .take(MAX_MANUAL_MATCH_CHOICES_PER_SECTION),
        ),
        ManualMatchChoiceSection(
            title = stringResource(R.string.person_match_existing_people_section_label),
            choices = visibleChoices
                .filter { it.kind == PersonMatchChoiceKind.EXISTING_PERSON }
                .sortedWith(compareByDescending<PersonMatchChoiceRow> { it.hasInteractions }.thenBy { it.displayName })
                .take(MAX_MANUAL_MATCH_CHOICES_PER_SECTION),
        ),
    )
    val selectedKnownChoice = allChoices.any { it.anchor == personAnchor }
    val newPersonDisplayName = safeManualMatchDisplayName(nickname)
        ?: safeManualMatchDisplayName(personAnchor)
    val confirmAnchor = if (selectedKnownChoice) {
        personAnchor
    } else {
        normalizedQuery.ifBlank { newPersonDisplayName.orEmpty() }
    }
    val confirmDisplayName = if (selectedKnownChoice) {
        safeManualMatchDisplayName(nickname).orEmpty()
    } else {
        newPersonDisplayName.orEmpty()
    }
    val canConfirm = !saving &&
        confirmAnchor.isNotBlank() &&
        (selectedKnownChoice || newPersonDisplayName != null)

    Text(
        text = stringResource(R.string.person_match_manual_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (selfRejected) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.person_match_not_self_followup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("unassigned-match-not-self-followup-$eventId"),
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    BecalmTextField(
        value = personAnchor,
        onValueChange = onPersonAnchorChange,
        placeholder = stringResource(R.string.persons_manual_match_search_hint),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unassigned-match-anchor-$eventId"),
    )
    if (choiceSections.any { it.choices.isNotEmpty() }) {
        Spacer(modifier = Modifier.height(10.dp))
        choiceSections.forEach { section ->
            if (section.choices.isNotEmpty()) {
                ManualMatchChoiceSectionContent(
                    title = section.title,
                    choices = section.choices,
                    eventId = eventId,
                    selectedAnchor = personAnchor,
                    onPersonAnchorChange = onPersonAnchorChange,
                    onNicknameChange = onNicknameChange,
                )
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
    if (personAnchor.isNotBlank() && !selectedKnownChoice && newPersonDisplayName == null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.persons_manual_add_person_name_required),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
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
    if (!selfRejected) {
        BecalmButton(
            text = stringResource(R.string.person_match_self_action),
            onClick = onSelf,
            enabled = !saving,
            loading = saving,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("unassigned-match-self-$eventId"),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextButton(onClick = onLater, enabled = !saving) {
            Text(text = stringResource(R.string.person_match_later_action))
        }
        BecalmButton(
            text = stringResource(
                if (selectedKnownChoice) {
                    R.string.persons_manual_match_action
                } else {
                    R.string.persons_manual_add_person_action
                },
            ),
            enabled = canConfirm,
            loading = saving,
            onClick = { onConfirm(confirmAnchor, confirmDisplayName) },
            variant = BecalmButtonVariant.Primary,
        )
    }
}

private data class ManualMatchChoiceSection(
    val title: String,
    val choices: List<PersonMatchChoiceRow>,
)

@Composable
private fun ManualMatchChoiceSectionContent(
    title: String,
    choices: List<PersonMatchChoiceRow>,
    eventId: String,
    selectedAnchor: String,
    onPersonAnchorChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 2.dp),
    )
    Spacer(modifier = Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { choice ->
            val selected = choice.anchor == selectedAnchor
            ContactRow(
                headline = choice.displayName,
                metadata = choice.detail
                    ?.takeIf { isDisplayableManualMatchMetadata(it) },
                attentionLabel = when {
                    selected -> stringResource(R.string.person_match_selected_label)
                    choice.kind == PersonMatchChoiceKind.CANDIDATE ->
                        stringResource(R.string.person_match_candidate_label)
                    choice.kind == PersonMatchChoiceKind.CONTACT ->
                        stringResource(R.string.person_match_contact_label)
                    else -> stringResource(R.string.person_match_existing_person_label)
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
    Spacer(modifier = Modifier.height(10.dp))
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

private const val MAX_MANUAL_MATCH_CHOICES_PER_SECTION = 12

private fun isDisplayableManualMatchMetadata(value: String): Boolean {
    val trimmed = value.trim().takeIf { it.isNotEmpty() } ?: return false
    if (trimmed.contains("@")) return false
    if (PersonIdentityResolver.normalizePhoneAnchor(trimmed) != null) return false
    return true
}

private fun sanitizeChoiceDetail(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.contains("@")) return null
    return trimmed
}

private fun UnassignedEventSummary.bestCandidate(): PersonMatchCandidateSummary? =
    candidates.firstOrNull()?.safeDisplayNameCopy()
        ?: suggestedLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                val safeDisplayName = safeManualMatchDisplayName(it) ?: UNKNOWN_PERSON_DISPLAY_NAME
                PersonMatchCandidateSummary(
                    anchor = it,
                    displayName = safeDisplayName,
                    detail = null,
                    role = "suggested",
                    evidence = null,
                    confidence = 0.72,
                )
            }

private fun PersonMatchCandidateSummary.safeDisplayNameCopy(): PersonMatchCandidateSummary =
    copy(displayName = safeManualMatchDisplayName(displayName) ?: UNKNOWN_PERSON_DISPLAY_NAME)

private fun UnassignedEventSummary.bulkConfirmPayload(): BulkConfirmPayload? {
    val candidate = bestCandidate() ?: return null
    if (candidate.isSelfSuggestion) return null
    val displayName = safeManualMatchDisplayName(candidate.displayName) ?: return null
    return BulkConfirmPayload(anchor = candidate.anchor, displayName = displayName)
}

private fun UnassignedEventSummary.matchClusterKey(): String =
    bestCandidate()
        ?.anchor
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?: suggestedLabel?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: "$sourceType:$sourceRef"

private fun safeManualMatchDisplayName(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value == UNKNOWN_PERSON_DISPLAY_NAME) return null
    if (PersonIdentityResolver.normalizeEmailAnchor(value) != null) return null
    if (PersonIdentityResolver.normalizePhoneAnchor(value) != null) return null
    if (PersonIdentityResolver.isSpeakerLabelValue(value)) return null
    if (PERSON_ID_LIKE_REGEX.matches(value)) return null
    return value
}

private const val UNKNOWN_PERSON_DISPLAY_NAME = "아직 이름을 모르는 연락처"
private val PERSON_ID_LIKE_REGEX = Regex(
    """^(?:person[-_:])?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$|^(?:person[-_:])?[0-9a-f]{32}$""",
    RegexOption.IGNORE_CASE,
)

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
