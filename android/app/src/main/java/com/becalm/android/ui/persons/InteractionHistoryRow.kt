package com.becalm.android.ui.persons

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.core.util.KST
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.BecalmActionPill
import com.becalm.android.ui.components.BecalmActionPillSize
import com.becalm.android.ui.components.BecalmActionPillVariant
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.theme.becalmColors
import kotlinx.datetime.Instant
import kotlinx.datetime.toLocalDateTime

/**
 * Source-event timeline row renderer for [PersonDetailScreen].
 * One row owns the original source evidence. Extracted give/take/schedule items
 * are compressed into a single derived tag so the relation timeline stays scannable.
 */
@Composable
internal fun SourceEventCardRow(
    card: SourceEventCardProjection,
    onEventTap: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onThreadToggle: (String) -> Unit = {},
) {
    val sourcePresentation = sourcePresentationFor(card.sourceType)
    val clickableModifier = card.rawEventId?.let { rawEventId ->
        modifier.clickable(role = Role.Button) { onEventTap(rawEventId) }
    } ?: modifier
    TimelineRowFrame(
        markerColor = sourcePresentation.accentColor,
        markerIcon = sourcePresentation.icon,
        modifier = clickableModifier.testTag("person-detail-source-card-${card.sourceEventKey}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(sourcePresentation.labelRes),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = card.occurredAt.timelineDateLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
                        maxLines = 1,
                    )
                }
                card.rawEventId?.let { rawEventId ->
                    TimelineOpenSourceIcon(
                        testTag = "person-detail-open-source-${card.sourceEventKey}",
                        onClick = { onEventTap(rawEventId) },
                    )
                }
            }
            Text(
                text = card.title ?: stringResource(R.string.raw_event_detail_no_title),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            derivedTag(card)?.let { tag ->
                TimelinePill(text = tag, contentColor = MaterialTheme.colorScheme.primary)
            }
            val expandableThreadCount = card.threadEvents.size
            if (card.isEmailThread && expandableThreadCount > 1) {
                TimelineActionPill(
                    text = stringResource(
                        if (expanded) {
                            R.string.person_detail_mail_thread_collapse_fmt
                        } else {
                            R.string.person_detail_mail_thread_expand_fmt
                        },
                        expandableThreadCount,
                    ),
                    onClick = { onThreadToggle(card.sourceEventKey) },
                    expanded = expanded,
                    trailingChevron = true,
                    modifier = Modifier.testTag("person-detail-thread-toggle-${card.sourceEventKey}"),
                )
            }
            if (!card.snippet.isNullOrBlank()) {
                Text(
                    text = card.snippet,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.linkedCalendarEventId != null) {
                TimelinePill(
                    text = stringResource(R.string.person_detail_linked_calendar),
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }
            if (card.relatedSourceTypes.isNotEmpty()) {
                val distinctSources = card.relatedSourceTypes.distinct()
                var relatedLabels = ""
                for ((index, sourceType) in distinctSources.withIndex()) {
                    if (index > 0) relatedLabels += ", "
                    relatedLabels += stringResource(sourceTypeLabelRes(sourceType))
                }
                TimelinePill(
                    text = stringResource(
                        R.string.person_detail_related_records_fmt,
                        relatedLabels,
                    ),
                )
            }
            if (expanded && card.threadEvents.size > 1) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    card.threadEvents.forEach { event ->
                        ThreadEventRow(
                            event = event,
                            isCurrent = event.rawEventId == card.rawEventId,
                            onEventTap = onEventTap,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadEventRow(
    event: PersonTimelineThreadEvent,
    isCurrent: Boolean,
    onEventTap: (eventId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onEventTap(event.rawEventId) }
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            .testTag("person-detail-thread-event-${event.rawEventId}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isCurrent) {
                    stringResource(R.string.person_detail_mail_thread_current)
                } else {
                    stringResource(sourceTypeLabelRes(event.sourceType))
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = event.occurredAt.timelineDateLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
            )
        }
        Text(
            text = event.title ?: stringResource(R.string.raw_event_detail_no_title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        event.snippet?.takeIf { it.isNotBlank() }?.let { snippet ->
            Text(
                text = snippet,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ScheduleCandidateTimelineRow(
    item: PersonTimelineItem.ScheduleCandidate,
    onEventTap: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedulePresentation = sourcePresentationFor(SourceType.GOOGLE_CALENDAR)
    val clickableModifier = item.rawEventId?.let { rawEventId ->
        modifier.clickable(role = Role.Button) { onEventTap(rawEventId) }
    } ?: modifier
    TimelineRowFrame(
        markerColor = schedulePresentation.accentColor,
        markerIcon = schedulePresentation.icon,
        modifier = clickableModifier.testTag("person-detail-schedule-candidate-${item.key}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimelinePill(
                        text = stringResource(R.string.person_detail_schedule_candidate_label),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = item.sortAt.timelineDateLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
                        maxLines = 1,
                    )
                }
                item.rawEventId?.let { rawEventId ->
                    TimelineOpenSourceIcon(
                        testTag = "person-detail-open-schedule-source-${item.key}",
                        onClick = { onEventTap(rawEventId) },
                    )
                }
            }
            Text(
                text = item.title ?: stringResource(R.string.person_detail_schedule_candidate_untitled),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.person_detail_schedule_candidate_body),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ConfirmedScheduleTimelineRow(
    item: PersonTimelineItem.ConfirmedSchedule,
    onEventTap: (eventId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val schedulePresentation = sourcePresentationFor(item.sourceType)
    val clickableModifier = item.rawEventId?.let { rawEventId ->
        modifier.clickable(role = Role.Button) { onEventTap(rawEventId) }
    } ?: modifier
    TimelineRowFrame(
        markerColor = schedulePresentation.accentColor,
        markerIcon = schedulePresentation.icon,
        modifier = clickableModifier.testTag("person-detail-confirmed-schedule-${item.key}"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimelinePill(
                        text = stringResource(R.string.person_detail_confirmed_schedule_label),
                        contentColor = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = item.sortAt.timelineDateLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.86f),
                        maxLines = 1,
                    )
                }
                item.rawEventId?.let { rawEventId ->
                    TimelineOpenSourceIcon(
                        testTag = "person-detail-open-confirmed-schedule-source-${item.key}",
                        onClick = { onEventTap(rawEventId) },
                    )
                }
            }
            Text(
                text = item.title.ifBlank { stringResource(R.string.person_detail_confirmed_schedule_untitled) },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.person_detail_confirmed_schedule_body),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TimelineActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    trailingChevron: Boolean = false,
) {
    BecalmActionPill(
        text = text,
        onClick = onClick,
        expanded = expanded,
        trailingChevron = trailingChevron,
        variant = BecalmActionPillVariant.Neutral,
        size = BecalmActionPillSize.Mini,
        modifier = modifier,
    )
}

@Composable
private fun TimelineRowFrame(
    markerColor: Color,
    markerIcon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TimelineMarker(color = markerColor, icon = markerIcon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun TimelineMarker(
    color: Color,
    icon: ImageVector,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            color = color.copy(alpha = 0.13f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.42f)),
            contentColor = color,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(1.dp)
                .weight(1f)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.68f)),
        )
    }
}

@Composable
private fun TimelineOpenSourceIcon(
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(28.dp)
            .testTag(testTag)
            .clickable(role = Role.Button, onClick = onClick),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = stringResource(R.string.person_detail_open_source),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun TimelinePill(
    text: String,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.becalmColors.glassBorder),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

private fun Instant.timelineDateLabel(): String {
    val localDateTime = toLocalDateTime(KST)
    val hour = localDateTime.hour.toString().padStart(2, '0')
    val minute = localDateTime.minute.toString().padStart(2, '0')
    return "${localDateTime.monthNumber}.${localDateTime.dayOfMonth} $hour:$minute"
}

@Composable
private fun derivedTag(card: SourceEventCardProjection): String? {
    val giveCount = card.myActions.size
    val takeCount = card.theirActions.size
    val scheduleCount = card.schedules.size
    val labels = listOfNotNull(
        if (giveCount > 0) stringResource(R.string.person_detail_timeline_tag_give_fmt, giveCount) else null,
        if (takeCount > 0) stringResource(R.string.person_detail_timeline_tag_take_fmt, takeCount) else null,
        if (scheduleCount > 0) stringResource(R.string.person_detail_timeline_tag_schedule_fmt, scheduleCount) else null,
    )
    if (labels.isNotEmpty()) {
        return labels.joinToString(" · ")
    }
    return if (card.commitmentsExtractedCount > 0) {
        stringResource(R.string.person_detail_timeline_tag_count_fmt, card.commitmentsExtractedCount)
    } else {
        null
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
