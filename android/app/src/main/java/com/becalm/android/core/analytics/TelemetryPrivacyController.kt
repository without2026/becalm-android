package com.becalm.android.core.analytics

import com.becalm.android.BuildConfig
import com.becalm.android.core.di.ApplicationScope
import com.becalm.android.core.observability.FirebaseCrashlyticsPort
import com.becalm.android.data.local.datastore.UserPrefsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Singleton
public class TelemetryPrivacyController(
    private val userPrefsStore: UserPrefsStore,
    private val amplitude: AmplitudeProductAnalyticsClient,
    private val crashlytics: FirebaseCrashlyticsPort,
    private val applicationScope: CoroutineScope,
    private val telemetryEnabled: Boolean,
) {
    @Inject
    public constructor(
        userPrefsStore: UserPrefsStore,
        amplitude: AmplitudeProductAnalyticsClient,
        crashlytics: FirebaseCrashlyticsPort,
        @ApplicationScope applicationScope: CoroutineScope,
    ) : this(
        userPrefsStore = userPrefsStore,
        amplitude = amplitude,
        crashlytics = crashlytics,
        applicationScope = applicationScope,
        telemetryEnabled = BuildConfig.TELEMETRY_ENABLED,
    )

    public fun start() {
        applicationScope.launch {
            userPrefsStore.observeTelemetryEnabled()
                .distinctUntilChanged()
                .collect { enabled ->
                    val collectionEnabled = enabled && telemetryEnabled
                    amplitude.setOptOut(!collectionEnabled)
                    crashlytics.setCollectionEnabled(collectionEnabled)
                }
        }
    }
}
