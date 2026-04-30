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
 * MVP default: [useDarkTheme] = `true` — the canonical brand experience is dark.
 * Do NOT toggle via [androidx.compose.foundation.isSystemInDarkTheme] until a
 * user-facing settings toggle is wired (design token spec §8, BecalmTheme note).
 *
 * Usage:
 * ```kotlin
 * BecalmTheme {
 *     Scaffold(containerColor = MaterialTheme.becalmColors.cosmicBackground) {
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
 * @param useDarkTheme `true` to use the dark cosmic palette (MVP default).
 *   Set to `false` to use the light scheme.  Do NOT drive this from
 *   `isSystemInDarkTheme()` until a settings toggle is implemented.
 * @param content Composable content rendered inside the theme.
 */
@Composable
public fun BecalmTheme(
    useDarkTheme: Boolean = true,
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
            // Single shared KST midnight tick for the whole content tree.
            // See KstClock.kt for why this is hoisted instead of per-card.
            ProvideKstDayTick {
                content()
            }
        }
    }
}
