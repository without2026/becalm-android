package com.becalm.android.data.remote.dto

/**
 * String constants for the `source_type` field used across multiple tables and DTOs.
 *
 * Wire values must match the `source_type` enum in data-model.yml exactly.
 * Used in:
 * - [RawIngestionEventDto.sourceType]
 * - [CommitmentDto.sourceType]
 * - [CalendarEventDto.sourceType]
 *
 * SP-09 (Room entities) imports from this object — do not rename constants.
 */
public object SourceType {
    /** Audio recording captured by the Android voice recorder. */
    public const val VOICE: String = "voice"

    /** Call recording captured on Samsung One UI (`Recordings/Call/`). Matches data-model.yml:28-32 enum. */
    public const val CALL_RECORDING: String = "call_recording"

    /** User-imported meeting audio saved under `Recordings/BeCalm Meetings/Audio/`. */
    public const val MEETING: String = "meeting"

    /** User-imported messenger screenshot saved in app-private source import storage. */
    public const val MESSAGE_SCREENSHOT: String = "message_screenshot"

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

    /**
     * User-corrected/superseded commitment provenance. No associated raw_ingestion_events row.
     *
     * Valid ONLY on CommitmentEntity.sourceType — raw_ingestion_events never uses "manual"
     * (MAN-003 invariant). Commitments tab no longer exposes blank manual creation;
     * this value remains for EDIT-007 supersede compatibility and existing rows.
     */
    public const val MANUAL: String = "manual"

    /**
     * **Raw-ingestion source set** — every `source_type` value that can appear on a
     * [RawIngestionEventDto] / [CalendarEventDto] coming off the wire or being cached
     * from Room. Intentionally EXCLUDES [MANUAL] because user-corrected manual
     * commitments have no backing `raw_ingestion_events` row (MAN-003 invariant at
     * `.spec/manual-commitment.spec.yml`); allowing `manual` here would cause
     * deep-link routes like `/sources/manual` to render a bogus source-detail screen
     * and any server-emitted `manual` status item to be cached as if it were a
     * syncable source. A future `COMMITMENT_SOURCES` superset (which would add
     * [MANUAL]) will land with the Stage-5 CommitmentDto validator that first needs
     * it; deliberately not shipped here to satisfy DEADCODE-02.
     *
     * Includes [VOICE] and [CALL_RECORDING] even though neither has a product-UI tile
     * yet — the server is allowed to emit them on raw-ingestion rows. For the narrower
     * "connectable-account" set used by the Sources screen, see [PRODUCT_SOURCES].
     *
     * Spec ref: data-model.yml:28-32.
     */
    public val ALL: Set<String> = setOf(
        VOICE,
        CALL_RECORDING,
        MEETING,
        MESSAGE_SCREENSHOT,
        GMAIL,
        OUTLOOK_MAIL,
        NAVER_IMAP,
        DAUM_IMAP,
        GOOGLE_CALENDAR,
        OUTLOOK_CALENDAR,
    )

    /**
     * **Product-UI source set** — the user-facing sources shown in Sources and Today.
     *
     * Includes [VOICE] because the product treats recorder ingestion as a first-class
     * user-facing source (Today chips / Sources list / overall sync), but continues to
     * exclude [CALL_RECORDING] because wave 0 ships only the schema-side carve-out.
     *
     * Iteration order is the canonical render order for the Sources list and Today strip.
     */
    public val PRODUCT_SOURCES: Set<String> = setOf(
        VOICE,
        MEETING,
        MESSAGE_SCREENSHOT,
        GMAIL,
        OUTLOOK_MAIL,
        NAVER_IMAP,
        DAUM_IMAP,
        GOOGLE_CALENDAR,
        OUTLOOK_CALENDAR,
    )

    /** True when the value is allowed on raw_ingestion_events and extraction workers. */
    public fun isRawIngestionSource(sourceType: String): Boolean = sourceType in ALL

    /** True only for user-authored commitments with no backing raw event. */
    public fun isManualCommitmentSource(sourceType: String): Boolean = sourceType == MANUAL
}
