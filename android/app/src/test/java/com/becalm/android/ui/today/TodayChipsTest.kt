package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.ChipState
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for [buildChips] — the pure function that projects [SourceStatusUi] into the
 * [ChipState] rendered by `SourceStatusStrip`.
 *
 * Covers TDY-003 visual-state mapping and CTO Q7 (voice excluded from the six-chip strip).
 */
class TodayChipsTest {

    /** Fixed instant — 2026-04-18T10:30:00Z, the exact wall-clock shown on a synced chip. */
    private val syncedAt: Instant = Instant.parse("2026-04-18T10:30:00Z")

    @Test
    fun `CONNECTED with lastSyncedAt emits Synced carrying the instant`() {
        val sourceStatus = mapOf(
            SourceType.GMAIL to SourceStatusUi(
                syncing = false,
                statusLabel = "CONNECTED",
                errorMessage = null,
                lastSyncedAt = syncedAt,
            ),
        )

        val chips = buildChips(sourceStatus)

        val gmail = chips.single { it.sourceType == SourceType.GMAIL }
        assertEquals(ChipState.Synced(syncedAt), gmail.state)
    }

    @Test
    fun `CONNECTED without lastSyncedAt falls back to Idle first-sync-pending`() {
        val sourceStatus = mapOf(
            SourceType.GMAIL to SourceStatusUi(
                syncing = false,
                statusLabel = "CONNECTED",
                errorMessage = null,
                lastSyncedAt = null,
            ),
        )

        val chips = buildChips(sourceStatus)

        val gmail = chips.single { it.sourceType == SourceType.GMAIL }
        assertEquals(ChipState.Idle, gmail.state)
    }

    @Test
    fun `NEVER_CONNECTED emits Idle`() {
        val sourceStatus = mapOf(
            SourceType.DAUM_IMAP to SourceStatusUi(
                syncing = false,
                statusLabel = "NEVER_CONNECTED",
                errorMessage = null,
                lastSyncedAt = null,
            ),
        )

        val chips = buildChips(sourceStatus)

        val daum = chips.single { it.sourceType == SourceType.DAUM_IMAP }
        assertEquals(ChipState.Idle, daum.state)
    }

    @Test
    fun `syncing flag emits Syncing regardless of CONNECTED label`() {
        val sourceStatus = mapOf(
            SourceType.NAVER_IMAP to SourceStatusUi(
                syncing = true,
                statusLabel = "SYNCING",
                errorMessage = null,
                lastSyncedAt = null,
            ),
        )

        val chips = buildChips(sourceStatus)

        val naver = chips.single { it.sourceType == SourceType.NAVER_IMAP }
        assertEquals(ChipState.Syncing, naver.state)
    }

    @Test
    fun `errorMessage emits Error carrying the message`() {
        val sourceStatus = mapOf(
            SourceType.OUTLOOK_MAIL to SourceStatusUi(
                syncing = false,
                statusLabel = "ERROR",
                errorMessage = "auth expired",
                lastSyncedAt = null,
            ),
        )

        val chips = buildChips(sourceStatus)

        val outlook = chips.single { it.sourceType == SourceType.OUTLOOK_MAIL }
        assertEquals(ChipState.Error("auth expired"), outlook.state)
    }

    @Test
    fun `voice source is excluded from the chip list per Q7`() {
        val sourceStatus = mapOf(
            SourceType.VOICE to SourceStatusUi(
                syncing = true,
                statusLabel = "SYNCING",
                errorMessage = null,
                lastSyncedAt = null,
            ),
            SourceType.GMAIL to SourceStatusUi(
                syncing = false,
                statusLabel = "CONNECTED",
                errorMessage = null,
                lastSyncedAt = syncedAt,
            ),
        )

        val chips = buildChips(sourceStatus)

        assertEquals("strip must have exactly 6 chips", 6, chips.size)
        assertFalse(
            "voice must never appear in the chip strip",
            chips.any { it.sourceType == SourceType.VOICE },
        )
        // Order must match CHIP_ORDER (gmail first).
        assertEquals(SourceType.GMAIL, chips.first().sourceType)
    }

    @Test
    fun `missing entry in the status map falls back to Idle`() {
        val chips = buildChips(emptyMap())

        assertEquals("strip must have exactly 6 chips", 6, chips.size)
        chips.forEach { chip ->
            assertEquals(
                "chip ${chip.sourceType} must default to Idle when no status available",
                ChipState.Idle,
                chip.state,
            )
        }
    }
}
