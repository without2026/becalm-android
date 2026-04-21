package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focused unit tests for [deriveOverallState]. Complements VM-level tests in [TodayViewModelTest]. */
class TodayOverallSyncTest {

    private val t0: Instant = Instant.parse("2026-04-18T09:00:00Z")
    private val t1: Instant = Instant.parse("2026-04-18T10:00:00Z")

    private fun status(
        sourceType: String,
        connection: SourceConnectionStatus,
        lastSyncedAt: Instant? = null,
        errorMessage: String? = null,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = connection,
        lastSyncedAt = lastSyncedAt,
        errorMessage = errorMessage,
    )

    @Test
    fun `empty input yields Idle`() {
        assertEquals(OverallSyncState.Idle, deriveOverallState(emptyList()))
    }

    @Test
    fun `voice source is excluded from the aggregate`() {
        val sources = listOf(
            status(SourceType.VOICE, SourceConnectionStatus.ERROR, errorMessage = "ignore me"),
        )
        // With voice filtered out, no chip sources remain → Idle.
        assertEquals(OverallSyncState.Idle, deriveOverallState(sources))
    }

    @Test
    fun `call_recording source is excluded from the aggregate (wave-0 carve-out)`() {
        // CALL_RECORDING is in the schema-level ALL set but not in PRODUCT_SOURCES.
        // Passing one in directly must not flip the banner to Synced/PartialFailure.
        val sources = listOf(
            status(SourceType.CALL_RECORDING, SourceConnectionStatus.ERROR, errorMessage = "ignore me"),
            status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = t0),
        )
        val result = deriveOverallState(sources)
        // CALL_RECORDING filtered out → only GMAIL CONNECTED counts → Synced.
        assertTrue("expected Synced but was $result", result is OverallSyncState.Synced)
    }

    @Test
    fun `any ERROR beats SYNCING and CONNECTED`() {
        val sources = listOf(
            status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = t0),
            status(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.SYNCING),
            status(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.ERROR, errorMessage = "401"),
        )
        assertEquals(OverallSyncState.PartialFailure, deriveOverallState(sources))
    }

    @Test
    fun `SYNCING reports count and total over non-voice sources`() {
        val sources = listOf(
            status(SourceType.GMAIL, SourceConnectionStatus.SYNCING),
            status(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.SYNCING),
            status(SourceType.NAVER_IMAP, SourceConnectionStatus.CONNECTED, lastSyncedAt = t0),
            status(SourceType.DAUM_IMAP, SourceConnectionStatus.NEVER_CONNECTED),
        )
        val result = deriveOverallState(sources)
        assertTrue("expected Syncing but was $result", result is OverallSyncState.Syncing)
        val syncing = result as OverallSyncState.Syncing
        assertEquals(2, syncing.count)
        assertEquals(4, syncing.total)
    }

    @Test
    fun `all CONNECTED with timestamps emits Synced at earliest instant`() {
        val sources = listOf(
            status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = t1),
            status(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = t0),
        )
        val result = deriveOverallState(sources)
        assertTrue("expected Synced but was $result", result is OverallSyncState.Synced)
        assertEquals(t0, (result as OverallSyncState.Synced).at)
    }

    @Test
    fun `all CONNECTED but no timestamps falls back to Idle`() {
        val sources = listOf(
            status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = null),
            status(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = null),
        )
        assertEquals(OverallSyncState.Idle, deriveOverallState(sources))
    }

    @Test
    fun `mixed CONNECTED and NEVER_CONNECTED yields Idle, not Synced`() {
        val sources = listOf(
            status(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, lastSyncedAt = t0),
            status(SourceType.DAUM_IMAP, SourceConnectionStatus.NEVER_CONNECTED),
        )
        assertEquals(OverallSyncState.Idle, deriveOverallState(sources))
    }
}
