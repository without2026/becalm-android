package com.becalm.android.data.remote.dto

/**
 * String constants for the `source_type` field used across multiple tables and DTOs.
 *
 * Wire values must match the `source_type` enum in data-model.yml exactly.
 * Used in:
 * - [RawIngestionEventDto.sourceType]
 * - [CommitmentDto.sourceType]
 * - [CalendarEventDto.sourceType]
 * - SourceStatusDto.sourceType (removed — speculative, CONTRACT GAP, zero callers)
 * - AckCursorRequest.sourceType (removed — speculative, CONTRACT GAP, zero callers)
 *
 * SP-09 (Room entities) imports from this object — do not rename constants.
 */
public object SourceType {
    /** Audio recording captured by the Android voice recorder. */
    public const val VOICE: String = "voice"

    /** Gmail message via Google Gmail API. */
    public const val GMAIL: String = "gmail"

    /** Outlook / Microsoft 365 email via MS Graph API. */
    public const val OUTLOOK_MAIL: String = "outlook_mail"

    /** Naver Mail accessed via IMAP. */
    public const val NAVER_IMAP: String = "naver_imap"

    /** Daum Mail accessed via IMAP. */
    public const val DAUM_IMAP: String = "daum_imap"

    /** Google Calendar event via Google Calendar API. */
    public const val GOOGLE_CALENDAR: String = "google_calendar"

    /** Outlook / Microsoft 365 calendar event via MS Graph API. */
    public const val OUTLOOK_CALENDAR: String = "outlook_calendar"

    /** All declared source type values. Useful for validation. */
    public val ALL: Set<String> = setOf(
        VOICE,
        GMAIL,
        OUTLOOK_MAIL,
        NAVER_IMAP,
        DAUM_IMAP,
        GOOGLE_CALENDAR,
        OUTLOOK_CALENDAR,
    )
}
