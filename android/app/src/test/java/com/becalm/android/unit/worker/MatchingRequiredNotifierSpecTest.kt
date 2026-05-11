package com.becalm.android.unit.worker

import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
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
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val spec = MatchingRequiredNotifier.buildNotificationSpec(
            context = context,
            unmatchedCount = 3,
        )

        assertEquals(MatchingRequiredNotifier.CHANNEL_ID, spec.channelId)
        assertEquals(context.getString(R.string.person_matching_required_notification_title), spec.title)
        assertEquals(3, spec.unmatchedCount)
        assertEquals(
            context.resources.getQuantityString(R.plurals.person_matching_required_notification_body, 3, 3),
            spec.body,
        )
    }
}
