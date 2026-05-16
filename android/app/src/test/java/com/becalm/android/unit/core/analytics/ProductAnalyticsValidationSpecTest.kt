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
    fun `beta operations funnel events are allowed without PII`() {
        val events = listOf(
            ProductAnalyticsEvents.SOURCE_OAUTH_CALLBACK_RECEIVED,
            ProductAnalyticsEvents.EXTRACTION_FILTERED,
            ProductAnalyticsEvents.COMMITMENT_CORRECTION_SUBMITTED,
            ProductAnalyticsEvents.PERSON_MERGE_COMPLETED,
            ProductAnalyticsEvents.PERSON_SPLIT_COMPLETED,
            ProductAnalyticsEvents.CONSENT_WITHDRAWN,
            ProductAnalyticsEvents.PROCESSING_PAUSED,
        ).mapIndexed { index, eventName ->
            ProductAnalyticsEvent(
                eventId = "event-$index",
                eventName = eventName,
                occurredAt = Instant.parse("2026-05-16T00:00:00Z"),
                properties = mapOf(
                    "source_type" to "gmail",
                    "result" to "success",
                    "reason_code" to "user_requested",
                ),
            )
        }

        assertTrue(events.all(ProductAnalyticsValidation::isValid))
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
