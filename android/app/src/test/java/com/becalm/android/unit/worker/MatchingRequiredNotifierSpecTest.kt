package com.becalm.android.unit.worker

import androidx.test.core.app.ApplicationProvider
import com.becalm.android.worker.MatchingRequiredNotifier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MatchingRequiredNotifierSpecTest {

    @Test
    fun `buildNotificationSpec projects stable matching-required notification`() {
        val spec = MatchingRequiredNotifier.buildNotificationSpec(
            context = ApplicationProvider.getApplicationContext(),
            unmatchedCount = 3,
        )

        assertEquals(MatchingRequiredNotifier.CHANNEL_ID, spec.channelId)
        assertEquals("Person matching needed", spec.title)
        assertEquals(3, spec.unmatchedCount)
        assertEquals(
            "3 synced interactions need person matches. Open People to assign them.",
            spec.body,
        )
    }
}
