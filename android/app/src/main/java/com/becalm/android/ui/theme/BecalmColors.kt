/**
 * Semantic color extension for BeCalm's warm frosted relationship design language.
 *
 * This file defines [BecalmColors], a data class that holds all tokens that do
 * not map cleanly to Material3 ColorScheme slots: glass surface primitives,
 * canvas washes, commitment-state triples, direction cast colors, D-N urgency
 * badge colors, and source-status dot colors.
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
    /** Hairline border on all glass surfaces. */
    val glassBorder: Color,
    /** Inset highlight for elevated surfaces (slightly more opaque). */
    val glassInsetElevated: Color,
    /** Outer drop-shadow fill (standard). */
    val glassOuterShadow: Color,
    /** Outer drop-shadow fill for elevated surfaces. */
    val glassOuterShadowElevated: Color,

    // ── Canvas warm washes ───────────────────────────────────────────────────
    /** Solid canvas background. Always pass as `containerColor` on root Scaffold. */
    val canvasBackground: Color,
    /** Center stop of primary warm wash. */
    val ambientGlowCore: Color,
    /** Outer stop of primary wash. */
    val ambientGlowEdge: Color,

    // ── Commitment action states ──────────────────────────────────────────────
    /** Neutral / not-yet-acted-on state. */
    val actionStatePending: BecalmStateColors,
    /** Reminder has been sent. Amber cast signals attention. */
    val actionStateReminded: BecalmStateColors,
    /** Follow-up sent — slightly brighter than pending. */
    val actionStateFollowedUp: BecalmStateColors,
    /** Resolved. Intentionally dimmed — visually recedes from active items. */
    val actionStateCompleted: BecalmStateColors,

    // ── Direction cast ────────────────────────────────────────────────────────
    /** Give commitment, warm low-chroma cast. */
    val directionGive: BecalmDirectionColors,
    /** Take commitment, muted relationship-memory cast. */
    val directionTake: BecalmDirectionColors,

    // ── D-N urgency badges ────────────────────────────────────────────────────
    /** D-0: due today — amber urgency. */
    val dayBadgeToday: BecalmStateColors,
    /** D-1..D-3: due soon — softer honey-gold. */
    val dayBadgeSoon: BecalmStateColors,
    /** D-4+: upcoming neutral. */
    val dayBadgeUpcoming: BecalmStateColors,
    /** D+N: overdue — red danger signal. */
    val dayBadgeOverdue: BecalmStateColors,

    // ── Source status dots ────────────────────────────────────────────────────
    /** Healthy source, quiet neutral success. */
    val sourceStatusOk: Color,
    /** Stale source — amber, needs re-sync attention. */
    val sourceStatusStale: Color,
    /** Failed source — red danger, needs reconnect. */
    val sourceStatusError: Color,
)

// ─── Dark instance ────────────────────────────────────────────────────────────

internal val BecalmDarkColors = BecalmColors(
    glassPanelFill = Color(0x1FF8F1E8),
    glassPanelFillSdkLegacy = Color(0x2EF8F1E8),
    glassPanelFillElevated = Color(0x2EF8F1E8),
    glassPanelFillElevatedLegacy = Color(0x40F8F1E8),
    glassBorder = Color(0x24F8F1E8),
    glassInsetElevated = Color(0x1AF8F1E8),
    glassOuterShadow = Color(0x6619120B),
    glassOuterShadowElevated = Color(0x8019120B),

    canvasBackground = Color(0xFF17130F),
    ambientGlowCore = Color(0x334C2F0F),
    ambientGlowEdge = Color.Transparent,

    actionStatePending = BecalmStateColors(
        fill = Color(0x1FF8F1E8),
        border = Color(0x40F8F1E8),
        text = Color(0xFFEDE5D8),
    ),
    actionStateReminded = BecalmStateColors(
        fill = Color(0x26D19138),
        border = Color(0x66D19138),
        text = Color(0xFFF1DEC2),
    ),
    actionStateFollowedUp = BecalmStateColors(
        fill = Color(0x26F8F1E8),
        border = Color(0x40F8F1E8),
        text = Color(0xFFE8E1D7),
    ),
    actionStateCompleted = BecalmStateColors(
        fill = Color(0x12F8F1E8),
        border = Color(0x24F8F1E8),
        text = Color(0x99C8BAAA),
    ),

    directionGive = BecalmDirectionColors(
        fill = Color(0x1FD19138),
        border = Color(0x4DD19138),
    ),
    directionTake = BecalmDirectionColors(
        fill = Color(0x1F8AA07E),
        border = Color(0x4D8AA07E),
    ),

    dayBadgeToday = BecalmStateColors(
        fill = Color(0x33D19138),
        border = Color(0x66D19138),
        text = Color(0xFFF1DEC2),
    ),
    dayBadgeSoon = BecalmStateColors(
        fill = Color(0x26F1DEC2),
        border = Color(0x40D19138),
        text = Color(0xFFE8C894),
    ),
    dayBadgeUpcoming = BecalmStateColors(
        fill = Color(0x12F8F1E8),
        border = Color(0x24F8F1E8),
        text = Color(0xB3C8BAAA),
    ),
    dayBadgeOverdue = BecalmStateColors(
        fill = Color(0x33E08A80),
        border = Color(0x66E08A80),
        text = Color(0xFFFFB7AE),
    ),

    sourceStatusOk = Color(0xFFC8BAAA),
    sourceStatusStale = Color(0xFFD19138),
    sourceStatusError = Color(0xFFE08A80),
)

