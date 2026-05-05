/**
 * Root theme composable for BeCalm Android.
 *
 * [BecalmTheme] wires together:
 *   - Material3 [ColorScheme] (dark or light, per [useDarkTheme])
 *   - [BecalmTypography] (Pretendard Variable font family)
 *   - [BecalmShapes] (rounded corner scale)
 *   - [LocalBecalmColors] (semantic glass / state / glow tokens)
 *   - [LocalBecalmDimens] (spacing and component-slot dimensions)
 *
 * Default: [useDarkTheme] = `false`. The canonical Android experience is the
 * light-first frosted relationship theme from DESIGN.md. Dark remains an
 * alternate user-preference / accessibility path.
 *
 * Usage:
 * ```kotlin
 * BecalmTheme {
 *     Scaffold(containerColor = MaterialTheme.becalmColors.canvasBackground) {
 *         // content
 *     }
 * }
 * ```
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * BeCalm theme wrapper.
 *
 * @param useDarkTheme `false` uses the canonical light frosted palette.
 *   `true` uses the warm dark alternate.
 * @param content Composable content rendered inside the theme.
 */
@Composable
public fun BecalmTheme(
    useDarkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (useDarkTheme) BecalmDarkColorScheme else BecalmLightColorScheme
    val becalmColors = if (useDarkTheme) BecalmDarkColors else BecalmLightColors

    CompositionLocalProvider(
        LocalBecalmColors provides becalmColors,
        LocalBecalmDimens provides BecalmDimensDefault,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BecalmTypography,
            shapes = BecalmShapes,
        ) {
            // Two shared time-tick providers for the whole content tree.
            // ProvideKstDayTick fires on each KST midnight (CommitmentCard
            // D-N badge); ProvideMinuteTick fires on each minute boundary
            // (TimestampText "X분 전" labels). Both are hoisted to one
            // coroutine each instead of per-consumer LaunchedEffects.
            ProvideKstDayTick {
                ProvideMinuteTick {
                    content()
                }
            }
        }
    }
}
