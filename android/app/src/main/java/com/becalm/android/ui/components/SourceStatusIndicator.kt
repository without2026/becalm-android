/**
 * SP-50: Source sync-health pill indicator for BeCalm Android.
 *
 * Renders a rounded pill with a colored status dot and an optional text label,
 * suitable for both list rows (compact mode) and detail screens (full mode).
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors

// ─── SourceSyncStatus enum ────────────────────────────────────────────────────

/**
 * Represents the health of a data source's last synchronization.
 *
 * - [Ok]: source synced successfully and is fresh.
 * - [Stale]: source has not synced recently; attention may be needed.
 * - [Error]: sync failed; user action required.
 * - [Unknown]: status cannot be determined (e.g. first-run, no connectivity check yet).
 */
public enum class SourceSyncStatus {
    Ok,
    Stale,
    Error,
    Unknown,
}

// ─── SourceStatusIndicator ────────────────────────────────────────────────────

private val PillShape = RoundedCornerShape(100.dp)

/**
 * Renders a source's synchronization health as a glass pill containing a colored
 * dot and a human-readable label.
 *
 * In [compact] mode only the dot is shown; the [label] is surfaced via
 * `contentDescription` so TalkBack still announces the status.
 *
 * @param status   Sync health level driving the dot color.
 * @param label    Caller-supplied human-readable status text (e.g. "Synced 2m ago").
 *                 Must not include account identifiers, email addresses, or any PII —
 *                 use sync-status phrasing only (e.g. "Synced 2m ago", "Stale — tap to
 *                 retry"). The label is announced verbatim by TalkBack.
 *                 Always used for accessibility, shown visually when [compact] is false.
 * @param modifier Optional [Modifier] applied to the pill container.
 * @param compact  When `true`, the text label is hidden and only the dot is rendered.
 *                 Useful for list-row usage where horizontal space is constrained.
 */
@Composable
public fun SourceStatusIndicator(
    status: SourceSyncStatus,
    label: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val becalmColors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme

    val dotColor = when (status) {
        SourceSyncStatus.Ok -> becalmColors.sourceStatusOk
        SourceSyncStatus.Stale -> becalmColors.sourceStatusStale
        SourceSyncStatus.Error -> becalmColors.sourceStatusError
        SourceSyncStatus.Unknown -> colorScheme.outline
    }

    Row(
        modifier = modifier
            .semantics { contentDescription = label }
            .background(color = becalmColors.glassPanelFill, shape = PillShape)
            .border(width = 1.dp, color = becalmColors.glassBorder, shape = PillShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = dotColor, shape = CircleShape),
        )

        // Label — hidden in compact mode
        if (!compact) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurface,
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewSourceStatusOkFull() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            SourceStatusIndicator(status = SourceSyncStatus.Ok, label = "Synced 2m ago")
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewSourceStatusStaleFull() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            SourceStatusIndicator(status = SourceSyncStatus.Stale, label = "Stale — 2h ago")
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewSourceStatusErrorFull() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            SourceStatusIndicator(status = SourceSyncStatus.Error, label = "Failed — tap to retry")
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewSourceStatusUnknownFull() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            SourceStatusIndicator(status = SourceSyncStatus.Unknown, label = "Unknown")
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewSourceStatusCompact() {
    BecalmTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceStatusIndicator(
                    status = SourceSyncStatus.Ok,
                    label = "Synced",
                    compact = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                SourceStatusIndicator(
                    status = SourceSyncStatus.Stale,
                    label = "Stale",
                    compact = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                SourceStatusIndicator(
                    status = SourceSyncStatus.Error,
                    label = "Error",
                    compact = true,
                )
            }
        }
    }
}
