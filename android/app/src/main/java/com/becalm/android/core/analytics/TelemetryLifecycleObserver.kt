package com.becalm.android.core.analytics

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.becalm.android.core.util.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class TelemetryLifecycleObserver @Inject constructor(
    private val analytics: ProductAnalyticsClient,
    private val analyticsContext: ProductAnalyticsContext,
    private val clock: Clock,
) : DefaultLifecycleObserver {

    private var currentSessionId: String? = null
    private var currentSessionStartedAtMillis: Long? = null

    override fun onStart(owner: LifecycleOwner) {
        val sessionId = UUID.randomUUID().toString()
        val now = clock.nowInstant()
        val entrySource = analyticsContext.startSession(sessionId)
        currentSessionId = sessionId
        currentSessionStartedAtMillis = now.toEpochMilliseconds()
        analytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.SESSION_STARTED,
                occurredAt = now,
                sessionId = sessionId,
                properties = mapOf("entry_source" to entrySource),
            ),
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        val sessionId = currentSessionId ?: return
        val now = clock.nowInstant()
        val startedAt = currentSessionStartedAtMillis
        analytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.SESSION_ENDED,
                occurredAt = now,
                sessionId = sessionId,
                properties = mapOf(
                    "duration_seconds" to (
                        if (startedAt == null) 0L else ((now.toEpochMilliseconds() - startedAt) / 1000L).coerceAtLeast(0L)
                    ),
                ),
            ),
        )
        currentSessionId = null
        currentSessionStartedAtMillis = null
        analyticsContext.endSession()
    }
}
