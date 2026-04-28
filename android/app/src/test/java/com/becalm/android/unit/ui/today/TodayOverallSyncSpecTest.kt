package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.SourceConnectionStatus
import com.becalm.android.data.repository.SourceStatus
import com.becalm.android.ui.components.ChipState
import kotlinx.datetime.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayOverallSyncSpecTest {

    @Test
    fun `TDY-003 chip order locks all seven contract sources including voice and split IMAP providers`() {
        assertEquals(
            listOf(
                SourceType.VOICE,
                SourceType.GMAIL,
                SourceType.OUTLOOK_MAIL,
                SourceType.NAVER_IMAP,
                SourceType.DAUM_IMAP,
                SourceType.GOOGLE_CALENDAR,
                SourceType.OUTLOOK_CALENDAR,
            ),
            CHIP_ORDER,
        )
    }

    @Test
    fun `deriveOverallState returns PartialFailure when any product source is in error`() {
        val state = deriveOverallState(
            listOf(
                sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, 3_000),
                sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.ERROR, null),
                sourceStatus(SourceType.VOICE, SourceConnectionStatus.SYNCING, null),
            ),
        )

        assertEquals(OverallSyncState.PartialFailure, state)
    }

    @Test
    fun `deriveOverallState returns Syncing count over product sources only`() {
        val state = deriveOverallState(
            listOf(
                sourceStatus(SourceType.VOICE, SourceConnectionStatus.SYNCING, null),
                sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, 4_000),
                sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, 4_000),
                sourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.CONNECTED, 4_000),
                sourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED, 4_000),
                sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED, 4_000),
                sourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.CONNECTED, 4_000),
            ),
        )

        assertEquals(OverallSyncState.Syncing(count = 1, total = 7), state)
    }

    @Test
    fun `deriveOverallState excludes never connected sources from syncing total`() {
        val state = deriveOverallState(
            listOf(
                sourceStatus(SourceType.VOICE, SourceConnectionStatus.SYNCING, null),
                sourceStatus(SourceType.GMAIL, SourceConnectionStatus.NEVER_CONNECTED, null),
                sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, 4_000),
            ),
        )

        assertEquals(OverallSyncState.Syncing(count = 1, total = 2), state)
    }

    @Test
    fun `deriveOverallState returns Synced at earliest lastSyncedAt when all product sources connected`() {
        val state = deriveOverallState(
            listOf(
                sourceStatus(SourceType.VOICE, SourceConnectionStatus.CONNECTED, 8_000),
                sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, 5_000),
                sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, 3_000),
                sourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.CONNECTED, 9_000),
                sourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED, 6_000),
                sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED, 7_000),
                sourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.CONNECTED, 10_000),
            ),
        )

        assertEquals(OverallSyncState.Synced(Instant.fromEpochMilliseconds(3_000)), state)
    }

    @Test
    fun `deriveOverallState falls back to Idle when connected sources have no timestamp`() {
        val state = deriveOverallState(
            listOf(
                sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, null),
                sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, null),
            ),
        )

        assertEquals(OverallSyncState.Idle, state)
    }

    @Test
    fun `TDY-008 deriveOverallState ignores call recording as a non user facing source`() {
        val state = deriveOverallState(
            listOf(
                sourceStatus(SourceType.VOICE, SourceConnectionStatus.CONNECTED, 8_000),
                sourceStatus(SourceType.CALL_RECORDING, SourceConnectionStatus.SYNCING, null),
                sourceStatus(SourceType.GMAIL, SourceConnectionStatus.CONNECTED, 5_000),
                sourceStatus(SourceType.OUTLOOK_MAIL, SourceConnectionStatus.CONNECTED, 3_000),
                sourceStatus(SourceType.NAVER_IMAP, SourceConnectionStatus.CONNECTED, 9_000),
                sourceStatus(SourceType.DAUM_IMAP, SourceConnectionStatus.CONNECTED, 6_000),
                sourceStatus(SourceType.GOOGLE_CALENDAR, SourceConnectionStatus.CONNECTED, 7_000),
                sourceStatus(SourceType.OUTLOOK_CALENDAR, SourceConnectionStatus.CONNECTED, 10_000),
            ),
        )

        assertEquals(OverallSyncState.Synced(Instant.fromEpochMilliseconds(3_000)), state)
    }

    @Test
    fun `buildChips emits connected and syncing chips and hides failed or disconnected sources`() {
        val chips = buildChips(
            mapOf(
                SourceType.VOICE to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 8_000),
                SourceType.GMAIL to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = "401", lastSyncedAt = 9_000),
                SourceType.OUTLOOK_MAIL to sourceUi(syncing = true, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = null),
                SourceType.NAVER_IMAP to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 3_000),
                SourceType.DAUM_IMAP to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 4_000),
                SourceType.GOOGLE_CALENDAR to sourceUi(syncing = false, statusLabel = "NEVER_CONNECTED", errorMessage = null, lastSyncedAt = null),
                SourceType.OUTLOOK_CALENDAR to sourceUi(syncing = false, statusLabel = "NEVER_CONNECTED", errorMessage = null, lastSyncedAt = null),
            ),
        )

        assertEquals(4, chips.size)
        assertTrue(chips.any { it.sourceType == SourceType.VOICE })
        assertTrue(chips.any { it.sourceType == SourceType.NAVER_IMAP })
        assertTrue(chips.any { it.sourceType == SourceType.DAUM_IMAP })
        assertFalse(chips.any { it.sourceType == SourceType.GMAIL })
        assertFalse(chips.any { it.sourceType == SourceType.GOOGLE_CALENDAR })
        assertFalse(chips.any { it.sourceType == SourceType.OUTLOOK_CALENDAR })
        assertEquals(ChipState.Syncing, chips.single { it.sourceType == SourceType.OUTLOOK_MAIL }.state)
        assertEquals(
            ChipState.Synced(Instant.fromEpochMilliseconds(8_000)),
            chips.single { it.sourceType == SourceType.VOICE }.state,
        )
    }

    @Test
    fun `TDY-003 buildChips hides missing rows instead of showing fake idle chips`() {
        val chips = buildChips(
            mapOf(
                SourceType.GMAIL to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 9_000),
            ),
        )

        assertEquals(listOf(SourceType.GMAIL), chips.map { it.sourceType })
        assertEquals(1, chips.size)
        assertEquals(ChipState.Synced(Instant.fromEpochMilliseconds(9_000)), chips.single { it.sourceType == SourceType.GMAIL }.state)
        assertFalse(chips.any { it.sourceType == SourceType.CALL_RECORDING })
    }

    @Test
    fun `buildChips does not expose call recording as a separate user-facing chip`() {
        val chips = buildChips(
            mapOf(
                SourceType.VOICE to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 8_000),
                SourceType.CALL_RECORDING to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 9_000),
                SourceType.GMAIL to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 3_000),
                SourceType.OUTLOOK_MAIL to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 4_000),
                SourceType.NAVER_IMAP to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 5_000),
                SourceType.DAUM_IMAP to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 6_000),
                SourceType.GOOGLE_CALENDAR to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 7_000),
                SourceType.OUTLOOK_CALENDAR to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 10_000),
            ),
        )

        assertEquals(7, chips.size)
        assertTrue(chips.none { it.sourceType == SourceType.CALL_RECORDING })
        assertTrue(chips.any { it.sourceType == SourceType.VOICE })
    }

    @Test
    fun `buildSourceStatusAttention reports disconnected and failed sources for warning banner`() {
        val attention = buildSourceStatusAttention(
            mapOf(
                SourceType.VOICE to sourceUi(syncing = false, statusLabel = "NEVER_CONNECTED", errorMessage = null, lastSyncedAt = null),
                SourceType.GMAIL to sourceUi(syncing = false, statusLabel = "ERROR", errorMessage = "expired", lastSyncedAt = null),
                SourceType.OUTLOOK_MAIL to sourceUi(syncing = false, statusLabel = "CONNECTED", errorMessage = null, lastSyncedAt = 1_000),
            ),
        )

        assertEquals(SourceStatusAttention(disconnectedCount = 1, failedCount = 1), attention)
        assertTrue(attention.hasWarning)
    }

    private fun sourceStatus(
        sourceType: String,
        status: SourceConnectionStatus,
        lastSyncedAtMs: Long?,
    ): SourceStatus = SourceStatus(
        sourceType = sourceType,
        status = status,
        lastSyncedAt = lastSyncedAtMs?.let(Instant::fromEpochMilliseconds),
        errorMessage = null,
    )

    private fun sourceUi(
        syncing: Boolean,
        statusLabel: String,
        errorMessage: String?,
        lastSyncedAt: Long?,
    ): SourceStatusUi = SourceStatusUi(
        syncing = syncing,
        statusLabel = statusLabel,
        errorMessage = errorMessage,
        lastSyncedAt = lastSyncedAt?.let(Instant::fromEpochMilliseconds),
    )
}
