package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Person detail screen — renders a [PersonHeader] plus a source-filtered unified
 * timeline built from commitments, raw events, and meetings.
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    HandleSnackbarMessage(state.error, snackbarHostState, viewModel::onErrorDismissed)

    val onEventTap: (String) -> Unit = { eventId ->
        navController.navigate(
            BecalmRoute.RawEventDetail(personId = personId, eventId = eventId).path,
        )
    }

    PersonDetailScreenContent(
        state = state,
        title = state.displayName ?: personId.take(16),
        snackbarHostState = snackbarHostState,
        onBack = navController::popBackStack,
        onEventTap = onEventTap,
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
        val hasAnyInteractions = state.sourceEventCards.isNotEmpty() || state.timeline.isNotEmpty()
        when {
            state.loading -> {
                BecalmSheetSkeleton(modifier = Modifier.padding(padding))
            }
            state.error != null && !hasAnyInteractions -> {
                ErrorState(
                    title = stringResource(R.string.error_generic_title),
                    message = state.error,
                    modifier = Modifier.padding(padding),
                )
            }
            !hasAnyInteractions -> PersonDetailEmpty(state = state, padding = padding)
            else -> PersonDetailList(
                state = state,
                padding = padding,
                onEventTap = onEventTap,
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
) {
    var selectedFilter by rememberSaveable { mutableStateOf(PersonTimelineFilter.ALL) }
    val sourceCards = remember(selectedFilter, state.sourceEventCards) {
        state.sourceEventCards.filter(selectedFilter::matches)
    }
    val filteredRows = remember(selectedFilter, state.timeline) {
        state.timeline.filter(selectedFilter::matches)
    }
    val timelineHeader = stringResource(
        R.string.person_detail_timeline_section_fmt,
        sourceCards.takeIf { it.isNotEmpty() }?.size ?: filteredRows.size,
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
        } else if (filteredRows.isEmpty()) {
            item(key = "timeline-empty") {
                EmptyState(title = stringResource(R.string.person_detail_timeline_filter_empty))
            }
        } else {
            items(
                items = filteredRows,
                key = { row -> row.stableTimelineKey() },
            ) { row ->
                InteractionHistoryRow(
                    row = row,
                    onEventTap = onEventTap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ─── Timeline helpers ─────────────────────────────────────────────────────────

private enum class PersonTimelineFilter {
    ALL,
    EMAIL,
    CALL,
    MEETING,
    COMMITMENT,
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
        PersonTimelineFilter.COMMITMENT to stringResource(R.string.person_detail_filter_commitment),
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
                modifier = Modifier.testTag("person-detail-filter-${filter.name.lowercase()}"),
            )
        }
    }
}

private fun InteractionRow.stableTimelineKey(): String = when (this) {
    is InteractionRow.Event -> "event-$id"
    is InteractionRow.Commitment ->
        "commitment-${timestamp.toEpochMilliseconds()}-${title.hashCode()}-$itemType-$actionState-$direction"
    is InteractionRow.CalendarMeeting -> "meeting-${timestamp.toEpochMilliseconds()}-${title.hashCode()}"
}

private fun PersonTimelineFilter.matches(row: InteractionRow): Boolean = when (this) {
    PersonTimelineFilter.ALL -> true
    PersonTimelineFilter.EMAIL -> row is InteractionRow.Event && row.source.isEmailSource()
    PersonTimelineFilter.CALL -> row is InteractionRow.Event && row.source.isCallSource()
    PersonTimelineFilter.MEETING ->
        row is InteractionRow.CalendarMeeting ||
            (row is InteractionRow.Event && row.source.isCalendarSource())
    PersonTimelineFilter.COMMITMENT -> row is InteractionRow.Commitment
}

private fun PersonTimelineFilter.matches(card: SourceEventCardProjection): Boolean = when (this) {
    PersonTimelineFilter.ALL -> true
    PersonTimelineFilter.EMAIL -> card.sourceType.isEmailSource()
    PersonTimelineFilter.CALL -> card.sourceType.isCallSource()
    PersonTimelineFilter.MEETING -> card.sourceType.isCalendarSource()
    PersonTimelineFilter.COMMITMENT ->
        card.myActions.isNotEmpty() || card.theirActions.isNotEmpty() || card.schedules.isNotEmpty()
}

private fun String.isEmailSource(): Boolean =
    equals("gmail", ignoreCase = true) ||
        equals("outlook_mail", ignoreCase = true) ||
        equals("naver_imap", ignoreCase = true) ||
        equals("daum_imap", ignoreCase = true) ||
        contains("mail", ignoreCase = true) ||
        contains("imap", ignoreCase = true)

private fun String.isCallSource(): Boolean =
    equals("voice", ignoreCase = true) ||
        equals("call_recording", ignoreCase = true) ||
        contains("call", ignoreCase = true)

private fun String.isCalendarSource(): Boolean =
    equals("google_calendar", ignoreCase = true) ||
        equals("outlook_calendar", ignoreCase = true) ||
        contains("calendar", ignoreCase = true)

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
                        contentDescription = "Back",
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
                        InteractionRow.Commitment(
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            title = "Send contract draft",
                            direction = "give",
                            actionState = "pending",
                        ),
                        InteractionRow.CalendarMeeting(
                            timestamp = kotlinx.datetime.Clock.System.now(),
                            title = "Q2 Planning Meeting",
                        ),
                    ),
                ) { row ->
                    InteractionHistoryRow(
                        row = row,
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
