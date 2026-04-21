package com.becalm.android.ui.persons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.CommitmentsExtractedBadge
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.IngestionTimestamp
import com.becalm.android.ui.theme.glassPanel

/**
 * Unified row renderer for the "상호작용 히스토리" section of [PersonDetailScreen]
 * — replaces the per-branch inlined layouts that lived inside the screen file.
 *
 * Each row shows, top-to-bottom: source badge → title → optional snippet →
 * ingestion timestamp → optional "약속 추출 N건" badge (SRC-008). Branches:
 *
 * - [InteractionRow.Event] — source-type badge, event title, snippet.
 *   Optional commitments-extracted badge when [InteractionRow.Event.commitmentsExtractedCount]
 *   is > 0.
 * - [InteractionRow.Commitment] — "commitment" pseudo-badge, commitment title.
 *   Commitment rows never carry an independent snippet or extracted-count.
 * - [InteractionRow.CalendarMeeting] — calendar badge, meeting title.
 *
 * Spec: `.spec/contracts/ui-map.yml:106-111 § PersonDetail.components § InteractionHistoryRow`,
 * `.spec/contracts/ui-map.yml:206-210`, SRC-008.
 */
@Composable
internal fun InteractionHistoryRow(
    row: InteractionRow,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
    EventSourceBadge(sourceType = row.source)
    Text(
        text = row.summary ?: stringResource(R.string.raw_event_detail_no_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    IngestionTimestamp(timestamp = row.timestamp)
    if (row.commitmentsExtractedCount > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CommitmentsExtractedBadge(count = row.commitmentsExtractedCount)
        }
    }
}

@Composable
private fun CommitmentRow(row: InteractionRow.Commitment) {
    Text(
        text = stringResource(R.string.person_detail_commitments_section),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = row.title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    IngestionTimestamp(timestamp = row.timestamp)
}

@Composable
private fun CalendarMeetingRow(row: InteractionRow.CalendarMeeting) {
    Text(
        text = stringResource(R.string.today_section_meetings),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        text = row.title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    IngestionTimestamp(timestamp = row.timestamp)
}