// ─── Light instance ───────────────────────────────────────────────────────────

internal val BecalmLightColors = BecalmColors(
    glassPanelFill = Color(0xB8FCFAF5),
    glassPanelFillSdkLegacy = Color(0xD1FCFAF5),
    glassPanelFillElevated = Color(0xD1FCFAF5),
    glassPanelFillElevatedLegacy = Color(0xE6FCFAF5),
    glassBorder = Color(0x66B8AC9E),
    glassInsetElevated = Color(0x80FCFAF5),
    glassOuterShadow = Color(0x2419120B),
    glassOuterShadowElevated = Color(0x3319120B),

    canvasBackground = Color(0xFFF4F0E9),
    ambientGlowCore = Color(0x33F1DEC2),
    ambientGlowEdge = Color.Transparent,

    actionStatePending = BecalmStateColors(
        fill = Color(0x99FCFAF5),
        border = Color(0x66B8AC9E),
        text = Color(0xFF453D35),
    ),
    actionStateReminded = BecalmStateColors(
        fill = Color(0x66F1DEC2),
        border = Color(0x80B87623),
        text = Color(0xFF6B410F),
    ),
    actionStateFollowedUp = BecalmStateColors(
        fill = Color(0xB8FCFAF5),
        border = Color(0x66C8BAAA),
        text = Color(0xFF5C534A),
    ),
    actionStateCompleted = BecalmStateColors(
        fill = Color(0x73FCFAF5),
        border = Color(0x4DB8AC9E),
        text = Color(0x998B8379),
    ),

    directionGive = BecalmDirectionColors(
        fill = Color(0x4DF1DEC2),
        border = Color(0x80B87623),
    ),
    directionTake = BecalmDirectionColors(
        fill = Color(0x4DE2E6DD),
        border = Color(0x805F7F69),
    ),

    dayBadgeToday = BecalmStateColors(
        fill = Color(0x80F1DEC2),
        border = Color(0x99B87623),
        text = Color(0xFF6B410F),
    ),
    dayBadgeSoon = BecalmStateColors(
        fill = Color(0x59F1DEC2),
        border = Color(0x66B87623),
        text = Color(0xCC6B410F),
    ),
    dayBadgeUpcoming = BecalmStateColors(
        fill = Color(0x99FCFAF5),
        border = Color(0x66B8AC9E),
        text = Color(0x99696158),
    ),
    dayBadgeOverdue = BecalmStateColors(
        fill = Color(0x66F1D9D5),
        border = Color(0x80B84A3E),
        text = Color(0xFF7A281F),
    ),

    sourceStatusOk = Color(0xFF5F7F69),
    sourceStatusStale = Color(0xFFB87623),
    sourceStatusError = Color(0xFFB84A3E),
)

// ─── CompositionLocal ─────────────────────────────────────────────────────────

/**
 * Provides the current [BecalmColors] instance down the composition tree.
 * [BecalmTheme] calls `CompositionLocalProvider(LocalBecalmColors provides ...)`.
 * Defaults to [BecalmLightColors] so previews render correctly without a theme
 * wrapper.
 */
public val LocalBecalmColors: ProvidableCompositionLocal<BecalmColors> =
    staticCompositionLocalOf { BecalmLightColors }

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
