package com.becalm.android.unit.productanalytics

import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.productanalytics.CommitmentAnalyticsPayloads
import com.becalm.android.productanalytics.ProductAnalyticsNames
import com.becalm.android.productanalytics.ProductAnalyticsPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductAnalyticsPrivacySpecTest {
    @Test
    fun `event name allowlist accepts beta observability vocabulary only`() {
        assertTrue(ProductAnalyticsPrivacy.isSafeEventName(ProductAnalyticsNames.SESSION_STARTED))
        assertTrue(ProductAnalyticsPrivacy.isSafeEventName(ProductAnalyticsNames.COMMITMENT_ACTION_SELECTED))
        assertFalse(ProductAnalyticsPrivacy.isSafeEventName("raw_quote_viewed"))
    }

    @Test
    fun `PII-looking property keys and values are blocked before enqueue`() {
        assertNull(ProductAnalyticsPrivacy.sanitizeProperties(mapOf("search_query" to "Jane")))
        assertNull(ProductAnalyticsPrivacy.sanitizeProperties(mapOf("value" to "jane@example.com")))
        assertNull(ProductAnalyticsPrivacy.sanitizeProperties(mapOf("nested" to mapOf("phone" to "+821012341234"))))
    }

    @Test
    fun `available actions match commitment state gates`() {
        assertEquals(
            listOf("remind", "follow_up", "complete", "cancel", "edit"),
            CommitmentAnalyticsPayloads.availableActionsForState(CommitmentState.PENDING),
        )
        assertEquals(
            listOf("complete", "cancel", "edit"),
            CommitmentAnalyticsPayloads.availableActionsForState(CommitmentState.OVERDUE),
        )
        assertEquals(
            emptyList<String>(),
            CommitmentAnalyticsPayloads.availableActionsForState(CommitmentState.CANCELLED, editEnabled = false),
        )
    }
}
