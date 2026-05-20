package com.becalm.android.ui.persons

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.becalm.android.ui.components.EMAIL_SOURCE_TYPES
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.EventTitleText
import com.becalm.android.ui.components.EvidenceCard
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

    BecalmScaffold(
        title = stringResource(R.string.raw_event_detail_title),
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

        if (visibleExtractedCommitments.isNotEmpty()) {
            item {
                RawEventExtractionSection(
                    commitments = visibleExtractedCommitments,
                    modifier = Modifier.fillMaxWidth(),
                )
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
