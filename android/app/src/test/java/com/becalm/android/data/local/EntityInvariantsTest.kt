package com.becalm.android.data.local

import com.becalm.android.data.local.entities.Commitment
import com.becalm.android.data.local.entities.RawIngestionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// spec: data-model invariants — entity constants and defaults

class EntityInvariantsTest {

    // spec: data-model — RawIngestionEvent.SyncStatus constants
    @Test
    fun `RawIngestionEvent SyncStatus constants match spec`() {
        assertEquals("pending", RawIngestionEvent.SyncStatus.PENDING)
        assertEquals("synced", RawIngestionEvent.SyncStatus.SYNCED)
        assertEquals("failed", RawIngestionEvent.SyncStatus.FAILED)
        assertEquals("quarantined", RawIngestionEvent.SyncStatus.QUARANTINED)
    }

    // spec: data-model — SourceType constants cover all 7 sources
    @Test
    fun `RawIngestionEvent SourceType constants cover all 7 sources`() {
        val types = setOf(
            RawIngestionEvent.SourceType.VOICE,
            RawIngestionEvent.SourceType.GMAIL,
            RawIngestionEvent.SourceType.OUTLOOK_MAIL,
            RawIngestionEvent.SourceType.NAVER_IMAP,
            RawIngestionEvent.SourceType.DAUM_IMAP,
            RawIngestionEvent.SourceType.GOOGLE_CALENDAR,
            RawIngestionEvent.SourceType.OUTLOOK_CALENDAR
        )
        assertEquals(7, types.size)
    }

    // spec: data-model — Commitment defaults pending action_state
    @Test
    fun `Commitment defaults to pending action_state`() {
        val cmt = Commitment(
            direction = "give", title = "Test", quote = "verbatim",
            sourceEventOccurredAt = 0L, sourceType = "voice"
        )
        assertEquals("pending", cmt.actionState)
        assertEquals("pending", cmt.syncStatus)
    }

    // spec: CMT action_state constants
    @Test
    fun `Commitment ActionState constants match spec`() {
        assertEquals("pending", Commitment.ActionState.PENDING)
        assertEquals("reminded", Commitment.ActionState.REMINDED)
        assertEquals("followed_up", Commitment.ActionState.FOLLOWED_UP)
        assertEquals("completed", Commitment.ActionState.COMPLETED)
    }

    // spec: CMT direction constants
    @Test
    fun `Commitment Direction constants are give and take`() {
        assertEquals("give", Commitment.Direction.GIVE)
        assertEquals("take", Commitment.Direction.TAKE)
    }

    // spec: data-model — RawIngestionEvent defaults to pending sync_status, retry_count=0
    @Test
    fun `RawIngestionEvent defaults to pending sync_status and zero retry_count`() {
        val event = RawIngestionEvent(
            sourceType = "voice",
            timestamp = System.currentTimeMillis()
        )
        assertEquals("pending", event.syncStatus)
        assertEquals(0, event.retryCount)
        assertEquals(0, event.commitmentsExtractedCount)
        assertNotNull(event.clientEventId)
    }
}
