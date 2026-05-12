package com.becalm.android.ui.components

import com.becalm.android.data.remote.dto.SourceType

/**
 * Source category helpers shared by UI projections and source-detail surfaces.
 *
 * Keeping these helpers in the shared component layer prevents filter chips,
 * source-event cards, next-action inference, and raw-event detail rendering from
 * drifting when a provider is added.
 */
internal val EMAIL_SOURCE_TYPES: Set<String> = setOf(
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
    SourceType.NAVER_IMAP,
    SourceType.DAUM_IMAP,
)

internal val CALL_SOURCE_TYPES: Set<String> = setOf(
    SourceType.VOICE,
    SourceType.CALL_RECORDING,
)

internal val CALENDAR_SOURCE_TYPES: Set<String> = setOf(
    SourceType.GOOGLE_CALENDAR,
    SourceType.OUTLOOK_CALENDAR,
)

internal val MEETING_SOURCE_TYPES: Set<String> = setOf(
    SourceType.MEETING,
)

internal fun String.isEmailSource(): Boolean =
    this in EMAIL_SOURCE_TYPES ||
        contains("email", ignoreCase = true) ||
        contains("mail", ignoreCase = true) ||
        contains("imap", ignoreCase = true)

internal fun String.isCallSource(): Boolean =
    this in CALL_SOURCE_TYPES ||
        contains("call", ignoreCase = true)

internal fun String.isCalendarSource(): Boolean =
    this in CALENDAR_SOURCE_TYPES ||
        contains("calendar", ignoreCase = true)

internal fun String.isMeetingSource(): Boolean =
    this in MEETING_SOURCE_TYPES

internal fun String.isMeetingTimelineSource(): Boolean =
    isCalendarSource() || isMeetingSource()
