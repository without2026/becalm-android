package com.becalm.android.ui.persons

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.ui.components.CommitmentsExtractedBadge
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.IngestionTimestamp
import com.becalm.android.ui.theme.glassPanel

/**
 * Unified row renderer for the "상호작용 히스토리" section of [PersonDetailScreen]
 * — replaces the per-branch inlined layouts that lived inside the screen file.
 *
 * Row composition per `.spec/contracts/ui-map.yml:206-210`:
 * source badge → title → snippet → ingestion timestamp → optional "약속 추출
 * N건" badge (SRC-008). For [InteractionRow.Event] the whole row is tappable
 * and navigates to [com.becalm.android.ui.navigation.BecalmRoute.RawEventDetail]
 * via [onEventTap]; [SRC-004 `.spec/source-viewer.spec.yml:37-45`] requires this
 * drill-down path.
 *
 * Commitment and calendar branches are non-interactive display rows today;
 * their tap flows live in separate plan docs.
 */
@Composable
internal fun InteractionHistoryRow(
    row: InteractionRow,
    onEventTap: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier = when (row) {
        is InteractionRow.Event -> {
            val rawEventId = row.rawEventId
            if (rawEventId != null) {
                modifier
                    .testTag("person-detail-event-${row.id}")
                    .clickable { onEventTap(rawEventId) }
            } else {
                modifier.testTag("person-detail-event-${row.id}")
            }
        }
        else -> modifier
    }
    Column(
        modifier = rowModifier
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (row) {
            is InteractionRow.Event -> EventRow(row)
            is InteractionRow.Commitment -> CommitmentRow(row)
            is InteractionRow.CalendarMeeting -> CalendarMeetingRow(row)
        }
    }
}

// ─── Branches ─────────────────────────────────────────────────────────────────

@Composable
private fun EventRow(row: InteractionRow.Event) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EventSourceBadge(sourceType = row.source)
        IngestionTimestamp(timestamp = row.timestamp)
    }
    Text(
        text = row.summary ?: stringResource(R.string.raw_event_detail_no_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (!row.snippet.isNullOrBlank()) {
        Text(
            text = row.snippet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (row.commitmentsExtractedCount > 0) {
        CommitmentsExtractedBadge(count = row.commitmentsExtractedCount)
    }
}

@Composable
private fun CommitmentRow(row: InteractionRow.Commitment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = commitmentTypeLabel(row.itemType),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        IngestionTimestamp(timestamp = row.timestamp)
    }
    Text(
        text = row.title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val statusText = when (row.itemType) {
        CommitmentItemType.SCHEDULE -> row.scheduleStatus
        CommitmentItemType.DECISION -> row.decisionStatus
        else -> row.direction ?: row.actionState
    }
    if (!statusText.isNullOrBlank()) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CalendarMeetingRow(row: InteractionRow.CalendarMeeting) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.today_section_meetings),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        IngestionTimestamp(timestamp = row.timestamp)
    }
    Text(
        text = row.title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun commitmentTypeLabel(itemType: String): String = when (itemType) {
    CommitmentItemType.SCHEDULE -> stringResource(R.string.commitment_item_type_schedule)
    CommitmentItemType.DECISION -> stringResource(R.string.commitment_item_type_decision)
    else -> stringResource(R.string.person_detail_commitments_section)
}
