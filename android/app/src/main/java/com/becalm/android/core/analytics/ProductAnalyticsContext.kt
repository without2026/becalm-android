package com.becalm.android.core.analytics

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

@Singleton
public class ProductAnalyticsContext @Inject constructor() {
    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var currentEntrySource: String = ENTRY_SOURCE_ORGANIC

    @Volatile
    private var latestNotificationOpen: NotificationOpenContext? = null

    public fun startSession(sessionId: String): String {
        currentSessionId = sessionId
        val entrySource = currentEntrySource
        currentEntrySource = ENTRY_SOURCE_ORGANIC
        return entrySource
    }

    public fun endSession() {
        currentSessionId = null
        latestNotificationOpen = null
        currentEntrySource = ENTRY_SOURCE_ORGANIC
    }

    public fun markNotificationOpened(
        notificationInstanceId: String?,
        commitmentId: String?,
        openedAt: Instant = Clock.System.now(),
    ) {
        currentEntrySource = ENTRY_SOURCE_NOTIFICATION_OPEN
        if (!notificationInstanceId.isNullOrBlank()) {
            latestNotificationOpen = NotificationOpenContext(
                notificationInstanceId = notificationInstanceId,
                commitmentId = commitmentId,
                openedAt = openedAt,
            )
        }
    }

    public fun enrich(event: ProductAnalyticsEvent): ProductAnalyticsEvent {
        val baseProperties = event.properties.toMutableMap()
        baseProperties.putIfAbsent("entry_source", currentEntrySource)
        val notificationOpen = latestNotificationOpen
        if (event.eventName == ProductAnalyticsEvents.COMMITMENT_ACTION_SELECTED && notificationOpen != null) {
            baseProperties.putIfAbsent("notification_instance_id", notificationOpen.notificationInstanceId)
            if (notificationOpen.commitmentId != null) {
                baseProperties.putIfAbsent("notification_commitment_id", notificationOpen.commitmentId)
            }
            val secondsSinceOpen = (
                event.occurredAt.toEpochMilliseconds() - notificationOpen.openedAt.toEpochMilliseconds()
            ).coerceAtLeast(0L) / 1000L
            baseProperties.putIfAbsent("seconds_since_notification_open", secondsSinceOpen)
        }
        return event.copy(
            sessionId = event.sessionId ?: currentSessionId,
            properties = baseProperties,
        )
    }

    private data class NotificationOpenContext(
        val notificationInstanceId: String,
        val commitmentId: String?,
        val openedAt: Instant,
    )

    public companion object {
        public const val ENTRY_SOURCE_ORGANIC: String = "organic"
        public const val ENTRY_SOURCE_NOTIFICATION_OPEN: String = "notification_open"
    }
}
