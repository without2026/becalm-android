/**
 * Single shared KST midnight tick for all composables under [BecalmTheme].
 *
 * Day-relative UI (D-N badges, `오늘`/`내일` labels, KST date headers) needs to
 * re-render when the KST calendar boundary passes. The naïve fix is for each
 * affected composable to spawn its own [LaunchedEffect] that waits until the
 * next midnight. That works at small N but produces:
 *
 *  1. N coroutines on `Dispatchers.Main.immediate`, one per consumer.
 *  2. A simultaneous wake-up storm at exactly 00:00 KST — N state changes in
 *     the same frame, N recompositions, all redundant.
 *
 * Hoisting the tick to a single producer at [BecalmTheme] level collapses both:
 * one coroutine, one state change, every consumer that reads
 * [LocalKstDayTick.current] participates in the same recomposition pass.
 *
 * Consumers use the tick as a [androidx.compose.runtime.remember] key for
 * day-relative computations; the integer value itself is opaque (it just has
 * to differ across midnight boundaries).
 */
package com.becalm.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.becalm.android.core.util.KST
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Monotonically increasing counter that bumps once per KST midnight while a
 * [ProvideKstDayTick] (or [BecalmTheme], which wraps it) is in composition.
 *
 * Default value `0` means "no provider is in scope" — composables that read
 * the local outside of [BecalmTheme] will simply not refresh on midnight,
 * which is the same degradation the old per-card implementation had after a
 * card was disposed and recomposed.
 */
public val LocalKstDayTick = compositionLocalOf { 0 }

/**
 * Owns the single shared KST midnight tick. Placed by [BecalmTheme] around its
 * content so every screen automatically participates without each consumer
 * re-implementing the LaunchedEffect.
 */
@Composable
public fun ProvideKstDayTick(content: @Composable () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        val now = Clock.System.now()
        val nextMidnight = now.toLocalDateTime(KST).date
            .plus(1, DateTimeUnit.DAY)
            .atTime(0, 0, 0)
            .toInstant(KST)
        val delayMs = (nextMidnight.toEpochMilliseconds() - now.toEpochMilliseconds())
            .coerceAtLeast(0L) + 1_000L
        delay(delayMs)
        tick++
    }
    CompositionLocalProvider(LocalKstDayTick provides tick) {
        content()
    }
}
