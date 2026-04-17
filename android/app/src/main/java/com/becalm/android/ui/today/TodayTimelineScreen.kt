package com.becalm.android.ui.today

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EmptyState
import com.becalm.android.ui.components.ErrorState
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.dimens
import com.becalm.android.ui.theme.glassPanel
import kotlinx.datetime.Instant

/**
 * Today screen — unified calendar events + due commitments timeline.
 *
 * Renders a time-sorted list of [TimelineItem] for today. Shows a loading
 * spinner while [TodayUiState.loading] is true. Shows [ErrorState] when
 * [TodayUiState.error] is non-null (e.g. unauthenticated).
 *
 * spec: TDY-001..TDY-007
 *
 * Primary VM: [TodayViewModel]
 * Navigation entry: [BecalmRoute.Today]
 * Navigation exit: [BecalmRoute.Settings] (via top-right icon)
 */
@Composable
public fun TodayTimelineScreen(
    navController: NavHostController,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    BecalmScaffold(
        title = stringResource(R.string.today_title),
        actions = {
            if (state.overallSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 4.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(onClick = { navController.navigate(BecalmRoute.Settings.path) }) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.label_settings),
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                ErrorState(
                    title = stringResource(R.string.error_generic_title),
                    message = state.error,
                    modifier = Modifier.padding(padding),
                )
            }
            state.timeline.isEmpty() -> {
                EmptyState(
                    title = stringResource(R.string.today_empty_title),
                    message = stringResource(R.string.today_empty_message),
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                TimelineList(
                    items = state.timeline,
                    contentPadding = padding,
                )
            }
        }
    }
}

@Composable
private fun TimelineList(
    items: List<TimelineItem>,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = items,
            key = { item ->
                when (item) {
                    is TimelineItem.Commitment -> "commitment-${item.id}"
                    is TimelineItem.CalendarEvent -> "event-${item.id}"
                    is TimelineItem.Meeting -> "meeting-${item.id}"
                }
            },
        ) { item ->
            TimelineItemRow(
                item = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun TimelineItemRow(
    item: TimelineItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        val sectionLabel = when (item) {
            is TimelineItem.Commitment -> stringResource(R.string.today_section_commitments)
            is TimelineItem.CalendarEvent -> stringResource(R.string.today_section_events)
            is TimelineItem.Meeting -> stringResource(R.string.today_section_meetings)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sectionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        val title = when (item) {
            is TimelineItem.Commitment -> item.title
            is TimelineItem.CalendarEvent -> item.title
            is TimelineItem.Meeting -> item.title
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewTodayTimelineScreenWithItems() {
    BecalmTheme {
        BecalmScaffold(title = "Today") { padding ->
            EmptyState(
                title = "Clear day ahead",
                message = "No commitments or meetings scheduled for today.",
                modifier = Modifier.padding(padding),
            )
        }
    }
}
