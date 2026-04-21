package com.becalm.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.becalm.android.R

/**
 * Inline counterparty display for Today timeline rows.
 *
 * [name] is the fully-resolved counterparty label produced by
 * `TodayViewModel.resolveCounterpartyDisplay` (`display_name → nickname →
 * personRef → counterpartyRaw.take(30)`). When the VM hands back null — a rare
 * case where every fallback produced a blank — a localized "Unknown" placeholder
 * renders instead of collapsing the layout.
 *
 * Single-line, `bodySmall`, `onSurfaceVariant`. Long strings ellipsize.
 *
 * Spec: TDY-001 (`.spec/today-timeline.spec.yml`),
 * `.spec/contracts/ui-map.yml § TodayTimelineRow.components § CounterpartyText`.
 */
@Composable
public fun CounterpartyText(
    name: String?,
    modifier: Modifier = Modifier,
) {
    val shown = name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.today_counterparty_unknown)
    Text(
        text = shown,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
