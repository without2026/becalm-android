package com.becalm.android.unit.core.observability

import com.becalm.android.core.observability.LoggerObservabilityClient
import com.becalm.android.core.util.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggerObservabilityClientSpecTest {

    @Test
    fun `ONB-007 logger implementation records event without Firebase runtime config and scrubs PII`() {
        val logger = RecordingLogger()
        val client = LoggerObservabilityClient(logger)

        client.setUserScope("user-123")
        client.captureMessage(
            message = "onboarding_step_failed",
            tags = mapOf(
                "step" to "LINK_GMAIL",
                "email" to "person@example.com",
                "token" to "Bearer abcdefghijklmnopqrstuvwxyz",
            ),
        )

        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(RecordingLogger.Level.I, entry.level)
        assertEquals("Observability", entry.tag)
        assertTrue(entry.message.contains("kind=event"))
        assertTrue(entry.message.contains("msg=onboarding_step_failed"))
        assertTrue(entry.message.contains("user=user-123"))
        assertTrue(entry.message.contains("step=LINK_GMAIL"))
        assertTrue(entry.message.contains("email=[redacted]"))
        assertTrue(entry.message.contains("token=[redacted]"))
        assertFalse(entry.message.contains("person@example.com"))
        assertFalse(entry.message.contains("abcdefghijklmnopqrstuvwxyz"))
    }
}
