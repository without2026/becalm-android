package com.becalm.android.core.analytics

import kotlinx.datetime.Instant

public interface ProductAnalyticsClient {
    public fun track(event: ProductAnalyticsEvent)
    public fun setUserScope(userId: String?)
    public fun resetUserScope()
}

public data class ProductAnalyticsEvent(
    val eventId: String,
    val eventName: String,
    val occurredAt: Instant,
    val sessionId: String? = null,
    val properties: Map<String, Any?> = emptyMap(),
)

public object ProductAnalyticsEvents {
    public const val SESSION_STARTED: String = "session_started"
    public const val SESSION_ENDED: String = "session_ended"
    public const val COMMITMENT_NOTIFICATION_POSTED: String = "commitment_notification_posted"
    public const val COMMITMENT_NOTIFICATION_OPENED: String = "commitment_notification_opened"
    public const val COMMITMENT_ACTION_SELECTED: String = "commitment_action_selected"
    public const val SCREEN_VIEWED: String = "screen_viewed"
    public const val SCREEN_EXITED: String = "screen_exited"
    public const val PERSON_MATCH_COMPLETED: String = "person_match_completed"
    public const val HISTORICAL_ITEM_VIEWED: String = "historical_item_viewed"
    public const val SEARCH_TO_DETAIL: String = "search_to_detail"
    public const val SEARCH_PERFORMED: String = "search_performed"
    public const val EVIDENCE_IMPORT_COMPLETED: String = "evidence_import_completed"
    public const val PMF_SURVEY_SUBMITTED: String = "pmf_survey_submitted"
    public const val SOURCE_OAUTH_STARTED: String = "source_oauth_started"
    public const val SOURCE_OAUTH_BROWSER_OPENED: String = "source_oauth_browser_opened"
    public const val SOURCE_OAUTH_CALLBACK_RECEIVED: String = "source_oauth_callback_received"
    public const val SOURCE_OAUTH_STATUS_CHECKED: String = "source_oauth_status_checked"
    public const val SOURCE_STATUS_REFRESHED: String = "source_status_refreshed"
    public const val SOURCE_STATUS_REFRESH_FAILED: String = "source_status_refresh_failed"
    public const val SOURCE_SYNC_STARTED: String = "source_sync_started"
    public const val SOURCE_SYNC_COMPLETED: String = "source_sync_completed"
    public const val SOURCE_SYNC_FAILED: String = "source_sync_failed"
    public const val EXTRACTION_STARTED: String = "extraction_started"
    public const val EXTRACTION_FILTERED: String = "extraction_filtered"
    public const val EXTRACTION_COMPLETED: String = "extraction_completed"
    public const val EXTRACTION_FAILED: String = "extraction_failed"
    public const val COMMITMENT_CORRECTION_SUBMITTED: String = "commitment_correction_submitted"
    public const val PERSON_MERGE_COMPLETED: String = "person_merge_completed"
    public const val PERSON_SPLIT_COMPLETED: String = "person_split_completed"
    public const val CONSENT_WITHDRAWN: String = "consent_withdrawn"
    public const val PROCESSING_PAUSED: String = "processing_paused"

    public val Allowed: Set<String> = setOf(
        SESSION_STARTED,
        SESSION_ENDED,
        COMMITMENT_NOTIFICATION_POSTED,
        COMMITMENT_NOTIFICATION_OPENED,
        COMMITMENT_ACTION_SELECTED,
        SCREEN_VIEWED,
        SCREEN_EXITED,
        PERSON_MATCH_COMPLETED,
        HISTORICAL_ITEM_VIEWED,
        SEARCH_TO_DETAIL,
        SEARCH_PERFORMED,
        EVIDENCE_IMPORT_COMPLETED,
        PMF_SURVEY_SUBMITTED,
        SOURCE_OAUTH_STARTED,
        SOURCE_OAUTH_BROWSER_OPENED,
        SOURCE_OAUTH_CALLBACK_RECEIVED,
        SOURCE_OAUTH_STATUS_CHECKED,
        SOURCE_STATUS_REFRESHED,
        SOURCE_STATUS_REFRESH_FAILED,
        SOURCE_SYNC_STARTED,
        SOURCE_SYNC_COMPLETED,
        SOURCE_SYNC_FAILED,
        EXTRACTION_STARTED,
        EXTRACTION_FILTERED,
        EXTRACTION_COMPLETED,
        EXTRACTION_FAILED,
        COMMITMENT_CORRECTION_SUBMITTED,
        PERSON_MERGE_COMPLETED,
        PERSON_SPLIT_COMPLETED,
        CONSENT_WITHDRAWN,
        PROCESSING_PAUSED,
    )
}
