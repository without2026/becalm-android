package com.becalm.android.unit.worker

import android.content.Context
import com.becalm.android.R
import com.becalm.android.worker.VoiceFailureNotifier
import org.junit.Assert.assertEquals
import org.junit.Test
import io.mockk.every
import io.mockk.mockk

class VoiceFailureNotifierSpecTest {

    private val context: Context = mockk(relaxed = true)
    private val notifier = VoiceFailureNotifier()

    @Test
    fun `voice failure notifier builds output truncated copy`() {
        every { context.getString(R.string.voice_failure_notification_title) } returns "Voice processing failed"
        every { context.getString(R.string.voice_failure_notification_body_output_truncated, "긴 회의 녹음") } returns
            "긴 회의 녹음 was too long to process. Try a shorter recording."

        val spec = notifier.buildNotificationSpec(
            context = context,
            rawEventId = "raw-1",
            eventTitle = "긴 회의 녹음",
            reasonCode = "output_truncated",
        )

        assertEquals(VoiceFailureNotifier.CHANNEL_ID, spec.channelId)
        assertEquals("Voice processing failed", spec.title)
        assertEquals("긴 회의 녹음 was too long to process. Try a shorter recording.", spec.body)
        assertEquals("raw-1", spec.rawEventId)
    }
}

