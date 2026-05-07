package com.becalm.android.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType

internal data class SourcePresentation(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val accentColor: Color,
)

internal fun sourcePresentationFor(sourceType: String): SourcePresentation = when (sourceType) {
    SourceType.GMAIL -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_gmail,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFFB6463B),
    )

    SourceType.OUTLOOK_MAIL -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_outlook_mail,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF4D89B8),
    )

    SourceType.NAVER_IMAP -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_naver_imap,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF2E8F58),
    )

    SourceType.DAUM_IMAP -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_daum_imap,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF9A7A2C),
    )

    SourceType.GOOGLE_CALENDAR -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_google_calendar,
        icon = Icons.Outlined.CalendarMonth,
        accentColor = Color(0xFF6D8A43),
    )

    SourceType.OUTLOOK_CALENDAR -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_outlook_calendar,
        icon = Icons.Outlined.CalendarMonth,
        accentColor = Color(0xFF397F91),
    )

    SourceType.VOICE -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_voice,
        icon = Icons.Outlined.Mic,
        accentColor = Color(0xFF9A6B3E),
    )

    SourceType.CALL_RECORDING -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_call_recording,
        icon = Icons.Outlined.Phone,
        accentColor = Color(0xFF8A6A86),
    )

    SourceType.MEETING -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_meeting,
        icon = Icons.Outlined.Mic,
        accentColor = Color(0xFF7E7861),
    )

    SourceType.MESSAGE_SCREENSHOT -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_message_screenshot,
        icon = Icons.Outlined.Image,
        accentColor = Color(0xFF8B7158),
    )

    SourceType.MANUAL_TEXT -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_manual_text,
        icon = Icons.AutoMirrored.Outlined.Notes,
        accentColor = Color(0xFF6F7F68),
    )

    else -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_unknown,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF76726A),
    )
}
