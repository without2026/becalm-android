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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// ─── Public types ────────────────────────────────────────────────────────────

/**
 * Stable data class for a single chip row in [SourceStatusStrip].
 *
 * `@Immutable` + stable parameter types so Compose skips recomposition when parent
 * state changes but the chip list did not (rubric D7 stable parameters).
 *
 * @param sourceType One of the seven user-facing [SourceType] constants used in the strip.
 * @param status     Current source sync status. The strip usually receives only
 *                   [SourceSyncStatus.Connected] and [SourceSyncStatus.Syncing];
 *                   failed/disconnected sources are shown by the attention banner.
 * @param lastSyncedAt Last successful sync time, rendered only when present.
 */
@Immutable
public data class SourceStatusChip(
    val sourceType: String,
    val status: SourceSyncStatus,
    val lastSyncedAt: Instant? = null,
)

// ─── Composable ──────────────────────────────────────────────────────────────

private val ChipShape = RoundedCornerShape(100.dp)

/**
 * Horizontal strip of connected or actively syncing source status chips (TDY-003).
 *
 * Renders exactly [sources].size chips in order. Disconnected and failed sources are
 * surfaced by the Today attention banner instead of occupying neutral-looking chip slots.
 * The strip is read-only per spec — "칩 탭 인터랙션 없음". Catch-up recovery is driven
 * by pull-to-refresh on the Today screen (TDY-009), not by tapping chips. Error recovery
 * is routed through the settings screen.
 *
 * @param sources Chip list in display order.
 * @param modifier Optional [Modifier] applied to the outer [LazyRow].
 */
@Composable
public fun SourceStatusStrip(
    sources: List<SourceStatusChip>,
    modifier: Modifier = Modifier,
) {
    val a11yLabel = stringResource(R.string.today_source_strip_a11y_label)
    LazyRow(
        modifier = modifier
            .testTag("source-status-strip")
            .semantics { contentDescription = a11yLabel },
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
            .testTag("source-chip-${chip.sourceType}")
            .background(color = becalmColors.glassPanelFill, shape = ChipShape)
            .border(width = 1.dp, color = becalmColors.glassBorder, shape = ChipShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceChipStatusIndicator(status = chip.status)
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = sourceDisplayName(chip.sourceType),
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.onSurface,
        )
        val timeLabel = chip.lastSyncedAt?.let(::formatTimeHHmm)
        if (timeLabel != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SourceChipStatusIndicator(status: SourceSyncStatus) {
    val toneColor = statusToneDotColor(sourceStatusToneFor(status))

    when (status) {
        SourceSyncStatus.Syncing -> {
            // Static halo dot uses the shared progress tone at reduced fill plus
            // a 1dp ring. Communicates "active + healthy" distinct from idle (solid
            // outline dot) and Synced (Check icon) without ambient motion. See
            // DESIGN.md Process-Hidden Rule — first-line surfaces never spin.
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .border(width = 1.dp, color = toneColor, shape = CircleShape)
                    .background(
                        color = toneColor.copy(alpha = 0.4f),
                        shape = CircleShape,
                    ),
            )
        }
        SourceSyncStatus.Connected -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = toneColor,
                modifier = Modifier.size(12.dp),
            )
        }
        SourceSyncStatus.Error,
        SourceSyncStatus.Stale,
        SourceSyncStatus.Disconnected,
        SourceSyncStatus.Unknown,
        -> {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = toneColor, shape = CircleShape),
            )
        }
    }
}

@Composable
private fun sourceDisplayName(sourceType: String): String =
    stringResource(sourcePresentationFor(sourceType).labelRes)

private fun formatTimeHHmm(at: Instant): String {
    val local = at.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    return "$hh:$mm"
}

// ─── Previews ────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewSourceStatusStripMixed() {
    BecalmTheme {
        Box(modifier = Modifier.background(Color.DarkGray).padding(8.dp)) {
            SourceStatusStrip(
                sources = listOf(
                    SourceStatusChip(SourceType.VOICE, SourceSyncStatus.Syncing),
                    SourceStatusChip(
                        sourceType = SourceType.GMAIL,
                        status = SourceSyncStatus.Connected,
                        lastSyncedAt = Instant.fromEpochMilliseconds(1_713_430_200_000L),
                    ),
                    SourceStatusChip(SourceType.OUTLOOK_MAIL, SourceSyncStatus.Error),
                    SourceStatusChip(SourceType.NAVER_IMAP, SourceSyncStatus.Syncing),
                    SourceStatusChip(SourceType.DAUM_IMAP, SourceSyncStatus.Connected),
                    SourceStatusChip(SourceType.GOOGLE_CALENDAR, SourceSyncStatus.Disconnected),
                    SourceStatusChip(SourceType.OUTLOOK_CALENDAR, SourceSyncStatus.Unknown),
                ),
            )
        }
    }
}
