package com.becalm.android.productanalytics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.becalm.android.core.util.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class ProductSessionTracker @Inject constructor(
    private val analytics: ProductAnalyticsClient,
    private val clock: Clock,
) : Application.ActivityLifecycleCallbacks {
    private var registered: Boolean = false
    private var resumedCount: Int = 0
    private var currentSessionId: String? = null
    private var currentEntrySource: String = ENTRY_UNKNOWN
    private var sessionStartedAtMillis: Long = 0L
    private var lastBackgroundAtMillis: Long = 0L
    private var nextEntrySource: String? = null

    public fun register(application: Application) {
        if (registered) return
        application.registerActivityLifecycleCallbacks(this)
        registered = true
    }

    public fun markNextEntrySource(source: String) {
        nextEntrySource = source
    }

    public fun currentSessionId(): String? = currentSessionId

    override fun onActivityResumed(activity: Activity) {
        resumedCount += 1
        if (resumedCount == 1) {
            val now = nowMillis()
            val shouldStartNewSession = currentSessionId == null ||
                lastBackgroundAtMillis == 0L ||
                now - lastBackgroundAtMillis >= SESSION_TIMEOUT_MILLIS
            if (shouldStartNewSession) {
                currentSessionId = UUID.randomUUID().toString()
                currentEntrySource = nextEntrySource ?: ENTRY_ORGANIC
                sessionStartedAtMillis = now
                analytics.track(
                    ProductAnalyticsNames.SESSION_STARTED,
                    properties = mapOf("entry_source" to currentEntrySource),
                    sessionId = currentSessionId,
                )
            }
            nextEntrySource = null
        }
    }

    override fun onActivityPaused(activity: Activity) {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
        if (resumedCount == 0) {
            val now = nowMillis()
            lastBackgroundAtMillis = now
            val durationSeconds = ((now - sessionStartedAtMillis).coerceAtLeast(0L) / 1_000L).toInt()
            analytics.track(
                ProductAnalyticsNames.SESSION_ENDED,
                properties = mapOf(
                    "entry_source" to currentEntrySource,
                    "duration_seconds" to durationSeconds,
                ),
                sessionId = currentSessionId,
            )
            analytics.flush()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun nowMillis(): Long = clock.nowInstant().toEpochMilliseconds()

    public companion object {
        public const val ENTRY_ORGANIC: String = "organic"
        public const val ENTRY_NOTIFICATION: String = "notification"
        public const val ENTRY_DEEP_LINK: String = "deep_link"
        public const val ENTRY_UNKNOWN: String = "unknown"
        private const val SESSION_TIMEOUT_MILLIS: Long = 30L * 60L * 1_000L
    }
}
