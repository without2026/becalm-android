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
public class TelemetryPrivacyController @Inject constructor(
    private val userPrefsStore: UserPrefsStore,
    private val amplitude: AmplitudeProductAnalyticsClient,
    private val crashlytics: FirebaseCrashlyticsPort,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    public fun start() {
        applicationScope.launch {
            userPrefsStore.observeTelemetryEnabled()
                .distinctUntilChanged()
                .collect { enabled ->
                    val collectionEnabled = enabled && BuildConfig.TELEMETRY_ENABLED
                    amplitude.setOptOut(!collectionEnabled)
                    crashlytics.setCollectionEnabled(collectionEnabled)
                }
        }
    }
}
