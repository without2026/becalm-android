/**
 * Single shared minute-boundary tick for relative-time labels under
 * [BecalmTheme].
 *
 * Relative-time UI ("5분 전", "1시간 전") needs to invalidate when the
 * underlying minute bucket changes. Without a tick the label stays stale
 * until something else triggers recomposition. The same hoist logic that
 * applies to [LocalKstDayTick] applies here, just at a finer cadence:
 * one coroutine for the whole content tree, all consumers re-key on the
 * shared state.
 *
 * Consumers use the value as a [androidx.compose.runtime.remember] key for
 * time-relative computations; the long itself encodes the
 * minute-bucket-since-epoch.
 */
package com.becalm.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

private const val MILLIS_PER_MINUTE: Long = 60_000L
private const val NEXT_MINUTE_SAFETY_MS: Long = 500L

/**
 * Monotonically advancing tick — the integer count of minutes since the
 * Unix epoch. Increments once per minute boundary while a [BecalmTheme] is
 * in composition. Default `0L` means "no provider in scope".
 */
public val LocalMinuteTick = compositionLocalOf { 0L }

/**
 * Owns the single shared minute tick. Placed by [BecalmTheme] around its
 * content; consumers ([TimestampText], etc.) read [LocalMinuteTick.current]
 * as a remember key.
 */
@Composable
public fun ProvideMinuteTick(content: @Composable () -> Unit) {
    var tick by remember {
        mutableLongStateOf(System.currentTimeMillis() / MILLIS_PER_MINUTE)
    }
    LaunchedEffect(tick) {
        val nowMs = System.currentTimeMillis()
        val nextMinuteMs = ((nowMs / MILLIS_PER_MINUTE) + 1) * MILLIS_PER_MINUTE
        val delayMs = (nextMinuteMs - nowMs).coerceAtLeast(0L) + NEXT_MINUTE_SAFETY_MS
        delay(delayMs)
        tick = System.currentTimeMillis() / MILLIS_PER_MINUTE
    }
    CompositionLocalProvider(LocalMinuteTick provides tick) {
        content()
    }
}
