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
    private val clock: Clock,
) : DefaultLifecycleObserver {

    private var currentSessionId: String? = null

    override fun onStart(owner: LifecycleOwner) {
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        analytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.SESSION_STARTED,
                occurredAt = clock.nowInstant(),
                sessionId = sessionId,
            ),
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        val sessionId = currentSessionId ?: return
        analytics.track(
            ProductAnalyticsEvent(
                eventId = UUID.randomUUID().toString(),
                eventName = ProductAnalyticsEvents.SESSION_ENDED,
                occurredAt = clock.nowInstant(),
                sessionId = sessionId,
            ),
        )
        currentSessionId = null
    }
}
