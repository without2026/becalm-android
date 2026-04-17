/**
 * Semantic color extension for BeCalm's cosmic glassmorphism design language.
 *
 * This file defines [BecalmColors], a data class that holds all tokens that do
 * not map cleanly to Material3 ColorScheme slots — glass surface primitives,
 * nebula ambient glows, commitment-state triples, direction cast colors, D-N
 * urgency badge colors, and source-status dot colors.
 *
 * Consume via [MaterialTheme.becalmColors] extension property, backed by
 * [LocalBecalmColors] CompositionLocal. The theme provides the correct instance
 * (dark or light) through [BecalmTheme].
 *
 * Source of truth: design token spec §2.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── State color triple ───────────────────────────────────────────────────────

/**
 * Triplet that describes a single actionable state (fill background, hairline
 * border, and readable text color).  Used for commitment states, D-N badges,
 * and direction tokens.
 */
@Immutable
public data class BecalmStateColors(
    /** Low-alpha background fill for the chip / card. */
    val fill: Color,
    /** Hairline border around the chip / card. */
    val border: Color,
    /** Text / icon color rendered on top of the fill. */
    val text: Color,
)

// ─── Direction pair ───────────────────────────────────────────────────────────

/**
 * Border + fill pair for a directional cast (give = warm amber, take = cool
 * slate). Text color is inherited from the parent commitment state; these colors
 * are layered on top, not replacing it.
 */
@Immutable
public data class BecalmDirectionColors(
    val fill: Color,
    val border: Color,
)

// ─── BecalmColors ─────────────────────────────────────────────────────────────

/**
 * Complete set of semantic BeCalm color tokens that extend Material3.
 *
 * All hex values trace back to `BeCalmv3/desktop/src/renderer/styles/global.css`
 * via the design token spec (§2). Do not add tokens here without a spec entry.
 */
@Immutable
public data class BecalmColors(

    // ── Glass surface primitives ──────────────────────────────────────────────
    /** Translucent fill for standard cards and list items. */
    val glassPanelFill: Color,
    /** Translucent fill for SDK 28–30 devices (no blur available; alpha raised). */
    val glassPanelFillSdkLegacy: Color,
    /** Elevated fill for modals / bottom sheets (API 31+). */
    val glassPanelFillElevated: Color,
    /** Elevated fill fallback for SDK 28–30. */
    val glassPanelFillElevatedLegacy: Color,
    /** Muted fill for disabled / de-emphasised surfaces. */
    val glassPanelFillMuted: Color,
    /** Muted fill fallback for SDK 28–30. */
    val glassPanelFillMutedLegacy: Color,
    /** Hairline border on all glass surfaces. */
    val glassBorder: Color,
    /** Muted border for [glassPanelMuted] variant. */
    val glassBorderMuted: Color,
    /** Inset top-edge highlight (standard panels). */
    val glassInset: Color,
    /** Inset highlight for elevated surfaces (slightly more opaque). */
    val glassInsetElevated: Color,
    /** Outer drop-shadow fill (standard). */
    val glassOuterShadow: Color,
    /** Outer drop-shadow fill for elevated surfaces. */
    val glassOuterShadowElevated: Color,

    // ── Ambient / nebula glows ────────────────────────────────────────────────
    /** Solid canvas background. Always pass as `containerColor` on root Scaffold. */
    val cosmicBackground: Color,
    /** Center stop of primary radial glow. `rgba(80,80,80,0.35)`. */
    val ambientGlowCore: Color,
    /** Outer stop of primary glow at 70 % radius — transparent fade. */
    val ambientGlowEdge: Color,
    /** Secondary screen-corner glow. v3 `body::after rgba(60,60,60,0.30)`. */
    val ambientGlowCool: Color,
    /** Warm amber fog behind commitment-heavy screens. */
    val ambientGlowWarm: Color,
    /** Stronger center glow for full-screen modals (e.g. ColdSyncScreen). */
    val ambientGlowStrong: Color,

    // ── Commitment action states ──────────────────────────────────────────────
    /** Neutral / not-yet-acted-on state. White alpha hierarchy. */
    val actionStatePending: BecalmStateColors,
    /** Reminder has been sent. Amber cast signals attention. */
    val actionStateReminded: BecalmStateColors,
    /** Follow-up sent — slightly brighter than pending. */
    val actionStateFollowedUp: BecalmStateColors,
    /** Resolved. Intentionally dimmed — visually recedes from active items. */
    val actionStateCompleted: BecalmStateColors,

    // ── Direction cast ────────────────────────────────────────────────────────
    /** Give commitment — warm amber cast (low chroma, reads as neutral in dim light). */
    val directionGive: BecalmDirectionColors,
    /** Take commitment — cool slate cast. */
    val directionTake: BecalmDirectionColors,

    // ── D-N urgency badges ────────────────────────────────────────────────────
    /** D-0: due today — amber urgency. */
    val dayBadgeToday: BecalmStateColors,
    /** D-1..D-3: due soon — softer honey-gold. */
    val dayBadgeSoon: BecalmStateColors,
    /** D-4+: upcoming — neutral / muted white. */
    val dayBadgeUpcoming: BecalmStateColors,
    /** D+N: overdue — red danger signal. */
    val dayBadgeOverdue: BecalmStateColors,

    // ── Source status dots ────────────────────────────────────────────────────
    /** Healthy source — off-white, no call to action. */
    val sourceStatusOk: Color,
    /** Stale source — amber, needs re-sync attention. */
    val sourceStatusStale: Color,
    /** Failed source — red danger, needs reconnect. */
    val sourceStatusError: Color,
)

