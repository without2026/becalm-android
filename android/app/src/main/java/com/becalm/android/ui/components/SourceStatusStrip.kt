package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── Public types ────────────────────────────────────────────────────────────

/**
 * Visual state for a single chip in [SourceStatusStrip].
 *
 * Four mutually-exclusive states mirroring the TDY-003 spec:
 * - [Idle]    — neutral gray (source has never connected or is quiescent).
 * - [Syncing] — animated spinner while the adapter is in flight.
 * - [Synced]  — green check + wall-clock HH:mm of the last successful sync.
 * - [Error]   — red dot + short description for TalkBack (not shown visually).
 */
public sealed interface ChipState {
    public data object Idle : ChipState
    public data object Syncing : ChipState
    public data class Synced(val at: Instant) : ChipState
    public data class Error(val message: String) : ChipState
}

/**
 * Stable data class for a single chip row in [SourceStatusStrip].
 *
 * `@Immutable` + stable parameter types so Compose skips recomposition when parent
 * state changes but the chip list did not (rubric D7 stable parameters).
 *
 * @param sourceType One of the six [SourceType] constants used in the strip (voice excluded).
 * @param state      Current visual state of this chip.
 */
@Immutable
public data class SourceStatusChip(
    val sourceType: String,
    val state: ChipState,
)

// ─── Composable ──────────────────────────────────────────────────────────────

private val ChipShape = RoundedCornerShape(100.dp)

/**
 * Horizontal strip of six per-source status chips (TDY-003).
 *
 * Renders exactly [sources].size chips in order. The strip is read-only per spec —
 * "칩 탭 인터랙션 없음". Catch-up recovery is driven by pull-to-refresh on the Today
 * screen (TDY-009), not by tapping chips. Error recovery is routed through the
 * settings screen.
 *
 * @param sources Chip list in display order. Caller is expected to pass exactly 6 entries.
 * @param modifier Optional [Modifier] applied to the outer [LazyRow].
 */
@Composable
public fun SourceStatusStrip(
    sources: List<SourceStatusChip>,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .semantics { contentDescription = STRIP_A11Y_LABEL },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(items = sources, key = { it.sourceType }) { chip ->
            SourceStatusChipView(chip = chip)
        }
    }
}

// ─── Internals ───────────────────────────────────────────────────────────────

@Composable
private fun SourceStatusChipView(
    chip: SourceStatusChip,
) {
    val becalmColors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .background(color = becalmColors.glassPanelFill, shape = ChipShape)
            .border(width = 1.dp, color = becalmColors.glassBorder, shape = ChipShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChipStateIndicator(state = chip.state)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = sourceDisplayName(chip.sourceType),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurface,
        )
        val timeLabel = (chip.state as? ChipState.Synced)?.let { formatTimeHHmm(it.at) }
        if (timeLabel != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChipStateIndicator(state: ChipState) {
    val becalmColors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme

    when (state) {
        ChipState.Idle -> {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = colorScheme.outline, shape = CircleShape),
            )
        }
        ChipState.Syncing -> {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
            )
        }
        is ChipState.Synced -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = becalmColors.sourceStatusOk,
                modifier = Modifier.size(12.dp),
            )
        }
        is ChipState.Error -> {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = becalmColors.sourceStatusError, shape = CircleShape),
            )
        }
    }
}

/**
 * Display name for the six ingestion sources shown on the Today strip.
 *
 * Voice is intentionally not listed — the strip is 6 items per TDY-003 / CTO Q7.
 * Any source type not in the six is routed to a defensive default so the UI stays
 * renderable even if the server adds a new wire type before the Android client
 * ships an update.
 */
private fun sourceDisplayName(sourceType: String): String = when (sourceType) {
    SourceType.GMAIL -> "Gmail"
    SourceType.OUTLOOK_MAIL -> "Outlook Mail"
    SourceType.NAVER_IMAP -> "네이버"
    SourceType.DAUM_IMAP -> "다음"
    SourceType.GOOGLE_CALENDAR -> "Google Cal"
    SourceType.OUTLOOK_CALENDAR -> "Outlook Cal"
    else -> sourceType
}

private fun formatTimeHHmm(at: Instant): String {
    val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

private const val STRIP_A11Y_LABEL = "데이터 소스 상태 표시"

// ─── Previews ────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewSourceStatusStripMixed() {
    BecalmTheme {
        Box(modifier = Modifier.background(Color.DarkGray).padding(8.dp)) {
            SourceStatusStrip(
                sources = listOf(
                    SourceStatusChip(SourceType.GMAIL, ChipState.Synced(Instant.fromEpochMilliseconds(1_713_430_200_000L))),
                    SourceStatusChip(SourceType.OUTLOOK_MAIL, ChipState.Error("auth expired")),
                    SourceStatusChip(SourceType.NAVER_IMAP, ChipState.Syncing),
                    SourceStatusChip(SourceType.DAUM_IMAP, ChipState.Idle),
                    SourceStatusChip(SourceType.GOOGLE_CALENDAR, ChipState.Idle),
                    SourceStatusChip(SourceType.OUTLOOK_CALENDAR, ChipState.Idle),
                ),
            )
        }
    }
}
