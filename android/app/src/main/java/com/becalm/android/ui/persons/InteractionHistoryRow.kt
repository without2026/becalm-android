package com.becalm.android.ui.persons

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.CommitmentsExtractedBadge
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.EventSourceBadge
import com.becalm.android.ui.components.IngestionTimestamp

/**
 * Source-event card renderer for [PersonDetailScreen].
 * One card owns the original source evidence plus extracted give/take/schedule items.
 */
@Composable
internal fun SourceEventCardRow(
    card: SourceEventCardProjection,
    onEventTap: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clickableModifier = card.rawEventId?.let { rawEventId ->
        modifier.clickable { onEventTap(rawEventId) }
    } ?: modifier
    EvidenceCard(
        modifier = clickableModifier.testTag("person-detail-source-card-${card.sourceEventKey}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EventSourceBadge(sourceType = card.sourceType)
                IngestionTimestamp(timestamp = card.occurredAt)
            }
            Text(
                text = card.title ?: stringResource(R.string.raw_event_detail_no_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!card.snippet.isNullOrBlank()) {
                Text(
                    text = card.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CommitmentBucket(label = stringResource(R.string.person_detail_bucket_my_actions), items = card.myActions)
            CommitmentBucket(label = stringResource(R.string.person_detail_bucket_their_actions), items = card.theirActions)
            CommitmentBucket(label = stringResource(R.string.commitment_item_type_schedule), items = card.schedules)
            if (card.linkedCalendarEventId != null) {
                Text(
                    text = stringResource(R.string.person_detail_linked_calendar),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (card.relatedSourceTypes.isNotEmpty()) {
                val distinctSources = card.relatedSourceTypes.distinct()
                var relatedLabels = ""
                for ((index, sourceType) in distinctSources.withIndex()) {
                    if (index > 0) relatedLabels += ", "
                    relatedLabels += stringResource(sourceTypeLabelRes(sourceType))
                }
                Text(
                    text = stringResource(
                        R.string.person_detail_related_records_fmt,
                        relatedLabels,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.commitmentsExtractedCount > 0) {
                CommitmentsExtractedBadge(count = card.commitmentsExtractedCount)
            }
        }
    }
}

@StringRes
private fun sourceTypeLabelRes(sourceType: String): Int = when (sourceType) {
    SourceType.GMAIL -> R.string.raw_event_source_badge_gmail
    SourceType.OUTLOOK_MAIL -> R.string.raw_event_source_badge_outlook_mail
    SourceType.NAVER_IMAP -> R.string.raw_event_source_badge_naver_imap
    SourceType.DAUM_IMAP -> R.string.raw_event_source_badge_daum_imap
    SourceType.GOOGLE_CALENDAR -> R.string.raw_event_source_badge_google_calendar
    SourceType.OUTLOOK_CALENDAR -> R.string.raw_event_source_badge_outlook_calendar
    SourceType.VOICE -> R.string.raw_event_source_badge_voice
    SourceType.CALL_RECORDING -> R.string.raw_event_source_badge_call_recording
    SourceType.MEETING -> R.string.raw_event_source_badge_meeting
    SourceType.MESSAGE_SCREENSHOT -> R.string.raw_event_source_badge_message_screenshot
    else -> R.string.raw_event_source_badge_unknown
}

@Composable
private fun CommitmentBucket(
    label: String,
    items: List<PersonDetailCommitmentSummary>,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        items.forEach { item ->
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