// ─── Dark instance ────────────────────────────────────────────────────────────

internal val BecalmDarkColors = BecalmColors(
    // Glass primitives
    glassPanelFill = Color(0x0AFFFFFF),
    glassPanelFillSdkLegacy = Color(0x1AFFFFFF),
    glassPanelFillElevated = Color(0x0FFFFFFF),
    glassPanelFillElevatedLegacy = Color(0x26FFFFFF),
    glassPanelFillMuted = Color(0x08FFFFFF),
    glassPanelFillMutedLegacy = Color(0x14FFFFFF),
    glassBorder = Color(0x14FFFFFF),
    glassBorderMuted = Color(0x0DFFFFFF),
    glassInset = Color(0x0AFFFFFF),
    glassInsetElevated = Color(0x0DFFFFFF),
    glassOuterShadow = Color(0x4D000000),
    glassOuterShadowElevated = Color(0x66000000),

    // Nebula glows
    cosmicBackground = Color(0xFF111111),
    ambientGlowCore = Color(0x59505050),
    ambientGlowEdge = Color.Transparent,
    ambientGlowCool = Color(0x4D3C3C3C),
    ambientGlowWarm = Color(0x33503818),
    ambientGlowStrong = Color(0x66505050),

    // Commitment action states — dark
    actionStatePending = BecalmStateColors(
        fill = Color(0x1AFFFFFF),   // α=0.10
        border = Color(0x40FFFFFF), // α=0.25
        text = Color(0xEAEAEAEA),   // α=0.92
    ),
    actionStateReminded = BecalmStateColors(
        fill = Color(0x1AF5AD0B),   // α=0.10 amber
        border = Color(0x59F5AD0B), // α=0.35 amber
        text = Color(0xF2FFD282),   // honey-gold α=0.95
    ),
    actionStateFollowedUp = BecalmStateColors(
        fill = Color(0x26FFFFFF),   // α=0.15
        border = Color(0x40FFFFFF), // α=0.25
        text = Color(0xD9D9D9D9),   // α=0.85
    ),
    actionStateCompleted = BecalmStateColors(
        fill = Color(0x0DFFFFFF),   // α=0.05 — intentionally dim
        border = Color(0x1AFFFFFF), // α=0.10
        text = Color(0x99B2B2B2),   // α=0.60 dimmed muted
    ),

    // Direction cast — dark
    directionGive = BecalmDirectionColors(
        fill = Color(0x1AF5C842),
        border = Color(0x40F5C842),
    ),
    directionTake = BecalmDirectionColors(
        fill = Color(0x1AA0B4C8),
        border = Color(0x40A0B4C8),
    ),

    // D-N badges — dark
    dayBadgeToday = BecalmStateColors(
        fill = Color(0x33F5AD0B),   // α=0.20 amber
        border = Color(0x59F5AD0B), // α=0.35 amber
        text = Color(0xF2FFD282),   // honey-gold
    ),
    dayBadgeSoon = BecalmStateColors(
        fill = Color(0x1AFFD282),   // α=0.10 honey-gold
        border = Color(0x33FFD282), // α=0.20 honey-gold
        text = Color(0xD9FFD282),   // α=0.85 honey-gold
    ),
    dayBadgeUpcoming = BecalmStateColors(
        fill = Color(0x0AFFFFFF),   // α=0.04 neutral
        border = Color(0x14FFFFFF), // α=0.08 neutral
        text = Color(0xB3B3B3B3),   // α=0.70 muted
    ),
    dayBadgeOverdue = BecalmStateColors(
        fill = Color(0x33EF4444),   // α=0.20 red
        border = Color(0x59EF4444), // α=0.35 red
        text = Color(0xCCFF6464),   // α=0.80 red
    ),

    // Source status dots — dark
    sourceStatusOk = Color(0xCCFFFFFF),    // α=0.80 off-white
    sourceStatusStale = Color(0xE6F5AD0B), // α=0.90 amber
    sourceStatusError = Color(0xCCFF6464), // α=0.80 red
)

