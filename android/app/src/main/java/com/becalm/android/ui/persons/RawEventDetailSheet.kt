package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmSheetSkeleton
import com.becalm.android.ui.components.EMAIL_SOURCE_TYPES
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.EventTitleText
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.IngestionTimestamp
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Raw event detail screen — extended fields loaded from Room for a single ingestion event.
 *
 * Branches by `source_type` in [RawEventDetailUiState.sourceType]:
 * - **Email sources** ([EMAIL_SOURCE_TYPES]) → [EmailEventDetailSection] which renders
 *   the six SRC-004 / EMAIL-003 / EMAIL-004 components (source badge, title, snippet,
 *   body, attachments pill, commitments-extracted badge, KST timestamp).
 * - **Non-email sources** (voice / calendar / call_recording) → a minimal common layout
 *   (source badge + title + KST timestamp). Voice and calendar extended fields
 *   (`duration_seconds`, `location`, `attendees_raw`) are deferred to their own plan
 *   docs and intentionally not rendered here.
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        EvidenceCard(
            modifier = Modifier
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
        ) {
            if (state.sourceType in EMAIL_SOURCE_TYPES) {
                EmailEventDetailSection(
                    state = state,
                )
            } else {
                NonEmailEventDetailSection(state = state)
            }
        }
    }
}

// ─── Non-email fallback layout ────────────────────────────────────────────────

/**
 * Minimal layout for voice / calendar / call_recording events — the common header
 * (source badge + title + timestamp) only. Per-source extended fields
 * (`duration_seconds`, `transcript`, `location`, `attendees_raw`) are intentionally
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
                        contentDescription = "Back",
                    )
                }
            },
        ) { padding ->
            BecalmSheetSkeleton(modifier = Modifier.padding(padding))
        }
    }
}
