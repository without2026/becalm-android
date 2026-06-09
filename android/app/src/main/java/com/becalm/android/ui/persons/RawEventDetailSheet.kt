package com.becalm.android.ui.persons

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentDisplayPolicy
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonSize
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.ContactRow
import com.becalm.android.ui.components.EMAIL_SOURCE_TYPES
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.EventTitleText
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.IngestionTimestamp
import com.becalm.android.ui.components.isTakeDirection
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Raw event detail screen — extended fields loaded from Room for a single ingestion event.
 *
 * Branches by `source_type` in [RawEventDetailUiState.sourceType]:
 * - **Email sources** ([EMAIL_SOURCE_TYPES]) → [EmailEventDetailSection] which renders
 *   the six SRC-004 / EMAIL-003 / EMAIL-004 components (source badge, title, snippet,
 *   body, attachments pill, commitments-extracted badge, KST timestamp).
 * - **Non-email sources** (voice / meeting / call_recording / calendar) → a common
 *   summary layout. Audio sources also render their archived transcript through the
 *   same expandable source-body path as email originals.
 *
 * Named "Sheet" in the spec but implemented as a full screen for navigation consistency.
 *
 * Spec: SRC-008, `.spec/contracts/ui-map.yml:113-118`.
 *
 * Primary VM: [RawEventDetailViewModel]
 * Navigation entry: [BecalmRoute.RawEventDetail]
 * Navigation exit: back to [BecalmRoute.PersonDetail]
 */
