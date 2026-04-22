package com.becalm.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.becalm.android.core.util.KST
import com.becalm.android.core.util.formatKoreanDateTime
import kotlinx.datetime.Instant

/**
 * KST-formatted timestamp label rendered at the bottom of a raw-event detail card.
 *
 * All raw-event timestamps are business-calendar values per
 * `.spec/contracts/data-model.yml:132-144` — UI layer must render them in
 * `Asia/Seoul` regardless of the device's selected timezone.
 *
 * Formatting is delegated to [formatKoreanDateTime] so the label shape matches
 * the rest of the product (`"2026-04-16 오후 2:30"`). The `remember`-scoped
 * memoization avoids re-formatting on every recomposition — the input is an
 * immutable [Instant] so the formatted string is stable.
 *
 * Spec: `.spec/contracts/ui-map.yml:113-118 § IngestionTimestamp`.
 */
@Composable
public fun IngestionTimestamp(
    timestamp: Instant,
    modifier: Modifier = Modifier,
) {
    val text = remember(timestamp) { formatKoreanDateTime(timestamp, tz = KST) }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
