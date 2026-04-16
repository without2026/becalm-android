/**
 * Dark-first cosmic palette ported from BeCalmv3 desktop.
 *
 * Raw color constants (prefixed `Cosmic*` / `Glass*`) are module-internal and
 * should never be referenced directly outside this package. All consumer code
 * should go through [BecalmDarkColorScheme] / [BecalmLightColorScheme] (M3
 * slots) or [BecalmColors] (semantic extension — see BecalmColors.kt).
 *
 * Source of truth: `BeCalmv3/desktop/src/renderer/styles/global.css`, extracted
 * 2026-04-16.  Every hex value in this file traces back to a CSS variable in
 * that stylesheet — deviations are noted inline.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Raw cosmic palette ───────────────────────────────────────────────────────
// These are implementation details.  Do NOT reference them from UI code.

/** True near-black canvas. v3 `--bg: #111111`. Do NOT drift to #1A1A1A. */
internal val CosmicBackground = Color(0xFF111111)

/** 1 % step above background. Used as primaryContainer / surfaceVariant tint. */
internal val CosmicSurface1 = Color(0xFF1A1A1A)

/** 2 % step — secondaryContainer, input backgrounds. */
internal val CosmicSurface2 = Color(0xFF1C1C1C)

/** Readable body copy on dark surfaces. v3 `--text: rgba(255,255,255,0.92)`. */
internal val CosmicTextPrimary = Color(0xEAEAEAEA)

/** Secondary / muted text. v3 `--muted: rgba(255,255,255,0.70)`. */
internal val CosmicTextMuted = Color(0xB2B2B2B2)

/** Slightly dimmed body copy. v3 container-on text. */
internal val CosmicTextSubtle = Color(0xFFD9D9D9)

/** The only sanctioned hue. v3 `rgba(245,158,11,1)` amber. D-0 / warning only. */
internal val CosmicAmber = Color(0xFFF5AD0B)

/** Honey-gold text on amber backgrounds. v3 `.permission-banner-title`. */
internal val CosmicHoneyGold = Color(0xF2FFD282)

/** v3 `--danger: rgba(255,100,100,0.8)` rendered at full alpha for M3 slot. */
internal val CosmicDanger = Color(0xFFFF6464)

/** Touch-target-friendly border. Derived from v3 `--border` on #111, raised
 *  slightly for One UI visibility. */
internal val CosmicOutline = Color(0xFF3D3D3D)

/** Exact composite of v3 `--border: rgba(255,255,255,0.08)` over #111. */
internal val CosmicOutlineVariant = Color(0xFF1E1E1E)

// Glass layer primitives — see §2 / §3 of design token spec
/** Glass panel fill — base alpha. v3 `--panel: rgba(255,255,255,0.04)`. */
internal val GlassPanelFill = Color(0x0AFFFFFF)

/**
 * Glass panel fill for SDK 28–30 devices where [android.view.View.setRenderEffect]
 * / [androidx.compose.ui.draw.blur] is unavailable.  Alpha raised to 0x1A (10 %)
 * to partially compensate for the absent backdrop blur.
 */
internal val GlassPanelFillSdkLegacy = Color(0x1AFFFFFF)

/** Slightly brighter glass fill for elevated surfaces (modals / bottom sheets). */
internal val GlassPanelFillElevated = Color(0x0FFFFFFF)

/** Elevated fill fallback — SDK 28–30. Raised to 0x26 (15 %). */
internal val GlassPanelFillElevatedLegacy = Color(0x26FFFFFF)

/** Muted glass fill for de-emphasised / disabled surfaces. */
internal val GlassPanelFillMuted = Color(0x08FFFFFF)

/** Muted fill fallback — SDK 28–30. Raised to 0x14 (8 %) — deliberately lower
 *  than the standard fallback since content is intentionally de-emphasised. */
internal val GlassPanelFillMutedLegacy = Color(0x14FFFFFF)

/** Hairline border on all glass surfaces. v3 `--border: rgba(255,255,255,0.08)`. */
internal val GlassBorder = Color(0x14FFFFFF)

