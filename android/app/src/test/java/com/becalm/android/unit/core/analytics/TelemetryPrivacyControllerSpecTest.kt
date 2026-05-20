package com.becalm.android.unit.core.analytics

import com.becalm.android.core.analytics.AmplitudeProductAnalyticsClient
import com.becalm.android.core.analytics.TelemetryPrivacyController
import com.becalm.android.core.observability.FirebaseCrashlyticsPort
import com.becalm.android.data.local.datastore.UserPrefsStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TelemetryPrivacyControllerSpecTest {

    private val userPrefsStore: UserPrefsStore = mockk()
    private val amplitude: AmplitudeProductAnalyticsClient = mockk(relaxed = true)
    private val crashlytics: FirebaseCrashlyticsPort = mockk(relaxed = true)

    @Test
    fun `telemetry preference controls amplitude opt-out and crash collection`() = runTest {
        val telemetryEnabled = MutableStateFlow(true)
        every { userPrefsStore.observeTelemetryEnabled() } returns telemetryEnabled
        val subject = TelemetryPrivacyController(
            userPrefsStore = userPrefsStore,
            amplitude = amplitude,
            crashlytics = crashlytics,
            applicationScope = backgroundScope,
            telemetryEnabled = true,
        )

        subject.start()
        runCurrent()

        verify(exactly = 1) { amplitude.setOptOut(false) }
        verify(exactly = 1) { crashlytics.setCollectionEnabled(true) }

        telemetryEnabled.value = false
        runCurrent()

        verify(exactly = 1) { amplitude.setOptOut(true) }
        verify(exactly = 1) { crashlytics.setCollectionEnabled(false) }
    }
}