@Composable
public fun RawEventDetailSheet(
    navController: NavHostController,
    personId: String,
    eventId: String,
    viewModel: RawEventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val message = state.message?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(message, snackbarHostState, viewModel::onMessageShown)

    BecalmScaffold(
        title = stringResource(R.string.raw_event_detail_title),
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
        when {
            state.loading -> {
                BecalmSheetSkeleton(modifier = Modifier.padding(padding))
            }
            state.error != null -> {
                ErrorState(
                    title = stringResource(R.string.raw_event_detail_not_found),
                    message = uiMessageStringResource(requireNotNull(state.error)),
                    modifier = Modifier.padding(padding),
                )
            }
            state.sourceType != null -> RawEventDetailContent(
                state = state,
                onThreadMessageClick = { rawEventId ->
                    if (rawEventId != state.eventId) {
                        navController.navigate(BecalmRoute.RawEventDetail(personId = personId, eventId = rawEventId).path) {
                            launchSingleTop = true
                        }
                    }
                },
                onParticipantReassign = viewModel::onParticipantReassign,
                onParticipantIgnore = viewModel::onParticipantIgnore,
                modifier = Modifier.padding(padding),
            )
            else -> {
                EmptyState(
                    title = stringResource(R.string.raw_event_detail_not_found),
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
internal fun RawEventDetailContent(
    state: RawEventDetailUiState,
    modifier: Modifier = Modifier,
    onThreadMessageClick: (String) -> Unit = {},
    onParticipantReassign: (String, String) -> Unit = { _, _ -> },
    onParticipantIgnore: (String) -> Unit = {},
) {
    val visibleExtractedCommitments = visibleRawEventCommitments(state.extractedCommitments)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("raw-event-detail-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (rawEventSyncStatusCopy(state.syncStatus) != null) {
            item {
                RawEventSyncStatusBanner(syncStatus = state.syncStatus)
            }
        }

        if (state.sourceType in EMAIL_SOURCE_TYPES) {
            item {
                EvidenceCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    EmailEventSummarySection(state = state)
                }
            }
        } else {
            item {
                EvidenceCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    NonEmailEventDetailSection(state = state)
                }
            }
        }

        if (state.sourceType in EMAIL_SOURCE_TYPES && state.hasEmailBodyDetail()) {
            item {
                EvidenceCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    EmailEventBodySection(state = state)
                }
            }
        }

        if (state.hasAudioTranscriptDetail()) {
            item {
                EvidenceCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    RawEventTranscriptSection(state = state)
                }
            }
        }

        if (visibleExtractedCommitments.isNotEmpty()) {
            item {
                rawEventWhyText(visibleExtractedCommitments)?.let { whyText ->
                    RawEventWhySection(
                        whyText = whyText,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.sourceType in EMAIL_SOURCE_TYPES && state.threadMessages.size > 1) {
            item {
                RawEventThreadSection(
                    messages = state.threadMessages,
                    onMessageClick = onThreadMessageClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.participantCorrections.isNotEmpty()) {
            item {
                RawEventParticipantCorrectionSection(
                    participants = state.participantCorrections,
                    choices = state.participantChoices,
                    correctingParticipantIds = state.correctingParticipantIds,
                    onParticipantReassign = onParticipantReassign,
                    onParticipantIgnore = onParticipantIgnore,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (visibleExtractedCommitments.isNotEmpty()) {
            item {
                RawEventExtractionSection(
                    commitments = visibleExtractedCommitments,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RawEventThreadSection(
    messages: List<RawEventThreadMessageUi>,
    onMessageClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EvidenceCard(
        modifier = modifier.testTag("raw-event-thread-section"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.raw_event_thread_title_fmt, messages.size),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            messages.forEach { message ->
                RawEventThreadMessageRow(
                    message = message,
                    onClick = { onMessageClick(message.rawEventId) },
                )
            }
        }
    }
}

@Composable
private fun RawEventThreadMessageRow(
    message: RawEventThreadMessageUi,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("raw-event-thread-message-${message.rawEventId}")
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (message.isCurrent) {
                    stringResource(R.string.raw_event_thread_current)
                } else {
                    stringResource(R.string.raw_event_thread_message)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (message.isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            IngestionTimestamp(timestamp = message.timestamp)
        }
        Text(
            text = message.title ?: stringResource(R.string.raw_event_detail_no_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        message.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RawEventParticipantCorrectionSection(
    participants: List<RawEventParticipantCorrectionRow>,
    choices: List<RawEventParticipantChoiceRow>,
    correctingParticipantIds: Set<String>,
    onParticipantReassign: (String, String) -> Unit,
    onParticipantIgnore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    EvidenceCard(
        modifier = modifier.testTag("raw-event-person-corrections"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.raw_event_person_corrections_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            participants.forEach { participant ->
                RawEventParticipantCorrectionRowContent(
                    participant = participant,
                    choices = choices.filterNot { it.personId == participant.currentPersonId },
                    saving = participant.participantId in correctingParticipantIds,
                    onParticipantReassign = onParticipantReassign,
                    onParticipantIgnore = onParticipantIgnore,
                )
            }
        }
    }
}

@Composable
private fun RawEventParticipantCorrectionRowContent(
    participant: RawEventParticipantCorrectionRow,
    choices: List<RawEventParticipantChoiceRow>,
    saving: Boolean,
    onParticipantReassign: (String, String) -> Unit,
    onParticipantIgnore: (String) -> Unit,
) {
    var editing by remember(participant.participantId) { mutableStateOf(false) }
    var query by remember(participant.participantId) { mutableStateOf("") }
    var showIgnoreConfirm by remember(participant.participantId) { mutableStateOf(false) }
    val visibleChoices = choices
        .filter { choice ->
            query.isBlank() ||
                choice.displayName.contains(query, ignoreCase = true) ||
                choice.detail?.contains(query, ignoreCase = true) == true
        }
        .take(8)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RawEventChoiceAvatar(seed = participant.displayName)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = participant.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                participant.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            BecalmButton(
                text = stringResource(R.string.raw_event_person_change_action),
                enabled = !saving && choices.isNotEmpty(),
                onClick = { editing = !editing },
                variant = BecalmButtonVariant.Secondary,
                size = BecalmButtonSize.Compact,
                modifier = Modifier.weight(1f),
            )
            BecalmButton(
                text = stringResource(R.string.raw_event_person_ignore_action),
                enabled = !saving,
                loading = saving,
                onClick = { showIgnoreConfirm = true },
                variant = BecalmButtonVariant.DestructiveTertiary,
                size = BecalmButtonSize.Compact,
                leadingIcon = Icons.Outlined.Close,
                modifier = Modifier.weight(1f),
            )
        }
        if (editing) {
            BecalmTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.raw_event_person_choice_label),
                modifier = Modifier.fillMaxWidth(),
            )
            if (visibleChoices.isEmpty()) {
                Text(
                    text = stringResource(R.string.raw_event_person_no_choices),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleChoices.forEach { choice ->
                        ContactRow(
                            headline = choice.displayName,
                            metadata = choice.detail,
                            onClick = {
                                editing = false
                                query = ""
                                onParticipantReassign(participant.participantId, choice.personId)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("raw-event-person-choice-${participant.participantId}-${choice.personId}"),
                        ) {
                            RawEventChoiceAvatar(seed = choice.displayName)
                        }
                    }
                }
            }
        }
    }

    if (showIgnoreConfirm) {
        AlertDialog(
            onDismissRequest = { showIgnoreConfirm = false },
            title = { Text(text = stringResource(R.string.raw_event_person_ignore_confirm_title)) },
            text = { Text(text = stringResource(R.string.raw_event_person_ignore_confirm_body)) },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.raw_event_person_ignore_confirm_action),
                    onClick = {
                        showIgnoreConfirm = false
                        onParticipantIgnore(participant.participantId)
                    },
                    enabled = !saving,
                    variant = BecalmButtonVariant.Destructive,
                    size = BecalmButtonSize.Compact,
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.raw_event_person_cancel_action),
                    onClick = { showIgnoreConfirm = false },
                    variant = BecalmButtonVariant.Tertiary,
                    size = BecalmButtonSize.Compact,
                )
            },
        )
    }
}

@Composable
private fun RawEventChoiceAvatar(seed: String) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = seed.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun RawEventSyncStatusBanner(syncStatus: String?) {
    val (titleRes, bodyRes) = rawEventSyncStatusCopy(syncStatus) ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun rawEventSyncStatusCopy(syncStatus: String?): Pair<Int, Int>? =
    when (syncStatus) {
        "failed", "quarantined" -> R.string.raw_event_sync_failed_title to R.string.raw_event_sync_failed_body
        "awaiting_consent" -> R.string.raw_event_sync_awaiting_consent_title to R.string.raw_event_sync_awaiting_consent_body
        else -> null
    }

private fun visibleRawEventCommitments(commitments: List<RawEventCommitmentSummary>): List<RawEventCommitmentSummary> =
    commitments.filterNot { CommitmentDisplayPolicy.isDecisionContextItem(it.itemType) }

private fun RawEventDetailUiState.hasAudioTranscriptDetail(): Boolean =
    sourceType != null &&
        sourceType in RAW_EVENT_TRANSCRIPT_SOURCE_TYPES &&
        hasArchivedOriginalDetail()

private val RAW_EVENT_TRANSCRIPT_SOURCE_TYPES = setOf(
    SourceType.VOICE,
    SourceType.MEETING,
    SourceType.CALL_RECORDING,
)

@Composable
private fun RawEventTranscriptSection(state: RawEventDetailUiState) {
    if (!state.hasAudioTranscriptDetail()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.raw_event_transcript_title),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        SourceOriginalBodyBlock(
            archivedOriginal = state.archivedOriginal,
            body = null,
        )
    }
}

@Composable
private fun RawEventWhySection(
    whyText: String,
    modifier: Modifier = Modifier,
) {
    EvidenceCard(
        modifier = modifier.testTag("raw-event-why-action"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.commitment_action_evidence_why),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = whyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun rawEventWhyText(commitments: List<RawEventCommitmentSummary>): String? {
    if (commitments.isEmpty()) return null
    if (commitments.size == 1) {
        val item = commitments.first()
        return stringResource(
            R.string.raw_event_action_reason_single_fmt,
            rawEventReasonKindLabel(item),
            item.title,
        )
    }
    return stringResource(R.string.raw_event_action_reason_multi_fmt, commitments.size)
}

@Composable
private fun rawEventReasonKindLabel(item: RawEventCommitmentSummary): String =
    when {
        item.itemType == CommitmentItemType.SCHEDULE -> stringResource(R.string.commitment_item_type_schedule)
        isTakeDirection(item.direction) -> stringResource(R.string.commitments_filter_take)
        else -> stringResource(R.string.commitments_filter_give)
    }

@Composable
private fun RawEventExtractionSection(
    commitments: List<RawEventCommitmentSummary>,
    modifier: Modifier = Modifier,
) {
    if (commitments.isEmpty()) return
    val myActions = commitments.filter {
        it.itemType != CommitmentItemType.SCHEDULE && !isTakeDirection(it.direction)
    }
    val theirActions = commitments.filter {
        it.itemType != CommitmentItemType.SCHEDULE && isTakeDirection(it.direction)
    }
    val schedules = commitments.filter { it.itemType == CommitmentItemType.SCHEDULE }

    EvidenceCard(
        modifier = modifier.testTag("raw-event-extracted-commitments"),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(
                    text = stringResource(R.string.raw_event_extracted_commitments_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RawEventCommitmentBucket(
                label = stringResource(R.string.person_detail_bucket_my_actions),
                items = myActions,
            )
            RawEventCommitmentBucket(
                label = stringResource(R.string.person_detail_bucket_their_actions),
                items = theirActions,
            )
            RawEventCommitmentBucket(
                label = stringResource(R.string.commitment_item_type_schedule),
                items = schedules,
            )
        }
    }
}

@Composable
private fun RawEventCommitmentBucket(
    label: String,
    items: List<RawEventCommitmentSummary>,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { item ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f), CircleShape)
                        .alpha(0.9f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.quote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ─── Non-email fallback layout ────────────────────────────────────────────────

/**
 * Minimal layout for voice / calendar / call_recording events — the common header
 * (source badge + title + timestamp) only. Per-source extended fields
 * (`duration_seconds`, `location`, `attendees_raw`) are intentionally
 * out of scope for this plan; future plans (`ui-raw-event-voice-rendering`,
 * `ui-raw-event-calendar-rendering`) will specialize each branch the same way
 * [EmailEventDetailSection] does for email.
 */
@Composable
private fun NonEmailEventDetailSection(state: RawEventDetailUiState) {
    val sourceType = state.sourceType ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EventSourceBadge(sourceType = sourceType)
        if (state.eventTitle != null) {
            EventTitleText(title = state.eventTitle)
        }
        if (state.snippet != null) {
            Text(
                text = state.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        state.timestamp?.let { IngestionTimestamp(timestamp = it) }
    }
}

@PreviewLightDark
@Composable
private fun PreviewRawEventDetailSheetLoading() {
    BecalmTheme {
        BecalmScaffold(
            title = "Event Detail",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
        ) { padding ->
            BecalmSheetSkeleton(modifier = Modifier.padding(padding))
        }
    }
}
