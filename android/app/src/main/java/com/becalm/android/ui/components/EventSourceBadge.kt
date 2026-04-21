package com.becalm.android.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Pill-shaped badge rendering the originating source of a raw ingestion event
 * (e.g. "Gmail", "Naver 메일", "Google Calendar").
 *
 * Renders as a secondary-container Surface with a small leading icon plus a short
 * label. The icon is chosen by [SourceType] group — email envelope for the four
 * email providers, mic for voice captures, calendar for calendar events — so the
 * channel is recognizable at a glance before the user reads the label.
 *
 * Unknown `source_type` values render a generic fallback rather than throwing, so
 * a new wire value shipped from the server cannot crash the sheet.
 *
 * Spec: `.spec/contracts/ui-map.yml:113-118 § RawEventDetailSheet.components`.
 */
@Composable
public fun EventSourceBadge(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val labelRes = sourceLabelResource(sourceType)
    val icon = sourceIcon(sourceType)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null, // label below already conveys the source
                modifier = Modifier.size(size = 14.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun sourceLabelResource(sourceType: String): Int = when (sourceType) {
    SourceType.GMAIL -> R.string.raw_event_source_badge_gmail
    SourceType.OUTLOOK_MAIL -> R.string.raw_event_source_badge_outlook_mail
    SourceType.NAVER_IMAP -> R.string.raw_event_source_badge_naver_imap
    SourceType.DAUM_IMAP -> R.string.raw_event_source_badge_daum_imap
    SourceType.GOOGLE_CALENDAR -> R.string.raw_event_source_badge_google_calendar
    SourceType.OUTLOOK_CALENDAR -> R.string.raw_event_source_badge_outlook_calendar
    SourceType.VOICE -> R.string.raw_event_source_badge_voice
    SourceType.CALL_RECORDING -> R.string.raw_event_source_badge_call_recording
    else -> R.string.raw_event_source_badge_unknown
}

private fun sourceIcon(sourceType: String): ImageVector = when (sourceType) {
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
    SourceType.NAVER_IMAP,
    SourceType.DAUM_IMAP -> Icons.Outlined.Email
    SourceType.GOOGLE_CALENDAR,
    SourceType.OUTLOOK_CALENDAR -> Icons.Outlined.CalendarMonth
    SourceType.VOICE,
    SourceType.CALL_RECORDING -> Icons.Outlined.Mic
    else -> Icons.Outlined.Email
}

@PreviewLightDark
@Composable
private fun PreviewEventSourceBadgeGmail() {
    BecalmTheme { EventSourceBadge(sourceType = SourceType.GMAIL) }
}

@PreviewLightDark
@Composable
private fun PreviewEventSourceBadgeVoice() {
    BecalmTheme { EventSourceBadge(sourceType = SourceType.VOICE) }
}