// ─── Light instance ───────────────────────────────────────────────────────────

internal val BecalmLightColors = BecalmColors(
    // Glass primitives — light uses dark-on-light fills
    glassPanelFill = Color(0x0A000000),
    glassPanelFillSdkLegacy = Color(0x1A000000),
    glassPanelFillElevated = Color(0x0F000000),
    glassPanelFillElevatedLegacy = Color(0x26000000),
    glassPanelFillMuted = Color(0x08000000),
    glassPanelFillMutedLegacy = Color(0x14000000),
    glassBorder = Color(0x14000000),
    glassBorderMuted = Color(0x0D000000),
    glassInset = Color(0x0A000000),
    glassInsetElevated = Color(0x0D000000),
    glassOuterShadow = Color(0x33000000),
    glassOuterShadowElevated = Color(0x4D000000),

    // Nebula glows — muted on light bg
    cosmicBackground = Color(0xFFF5F5F5),
    ambientGlowCore = Color(0x33AAAAAA),
    ambientGlowEdge = Color.Transparent,
    ambientGlowCool = Color(0x26909090),
    ambientGlowWarm = Color(0x1AC47D00),
    ambientGlowStrong = Color(0x4DAAAAAA),

    // Commitment action states — light (spec: invert alpha direction)
    // borders rgba(0,0,0,0.12), fills rgba(0,0,0,0.04), text rgba(0,0,0,0.87)
    actionStatePending = BecalmStateColors(
        fill = Color(0x0A000000),   // rgba(0,0,0,0.04)
        border = Color(0x1F000000), // rgba(0,0,0,0.12)
        text = Color(0xDE000000),   // rgba(0,0,0,0.87)
    ),
    actionStateReminded = BecalmStateColors(
        fill = Color(0x0DC47D00),
        border = Color(0x26C47D00),
        text = Color(0xFF7A4F00),   // dark amber text for light bg
    ),
    actionStateFollowedUp = BecalmStateColors(
        fill = Color(0x14000000),
        border = Color(0x1F000000),
        text = Color(0xCC000000),   // rgba(0,0,0,0.80)
    ),
    actionStateCompleted = BecalmStateColors(
        fill = Color(0x05000000),
        border = Color(0x0A000000),
        text = Color(0x66555555),   // dimmed on light
    ),

    // Direction cast — light
    directionGive = BecalmDirectionColors(
        fill = Color(0x0DC47D00),
        border = Color(0x26C47D00),
    ),
    directionTake = BecalmDirectionColors(
        fill = Color(0x0D7090A8),
        border = Color(0x267090A8),
    ),

    // D-N badges — light
    dayBadgeToday = BecalmStateColors(
        fill = Color(0x1FC47D00),
        border = Color(0x33C47D00),
        text = Color(0xFF7A4F00),
    ),
    dayBadgeSoon = BecalmStateColors(
        fill = Color(0x14C47D00),
        border = Color(0x1FC47D00),
        text = Color(0xCC8C5A00),
    ),
    dayBadgeUpcoming = BecalmStateColors(
        fill = Color(0x0A000000),
        border = Color(0x14000000),
        text = Color(0x99555555),
    ),
    dayBadgeOverdue = BecalmStateColors(
        fill = Color(0x1FC62828),
        border = Color(0x33C62828),
        text = Color(0xFF7F0000),
    ),

    // Source status dots — light
    sourceStatusOk = Color(0xFF555555),    // dark-neutral on light bg
    sourceStatusStale = Color(0xFFC47D00), // dark amber
    sourceStatusError = Color(0xFFC62828), // dark red
)

// ─── CompositionLocal ─────────────────────────────────────────────────────────

/**
 * Provides the current [BecalmColors] instance down the composition tree.
 * [BecalmTheme] calls `CompositionLocalProvider(LocalBecalmColors provides ...)`.
 * Defaults to [BecalmDarkColors] so previews render correctly without a theme
 * wrapper.
 */
public val LocalBecalmColors: ProvidableCompositionLocal<BecalmColors> =
    staticCompositionLocalOf { BecalmDarkColors }

// ─── MaterialTheme extension ──────────────────────────────────────────────────

/**
 * Convenience accessor — retrieve [BecalmColors] from anywhere inside
 * [BecalmTheme].
 *
 * Usage:
 * ```kotlin
 * val glassColor = MaterialTheme.becalmColors.glassPanelFill
 * ```
 */
public val MaterialTheme.becalmColors: BecalmColors
    @Composable
    @ReadOnlyComposable
    get() = LocalBecalmColors.current
