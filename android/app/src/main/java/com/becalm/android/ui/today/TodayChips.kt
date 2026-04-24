package com.becalm.android.ui.today

import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.ChipState
import com.becalm.android.ui.components.SourceStatusChip

// ─── Chip construction (TDY-003) ─────────────────────────────────────────────

/**
 * Source-type display order for the TDY-003 strip.
 *
 * Seven user-facing entries in current spec order:
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
 * Projects the per-source map from [TodayUiState] into seven [SourceStatusChip] rows
 * in display order (pure function; covered by `TodayChipsTest`).
 *
 * Mapping priority per TDY-003:
 * 1. Missing entry (pre-first-emission or server-ignored) → [ChipState.Idle].
 * 2. `errorMessage != null` → [ChipState.Error(message)] (red dot).
 * 3. `syncing` → [ChipState.Syncing] (animated spinner).
 * 4. `statusLabel == "CONNECTED"` AND `lastSyncedAt != null`
 *    → [ChipState.Synced(at)] (green check + HH:mm).
 * 5. All other cases (including CONNECTED without a timestamp, NEVER_CONNECTED)
 *    → [ChipState.Idle] (neutral gray).
 *
 * [SourceType.CALL_RECORDING] is never emitted — it remains schema-only for wave 0.
 */
internal fun buildChips(sourceStatus: Map<String, SourceStatusUi>): List<SourceStatusChip> =
    CHIP_ORDER.map { sourceType ->
        val ui = sourceStatus[sourceType]
        val chipState: ChipState = when {
            ui == null -> ChipState.Idle
            ui.errorMessage != null -> ChipState.Error(ui.errorMessage)
            ui.syncing -> ChipState.Syncing
            ui.statusLabel == "CONNECTED" && ui.lastSyncedAt != null ->
                ChipState.Synced(ui.lastSyncedAt)
            else -> ChipState.Idle
        }
        SourceStatusChip(sourceType = sourceType, state = chipState)
    }
