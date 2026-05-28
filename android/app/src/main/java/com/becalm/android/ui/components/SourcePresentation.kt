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
        accentColor = Color(0xFF8C5148),
    )

    SourceType.OUTLOOK_MAIL -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_outlook_mail,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF557489),
    )

    SourceType.NAVER_IMAP -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_naver_imap,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF557B62),
    )

    SourceType.DAUM_IMAP -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_daum_imap,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF816E45),
    )

    SourceType.GOOGLE_CALENDAR -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_google_calendar,
        icon = Icons.Outlined.CalendarMonth,
        accentColor = Color(0xFF6E7A55),
    )

    SourceType.OUTLOOK_CALENDAR -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_outlook_calendar,
        icon = Icons.Outlined.CalendarMonth,
        accentColor = Color(0xFF547984),
    )

    SourceType.VOICE -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_voice,
        icon = Icons.Outlined.Mic,
        accentColor = Color(0xFF82664D),
    )

    SourceType.CALL_RECORDING -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_call_recording,
        icon = Icons.Outlined.Phone,
        accentColor = Color(0xFF776778),
    )

    SourceType.MEETING -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_meeting,
        icon = Icons.Outlined.Mic,
        accentColor = Color(0xFF77705F),
    )

    SourceType.MESSAGE_SCREENSHOT -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_message_screenshot,
        icon = Icons.Outlined.Image,
        accentColor = Color(0xFF806E5C),
    )

    SourceType.MANUAL -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_manual,
        icon = Icons.AutoMirrored.Outlined.Notes,
        accentColor = Color(0xFF7C735F),
    )

    else -> SourcePresentation(
        labelRes = R.string.raw_event_source_badge_unknown,
        icon = Icons.Outlined.Email,
        accentColor = Color(0xFF76726A),
    )
}
