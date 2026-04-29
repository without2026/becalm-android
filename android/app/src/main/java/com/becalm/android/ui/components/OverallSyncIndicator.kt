package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.core.util.Clock
import com.becalm.android.core.util.SystemClock
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Text banner summarising overall sync state (TDY-008).
 *
 * Four states:
 * - [OverallSyncState.Idle]            → empty / quiet copy.
 * - [OverallSyncState.Syncing]         → "동기화 중 N/6".
 * - [OverallSyncState.Synced]          → "동기화됨 HH:mm" (today) or "동기화됨 MM/dd HH:mm" (older).
 * - [OverallSyncState.PartialFailure]  → amber-toned "일부 소스 실패 — 설정에서 확인".
 *
 * `derivedStateOf` is intentionally NOT used here — the incoming [state] changes at most a few
 * times per minute so caching adds no benefit (rubric D5: avoid `derivedStateOf` unless it
 * throttles rapid state changes).
 *
 * @param state Current aggregate state from `TodayViewModel.state.overall`.
 * @param modifier Optional [Modifier] applied to the outer container.
 * @param clock Injectable [Clock] used to format [OverallSyncState.Synced]. Defaults to
 *              [SystemClock] so non-Hilt call sites (previews, tests) can pass a `FakeClock`.
 */
@Composable
public fun OverallSyncIndicator(
    state: OverallSyncState,
    modifier: Modifier = Modifier,
    clock: Clock = SystemClock,
) {
    val becalmColors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme

    val isPartialFailure = state is OverallSyncState.PartialFailure
    val textColor: Color = if (isPartialFailure) becalmColors.sourceStatusStale else colorScheme.onSurfaceVariant

    val label: String = when (state) {
        OverallSyncState.Idle -> ""
        is OverallSyncState.Syncing -> stringResource(R.string.today_syncing_fmt, state.count, state.total)
        is OverallSyncState.Synced -> formatSyncedLabel(state.at, clock)
        OverallSyncState.PartialFailure -> stringResource(R.string.today_partial_failure)
    }

    if (label.isEmpty()) {
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (isPartialFailure) {
                    becalmColors.sourceStatusStale.copy(alpha = 0.16f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(8.dp),
            )
            .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}

/**
 * Formats a [Synced.at] instant into the banner label.
 *
 * Today  → "동기화됨 HH:mm"
 * Older  → "동기화됨 MM/dd HH:mm"
 *
 * Uses the injected [clock] to decide "today" via `clock.today(zone)`, so tests can control
 * the boundary without patching the system clock.
 */
@Composable
private fun formatSyncedLabel(at: Instant, clock: Clock): String {
    val tz = TimeZone.currentSystemDefault()
    val atLocal = at.toLocalDateTime(tz)
    val today = clock.today(tz)

    val hh = atLocal.hour.toString().padStart(2, '0')
    val mm = atLocal.minute.toString().padStart(2, '0')

    val time = "$hh:$mm"
    return if (atLocal.date == today) {
        stringResource(R.string.today_synced_time_fmt, time)
    } else {
        val mo = atLocal.monthNumber.toString().padStart(2, '0')
        val d = atLocal.dayOfMonth.toString().padStart(2, '0')
        stringResource(R.string.today_synced_date_time_fmt, "$mo/$d", time)
    }
}

// ─── Previews ────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewOverallSyncIndicatorSyncing() {
    BecalmTheme {
        OverallSyncIndicator(state = OverallSyncState.Syncing(count = 2, total = 6))
    }
}

@PreviewLightDark
@Composable
private fun PreviewOverallSyncIndicatorSynced() {
    BecalmTheme {
        OverallSyncIndicator(state = OverallSyncState.Synced(Instant.fromEpochMilliseconds(1_713_430_200_000L)))
    }
}

@PreviewLightDark
@Composable
private fun PreviewOverallSyncIndicatorPartialFailure() {
    BecalmTheme {
        OverallSyncIndicator(state = OverallSyncState.PartialFailure)
    }
}
