package com.becalm.android.core.analytics

import android.content.Context
import com.amplitude.android.Amplitude
import com.amplitude.android.Configuration
import com.becalm.android.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

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
                    flushQueueSize = if (BuildConfig.DEBUG) 1 else 20
                    flushIntervalMillis = if (BuildConfig.DEBUG) 1_000 else 50_000
                    useBatch = true
                    optOut = false
                    if (BuildConfig.DEBUG) {
                        callback = { event, code, message ->
                            Timber.i(
                                "Amplitude product callback eventType=${event.eventType} " +
                                    "statusCode=$code message=$message",
                            )
                        }
                    }
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
