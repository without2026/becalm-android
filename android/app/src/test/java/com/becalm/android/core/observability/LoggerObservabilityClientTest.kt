package com.becalm.android.core.observability

import com.becalm.android.core.util.RecordingLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM coverage for [LoggerObservabilityClient] — focuses on the PII scrub path
 * because that is the only non-trivial behaviour the class owns (everything else
 * delegates straight to [com.becalm.android.core.util.Logger]).
 */
public class LoggerObservabilityClientTest {

    private lateinit var logger: RecordingLogger
    private lateinit var client: LoggerObservabilityClient

    @Before
    public fun setUp() {
        logger = RecordingLogger()
        client = LoggerObservabilityClient(logger)
    }

    @Test
    public fun captureMessage_emitsEventAtInfoLevel() {
        client.captureMessage("onboarding_step_failed", mapOf("step" to "LINK_GMAIL"))

        val entry = logger.entries.single()
        assertTrue(entry.level == RecordingLogger.Level.I)
        assertTrue(
            "expected label in message, got '${entry.message}'",
            entry.message.contains("msg=onboarding_step_failed"),
        )
        assertTrue(
            "expected tag in message, got '${entry.message}'",
            entry.message.contains("step=LINK_GMAIL"),
        )
    }

    @Test
    public fun captureException_emitsAtErrorLevelAndAttachesThrowable() {
        val t = IllegalStateException("boom")
        client.captureException(t, mapOf("step" to "LINK_OUTLOOK_MAIL"))

        val entry = logger.entries.single()
        assertTrue(entry.level == RecordingLogger.Level.E)
        assertTrue("throwable must be forwarded to the logger", entry.throwable === t)
    }

    @Test
    public fun captureMessage_scrubsEmailPatternsFromTagValues() {
        client.captureMessage("some_event", mapOf("contact" to "alice@example.com"))

        val message = logger.entries.single().message
        assertFalse("email must not appear in rendered message", message.contains("alice@"))
        assertTrue("scrub placeholder must be present", message.contains("[redacted]"))
    }

    @Test
    public fun captureMessage_scrubsJwtPatternsFromTagValues() {
        val jwt = "eyABCDEFGH.IJKLMNOPQR.STUVWXYZ01"
        client.captureMessage("some_event", mapOf("token" to jwt))

        val message = logger.entries.single().message
        assertFalse("JWT must not appear in rendered message", message.contains(jwt))
        assertTrue("scrub placeholder must be present", message.contains("[redacted]"))
    }

    @Test
    public fun captureMessage_scrubsBearerHeaderFromTagValues() {
        client.captureMessage(
            "some_event",
            mapOf("header" to "Bearer abcdef0123456789ABCDEF0"),
        )

        val message = logger.entries.single().message
        assertFalse(
            "bearer-token substring must not appear in rendered message",
            message.contains("abcdef0123456789ABCDEF0"),
        )
    }

    @Test
    public fun setUserScope_annotatesSubsequentEvents() {
        client.setUserScope("user-uuid-abc")
        client.captureMessage("onboarding_step_failed")

        val message = logger.entries.single().message
        assertTrue(
            "scope userId must surface as `user=` token: got '$message'",
            message.contains("user=user-uuid-abc"),
        )
    }

    @Test
    public fun setUserScope_nullClearsPriorScope() {
        client.setUserScope("user-uuid-abc")
        client.setUserScope(null)
        client.captureMessage("some_event")

        val message = logger.entries.single().message
        assertFalse("cleared scope must not leak into subsequent events", message.contains("user="))
    }

    @Test
    public fun addBreadcrumb_emitsAtDebugLevelWithCategory() {
        client.addBreadcrumb(category = "auth", message = "google_sign_in_started")

        val entry = logger.entries.single()
        assertTrue(entry.level == RecordingLogger.Level.D)
        assertTrue(
            "breadcrumb category must appear in kind= token: got '${entry.message}'",
            entry.message.contains("kind=breadcrumb/auth"),
        )
    }
}
