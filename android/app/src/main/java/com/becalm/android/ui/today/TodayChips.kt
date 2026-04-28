package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.ChipState
import com.becalm.android.ui.components.SourceStatusChip

// ─── Chip construction (TDY-003) ─────────────────────────────────────────────

/**
 * Source-type evaluation order for the TDY-003 strip and attention banner.
 *
 * Seven user-facing entries in current product order:
 * `voice/gmail/outlook_mail/naver_email/daum_email/google_calendar/outlook_calendar`.
 */
internal val CHIP_ORDER: List<String> = listOf(
    SourceType.VOICE,
    SourceType.GMAIL,
    SourceType.OUTLOOK_MAIL,
    SourceType.NAVER_IMAP,
    SourceType.DAUM_IMAP,
    SourceType.GOOGLE_CALENDAR,
    SourceType.OUTLOOK_CALENDAR,
)

/**
 * Projects connected or actively syncing sources into chips in display order.
 *
 * Mapping priority:
 * 1. Missing entry / NEVER_CONNECTED / ERROR → hidden from the strip.
 * 2. `syncing` → [ChipState.Syncing] (animated spinner).
 * 3. `statusLabel == "CONNECTED"` AND `lastSyncedAt != null`
 *    → [ChipState.Synced(at)] (green check + HH:mm).
 * 4. Other CONNECTED rows → [ChipState.Idle] (connected but no completed sync timestamp yet).
 *
 * [SourceType.CALL_RECORDING] is never emitted — it remains schema-only for wave 0.
 */
internal fun buildChips(sourceStatus: Map<String, SourceStatusUi>): List<SourceStatusChip> =
    CHIP_ORDER.mapNotNull { sourceType ->
        val ui = sourceStatus[sourceType]
        val chipState: ChipState = when {
            ui == null -> return@mapNotNull null
            ui.errorMessage != null -> return@mapNotNull null
            ui.syncing -> ChipState.Syncing
            ui.statusLabel == "CONNECTED" && ui.lastSyncedAt != null ->
                ChipState.Synced(ui.lastSyncedAt)
            ui.statusLabel == "CONNECTED" -> ChipState.Idle
            else -> return@mapNotNull null
        }
        SourceStatusChip(sourceType = sourceType, state = chipState)
    }

internal data class SourceStatusAttention(
    val disconnectedCount: Int,
    val failedCount: Int,
) {
    val hasWarning: Boolean = disconnectedCount > 0 || failedCount > 0
}

internal fun buildSourceStatusAttention(sourceStatus: Map<String, SourceStatusUi>): SourceStatusAttention {
    var disconnectedCount = 0
    var failedCount = 0
    CHIP_ORDER.forEach { sourceType ->
        val ui = sourceStatus[sourceType] ?: return@forEach
        when {
            ui.errorMessage != null || ui.statusLabel == "ERROR" -> failedCount += 1
            ui.statusLabel == "NEVER_CONNECTED" -> disconnectedCount += 1
        }
    }
    return SourceStatusAttention(
        disconnectedCount = disconnectedCount,
        failedCount = failedCount,
    )
}
