package com.becalm.android.productanalytics

import kotlinx.datetime.Instant

public data class ProductAnalyticsEvent(
    val eventId: String,
    val eventName: String,
    val occurredAt: Instant,
    val sessionId: String?,
    val source: String = SOURCE_ANDROID,
    val properties: Map<String, Any?> = emptyMap(),
) {
    public companion object {
        public const val SOURCE_ANDROID: String = "android"
    }
}

public interface ProductAnalyticsClient {
    public fun track(
        eventName: String,
        properties: Map<String, Any?> = emptyMap(),
        sessionId: String? = null,
    )

    public fun flush()
}

public object NoopProductAnalyticsClient : ProductAnalyticsClient {
    override fun track(eventName: String, properties: Map<String, Any?>, sessionId: String?) = Unit
    override fun flush() = Unit
}

public object ProductAnalyticsNames {
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

    public val ALLOWLIST: Set<String> = setOf(
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
    )
}
