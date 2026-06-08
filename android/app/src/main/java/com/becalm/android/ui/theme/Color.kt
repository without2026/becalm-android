/**
 * Ink-mist palette for BeCalm Android.
 *
 * The current HTML parity target (`becalm-v4-ux-inkmist-soft.html`) defines a
 * cool white app surface, navy primary actions, muted relationship accents, and
 * quiet grey borders. This file maps those tokens into Material3 slots.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Material3 ColorScheme — Light (canonical) ───────────────────────────────

/**
 * BeCalm light color scheme. Prototype token mapping:
 * --ink/#1A1F2E, --dark/#1E2A4A, --line/#E5E9F0,
 * --line2/#F0F3F8, --give/#C0967A, --take/#6589A1.
 */
internal val BecalmLightColorScheme = lightColorScheme(
    primary = Color(0xFF1E2A4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9EDF6),
    onPrimaryContainer = Color(0xFF1E2A4A),

    secondary = Color(0xFF6589A1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFECF0F4),
    onSecondaryContainer = Color(0xFF1A1F2E),

    tertiary = Color(0xFFC0967A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2EDE7),
    onTertiaryContainer = Color(0xFF1A1F2E),

    error = Color(0xFFBC8071),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF3ECEA),
    onErrorContainer = Color(0xFF1A1F2E),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1F2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1F2E),
    surfaceVariant = Color(0xFFF0F3F8),
    onSurfaceVariant = Color(0xFF5A6478),
    surfaceDim = Color(0xFFF0F3F8),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF6F8FB),
    surfaceContainerHighest = Color(0xFFF0F3F8),

    outline = Color(0xFF9AA4BC),
    outlineVariant = Color(0xFFE5E9F0),

    inverseSurface = Color(0xFF1E2A4A),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFE9EDF6),

    scrim = Color(0x66121214),

    surfaceTint = Color.Transparent,
)

// ─── Material3 ColorScheme — Dark (alternate) ────────────────────────────────

/**
 * Dark is an alternate accessibility/user-preference theme. It keeps the
 * ink-mist hue relationships while reducing brightness for dark surfaces.
 */
internal val BecalmDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE9EDF6),
    onPrimary = Color(0xFF111827),
    primaryContainer = Color(0xFF2A3447),
    onPrimaryContainer = Color(0xFFE9EDF6),

    secondary = Color(0xFFB8C0D0),
    onSecondary = Color(0xFF111827),
    secondaryContainer = Color(0xFF273244),
    onSecondaryContainer = Color(0xFFECF0F4),

    tertiary = Color(0xFFD5B69F),
    onTertiary = Color(0xFF111827),
    tertiaryContainer = Color(0xFF3E332E),
    onTertiaryContainer = Color(0xFFF2EDE7),

    error = Color(0xFFE0A498),
    onError = Color(0xFF111827),
    errorContainer = Color(0xFF4C2B27),
    onErrorContainer = Color(0xFFF3ECEA),

    background = Color(0xFF111827),
    onBackground = Color(0xFFF6F8FB),
    surface = Color(0xFF172033),
    onSurface = Color(0xFFF6F8FB),
    surfaceVariant = Color(0xFF202A3C),
    onSurfaceVariant = Color(0xFFB8C0D0),
    surfaceDim = Color(0xFF111827),
    surfaceBright = Color(0xFF202A3C),
    surfaceContainerLowest = Color(0xFF111827),
    surfaceContainerLow = Color(0xFF172033),
    surfaceContainer = Color(0xFF172033),
    surfaceContainerHigh = Color(0xFF202A3C),
    surfaceContainerHighest = Color(0xFF273244),

    outline = Color(0xFF778299),
    outlineVariant = Color(0xFF334054),

    inverseSurface = Color(0xFFF6F8FB),
    inverseOnSurface = Color(0xFF111827),
    inversePrimary = Color(0xFF1E2A4A),

    scrim = Color(0xCC121214),

    surfaceTint = Color.Transparent,
)
