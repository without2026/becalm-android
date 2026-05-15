package com.becalm.android.core.analytics

import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAnalyticsValidationSpecTest {

    @Test
    fun `source and extraction funnel events are allowed without PII`() {
        val event = ProductAnalyticsEvent(
            eventId = "event-1",
            eventName = ProductAnalyticsEvents.EXTRACTION_COMPLETED,
            occurredAt = Instant.parse("2026-05-16T00:00:00Z"),
            properties = mapOf(
                "source_type" to "gmail",
                "input_modality" to "email",
                "result" to "success",
                "item_count" to 3,
                "participant_count" to 2,
            ),
        )

        assertTrue(ProductAnalyticsValidation.isValid(event))
    }

    @Test
    fun `source funnel events reject account identity properties`() {
        val event = ProductAnalyticsEvent(
            eventId = "event-1",
            eventName = ProductAnalyticsEvents.SOURCE_OAUTH_STATUS_CHECKED,
            occurredAt = Instant.parse("2026-05-16T00:00:00Z"),
            properties = mapOf(
                "source_type" to "gmail",
                "account_email" to "tester@example.com",
            ),
        )

        assertFalse(ProductAnalyticsValidation.isValid(event))
    }
}
