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
     * User-created commitment via CommitmentCreateSheet. No associated raw_ingestion_events row.
     * See manual-commitment.spec.yml MAN-001..006.
     *
     * Valid ONLY on [CommitmentEntity.sourceType] — raw_ingestion_events never uses "manual"
     * (MAN-003 invariant). Out-of-wire-scope for POST /v1/raw_ingestion_events:batch.
     */
    public const val MANUAL: String = "manual"

    /**
     * **Raw-ingestion source set** — every `source_type` value that can appear on a
     * [RawIngestionEventDto] / [CalendarEventDto] coming off the wire or being cached
     * from Room. Intentionally EXCLUDES [MANUAL] because manual-created commitments
     * have no backing `raw_ingestion_events` row (MAN-003 invariant at
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
        GMAIL,
        OUTLOOK_MAIL,
        NAVER_IMAP,
        DAUM_IMAP,
        GOOGLE_CALENDAR,
        OUTLOOK_CALENDAR,
    )

    /**
     * **Product-UI source set** — the external product sources the user connects to in
     * the Sources screen and whose sync state feeds the Today aggregate banner.
     *
     * Excludes [VOICE] (handled by its own capture path — recorder UI, not a connect-able
     * account) and [CALL_RECORDING] (wave 0 carve-out: schema-only for now; no UI tile,
     * no ingestion worker — a future wave will ship the Samsung call-recording
     * ingestion + UI and promote it out of this carve-out).
     *
     * Iteration order is the canonical render order for the Sources list and the chips
     * strip on the Today screen.
     *
     * Use this for:
     * - [SourceStatusRepository.observeAll] — the list of rows rendered to the user
     * - [TodayOverallSync.deriveOverallState] — the "all synced" banner math
     * - Any consumer whose output ends up on screen or drives product-facing aggregates
     *
     * Do **not** use for DTO validation — see [ALL].
     *
     * Spec ref: wave-0 carve-out (CALL_RECORDING schema lands ahead of its UI/ingestion).
     */
    public val PRODUCT_SOURCES: Set<String> = setOf(
        GMAIL,
        OUTLOOK_MAIL,
        NAVER_IMAP,
        DAUM_IMAP,
        GOOGLE_CALENDAR,
        OUTLOOK_CALENDAR,
    )
}
