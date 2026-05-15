package com.becalm.android.core.analytics

import android.content.Context
import com.amplitude.android.Amplitude
import com.amplitude.android.Configuration
import com.becalm.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class AmplitudeProductAnalyticsClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val amplitude: Amplitude? by lazy {
        val apiKey = BuildConfig.AMPLITUDE_API_KEY
        if (!BuildConfig.TELEMETRY_ENABLED || apiKey.isBlank()) {
            null
        } else {
            Amplitude(
                Configuration(apiKey, context).apply {
                    flushQueueSize = 20
                    flushIntervalMillis = 50_000
                    useBatch = true
                    optOut = false
                },
            )
        }
    }

    public fun track(event: ProductAnalyticsEvent) {
        amplitude?.track(event.eventName, ProductAnalyticsValidation.sanitizedProperties(event.properties))
    }

    public fun setUserScope(userId: String?) {
        amplitude?.setUserId(userId)
    }

    public fun resetUserScope() {
        amplitude?.reset()
    }

    public fun setOptOut(optOut: Boolean) {
        amplitude?.optOut = optOut
    }
}