/** Inset top-edge highlight. v3 `--glass-shadow: rgba(255,255,255,0.04)`. */
internal val GlassInset = Color(0x0AFFFFFF)

/** Inset highlight on elevated surfaces (slightly more visible). */
internal val GlassInsetElevated = Color(0x0DFFFFFF)

/** Outer drop-shadow fill. `rgba(0,0,0,0.30)`. */
internal val GlassOuterShadow = Color(0x4D000000)

/** Stronger outer shadow for elevated panels. `rgba(0,0,0,0.40)`. */
internal val GlassOuterShadowElevated = Color(0x66000000)

// ─── Material3 ColorScheme — Dark (canonical) ────────────────────────────────

/**
 * BeCalm dark color scheme. This is the canonical brand expression for MVP.
 * [surfaceTint] is explicitly [Color.Transparent] to disable M3 tonal elevation
 * tinting, which would corrupt the cosmic-black aesthetic on all elevated surfaces.
 */
internal val BecalmDarkColorScheme = darkColorScheme(
    // Primary — near-white monochrome.  v3 `--accent: rgba(255,255,255,0.85)`
    // mapped to M3 primary.  Off-white 0xEA avoids pure-white harshness on OLED.
    primary = Color(0xFFEAEAEA),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF1A1A1A),
    onPrimaryContainer = Color(0xFFD9D9D9),

    // Secondary — muted interactive elements.
    secondary = Color(0xB2B2B2B2),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF1C1C1C),
    onSecondaryContainer = Color(0xFFC2C2C2),

    // Tertiary — amber only; the single sanctioned hue for D-0 / warning states.
    tertiary = Color(0xFFF5AD0B),
    onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF1C1600),
    onTertiaryContainer = Color(0xF2FFD282),

    // Error
    error = Color(0xFFFF6464),
    onError = Color(0xFF111111),
    errorContainer = Color(0xFF3D0000),
    onErrorContainer = Color(0xFFFF9090),

    // Background / surface — flat cosmic field.
    background = Color(0xFF111111),
    onBackground = Color(0xEAEAEAEA),
    surface = Color(0xFF111111),
    onSurface = Color(0xEAEAEAEA),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xB2B2B2B2),

    // Outlines
    outline = Color(0xFF3D3D3D),
    outlineVariant = Color(0xFF1E1E1E),

    // Inverse
    inverseSurface = Color(0xFFE5E5E5),
    inverseOnSurface = Color(0xFF111111),
    inversePrimary = Color(0xFF333333),

    // Scrim — full black for modal overlays.
    scrim = Color(0xFF000000),

    // CRITICAL: must be Transparent to suppress M3 tonal elevation tinting.
    surfaceTint = Color.Transparent,
)

// ─── Material3 ColorScheme — Light ───────────────────────────────────────────

/**
 * BeCalm light color scheme. Provided for accessibility / user preference.
 * The brand expression is dark; light is a supported alternative, not the default.
 * [surfaceTint] remains [Color.Transparent] for the same reason as the dark scheme.
 */
internal val BecalmLightColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFF5F5F5),
    primaryContainer = Color(0xFFE8E8E8),
    onPrimaryContainer = Color(0xFF1A1A1A),

    secondary = Color(0xFF555555),
    onSecondary = Color(0xFFF5F5F5),
    secondaryContainer = Color(0xFFEBEBEB),
    onSecondaryContainer = Color(0xFF333333),

    // Amber darkened for adequate contrast on light background.
    tertiary = Color(0xFFC47D00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFDEFC8),
    onTertiaryContainer = Color(0xFF3D2700),

    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEA),
    onErrorContainer = Color(0xFF7F0000),

    // Warm off-white — not pure white.
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF555555),

    outline = Color(0xFFAFAFAF),
    outlineVariant = Color(0xFFDDDDDD),

    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFC8C8C8),

    scrim = Color(0xFF000000),

    // CRITICAL: suppress tonal elevation tinting on light scheme too.
    surfaceTint = Color.Transparent,
)
