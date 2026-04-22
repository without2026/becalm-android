package com.becalm.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.becalm.android.R
import com.becalm.android.core.util.RelativeSince
import com.becalm.android.core.util.relativeSinceKst
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Relative-time label rendered at the bottom of a Today timeline row.
 *
 * The label is computed with a KST-anchored bucket
 * ([relativeSinceKst][com.becalm.android.core.util.relativeSinceKst]) and then
 * resolved against the active locale's string resources:
 *
 * - < 1 minute → `today_since_just_now`
 * - 1..59 minutes → `today_since_minutes`
 * - 1..23 hours → `today_since_hours`
 * - same-day KST but older than 24 hours elapsed (rare for the Today window) →
 *   `today_since_today_at` with a zero-padded HH:mm of [sortKey] in KST.
 *
 * The Composable defaults to [Clock.System] so callers don't plumb a clock
 * parameter through the timeline; unit coverage for the bucket logic lives on
 * the pure function, not on the Composable.
 *
 * Spec: `.spec/contracts/ui-map.yml § TimestampText`,
 * `.spec/today-timeline.spec.yml` KST invariant.
 */
@Composable
public fun TimestampText(
    sortKey: Instant,
    modifier: Modifier = Modifier,
    clock: Clock = Clock.System,
) {
    val bucket = remember(sortKey) { relativeSinceKst(now = clock.now(), past = sortKey) }
    val text = when (bucket) {
        RelativeSince.JustNow -> stringResource(R.string.today_since_just_now)
        is RelativeSince.Minutes -> stringResource(R.string.today_since_minutes, bucket.n)
        is RelativeSince.Hours -> stringResource(R.string.today_since_hours, bucket.n)
        is RelativeSince.TodayAt -> stringResource(R.string.today_since_today_at, bucket.hhmm)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
