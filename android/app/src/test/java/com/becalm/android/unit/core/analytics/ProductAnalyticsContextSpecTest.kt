package com.becalm.android.unit.core.analytics

import com.becalm.android.core.analytics.InMemoryProductAnalyticsAttributionStore
import com.becalm.android.core.analytics.ProductAnalyticsContext
import com.becalm.android.core.analytics.ProductAnalyticsEvent
import com.becalm.android.core.analytics.ProductAnalyticsEvents
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProductAnalyticsContextSpecTest {

    @Test
    fun `notification action attribution survives a new context instance and is consumed once`() {
        val store = InMemoryProductAnalyticsAttributionStore()
        ProductAnalyticsContext(store).markNotificationOpened(
            notificationInstanceId = "notif-1",
            commitmentId = "commitment-1",
            openedAt = Instant.parse("2026-05-18T00:00:00Z"),
        )
        val restored = ProductAnalyticsContext(store)

        val enriched = restored.enrich(
            actionEvent(
                commitmentId = "commitment-1",
                occurredAt = Instant.parse("2026-05-18T00:00:12Z"),
            ),
        )

        assertEquals("notif-1", enriched.properties["notification_instance_id"])
        assertEquals("commitment-1", enriched.properties["notification_commitment_id"])
        assertEquals(12L, enriched.properties["seconds_since_notification_open"])

        val next = restored.enrich(
            actionEvent(
                commitmentId = "commitment-1",
                occurredAt = Instant.parse("2026-05-18T00:00:20Z"),
            ),
        )
        assertFalse(next.properties.containsKey("notification_instance_id"))
    }

    @Test
    fun `expired notification attribution is ignored and cleared`() {
        val store = InMemoryProductAnalyticsAttributionStore()
        ProductAnalyticsContext(store).markNotificationOpened(
            notificationInstanceId = "notif-old",
            commitmentId = "commitment-1",
            openedAt = Instant.parse("2026-05-18T00:00:00Z"),
        )
        val restored = ProductAnalyticsContext(store)

        val enriched = restored.enrich(
            actionEvent(
                commitmentId = "commitment-1",
                occurredAt = Instant.parse("2026-05-19T00:00:01Z"),
            ),
        )

        assertFalse(enriched.properties.containsKey("notification_instance_id"))
        assertEquals(null, store.readNotificationOpen())
    }

    private fun actionEvent(commitmentId: String, occurredAt: Instant): ProductAnalyticsEvent =
        ProductAnalyticsEvent(
            eventId = "event-$commitmentId-${occurredAt.toEpochMilliseconds()}",
            eventName = ProductAnalyticsEvents.COMMITMENT_ACTION_SELECTED,
            occurredAt = occurredAt,
            properties = mapOf(
                "commitment_id" to commitmentId,
                "action" to "complete",
            ),
        )
}
