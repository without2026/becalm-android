/**
 * Light-first warm relationship palette for BeCalm Android.
 *
 * PRODUCT.md defines BeCalm as a person-centered relationship intelligence
 * assistant. DESIGN.md now sets the canonical Android theme as warm, light,
 * restrained, and frosted. This file maps those OKLCH source tokens into
 * Compose ARGB values for Material3.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Material3 ColorScheme — Light (canonical) ───────────────────────────────

/**
 * BeCalm light color scheme. This is the canonical product expression: warm
 * light canvas, dark neutral actions, and a restrained warm accent.
 */
internal val BecalmLightColorScheme = lightColorScheme(
    primary = Color(0xFF342E27),
    onPrimary = Color(0xFFF4F0E9),
    primaryContainer = Color(0xFFECE3D5),
    onPrimaryContainer = Color(0xFF342E27),

    secondary = Color(0xFF696158),
    onSecondary = Color(0xFFF4F0E9),
    secondaryContainer = Color(0xFFE8E1D7),
    onSecondaryContainer = Color(0xFF453D35),

    tertiary = Color(0xFFB87623),
    onTertiary = Color(0xFFF8F1E8),
    tertiaryContainer = Color(0xFFF1DEC2),
    onTertiaryContainer = Color(0xFF4C2F0F),

    error = Color(0xFFB84A3E),
    onError = Color(0xFFF8F1E8),
    errorContainer = Color(0xFFF1D9D5),
    onErrorContainer = Color(0xFF5A1F19),

    background = Color(0xFFF4F0E9),
    onBackground = Color(0xFF342E27),
    surface = Color(0xFFF8F5EF),
    onSurface = Color(0xFF342E27),
    surfaceVariant = Color(0xFFEAE2D6),
    onSurfaceVariant = Color(0xFF696158),

    outline = Color(0xFFB8AC9E),
    outlineVariant = Color(0xFFD7CEC1),

    inverseSurface = Color(0xFF342E27),
    inverseOnSurface = Color(0xFFF4F0E9),
    inversePrimary = Color(0xFFECE3D5),

    scrim = Color(0xCC19120B),

    surfaceTint = Color.Transparent,
)

// ─── Material3 ColorScheme — Dark (alternate) ────────────────────────────────

/**
 * Dark is an alternate accessibility/user-preference theme. It keeps the same
 * warm neutral family instead of reviving the previous dark-first palette.
 */
internal val BecalmDarkColorScheme = darkColorScheme(
    primary = Color(0xFFF1DEC2),
    onPrimary = Color(0xFF231D18),
    primaryContainer = Color(0xFF3C3229),
    onPrimaryContainer = Color(0xFFF1DEC2),

    secondary = Color(0xFFC8BAAA),
    onSecondary = Color(0xFF231D18),
    secondaryContainer = Color(0xFF352D26),
    onSecondaryContainer = Color(0xFFE8E1D7),

    tertiary = Color(0xFFD19138),
    onTertiary = Color(0xFF231D18),
    tertiaryContainer = Color(0xFF4C2F0F),
    onTertiaryContainer = Color(0xFFF1DEC2),

    error = Color(0xFFE08A80),
    onError = Color(0xFF231D18),
    errorContainer = Color(0xFF5A1F19),
    onErrorContainer = Color(0xFFF1D9D5),

    background = Color(0xFF17130F),
    onBackground = Color(0xFFEDE5D8),
    surface = Color(0xFF1D1813),
    onSurface = Color(0xFFEDE5D8),
    surfaceVariant = Color(0xFF2B241D),
    onSurfaceVariant = Color(0xFFC8BAAA),

    outline = Color(0xFF74695D),
    outlineVariant = Color(0xFF3F362D),

    inverseSurface = Color(0xFFEDE5D8),
    inverseOnSurface = Color(0xFF231D18),
    inversePrimary = Color(0xFF342E27),

    scrim = Color(0xCC19120B),

    surfaceTint = Color.Transparent,
)
