/**
 * Semantic color extension for BeCalm's ink-mist relationship design language.
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
 * Source of truth: `becalm-v4-ux-inkmist-soft.html`.
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
 * Border + fill pair for a directional cast (give = muted tan, take = cool
 * blue). Text color is inherited from the parent commitment state; these colors
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

    // ── Canvas washes ────────────────────────────────────────────────────────
    /** Solid canvas background. Always pass as `containerColor` on root Scaffold. */
    val canvasBackground: Color,
    /** Center stop of the optional root wash. */
    val ambientGlowCore: Color,
    /** Outer stop of primary wash. */
    val ambientGlowEdge: Color,

    // ── Commitment action states ──────────────────────────────────────────────
    /** Neutral / not-yet-acted-on state. */
    val actionStatePending: BecalmStateColors,
    /** Reminder has been sent. Muted give cast signals attention. */
    val actionStateReminded: BecalmStateColors,
    /** Follow-up sent — slightly brighter than pending. */
    val actionStateFollowedUp: BecalmStateColors,
    /** Resolved. Intentionally dimmed — visually recedes from active items. */
    val actionStateCompleted: BecalmStateColors,

    // ── Direction cast ────────────────────────────────────────────────────────
    /** Give commitment, muted tan cast. */
    val directionGive: BecalmDirectionColors,
    /** Take commitment, muted relationship-memory cast. */
    val directionTake: BecalmDirectionColors,

    // ── D-N urgency badges ────────────────────────────────────────────────────
    /** D-0: due today. */
    val dayBadgeToday: BecalmStateColors,
    /** D-1..D-3: due soon. */
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
    glassPanelFill = Color(0xF2172033),
    glassPanelFillSdkLegacy = Color(0xFF172033),
    glassPanelFillElevated = Color(0xFF172033),
    glassPanelFillElevatedLegacy = Color(0xFF172033),
    glassBorder = Color(0x66334054),
    glassInsetElevated = Color(0x1AFFFFFF),
    glassOuterShadow = Color(0x66111118),
    glassOuterShadowElevated = Color(0x80111118),

    canvasBackground = Color(0xFF111827),
    ambientGlowCore = Color.Transparent,
    ambientGlowEdge = Color.Transparent,

    actionStatePending = BecalmStateColors(
        fill = Color(0xFF172033),
        border = Color(0x66334054),
        text = Color(0xFFF6F8FB),
    ),
    actionStateReminded = BecalmStateColors(
        fill = Color(0x4DC0967A),
        border = Color(0x99C0967A),
        text = Color(0xFFF2EDE7),
    ),
    actionStateFollowedUp = BecalmStateColors(
        fill = Color(0x4D6589A1),
        border = Color(0x996589A1),
        text = Color(0xFFECF0F4),
    ),
    actionStateCompleted = BecalmStateColors(
        fill = Color(0x337E948A),
        border = Color(0x667E948A),
        text = Color(0xCCEDF1EF),
    ),

    directionGive = BecalmDirectionColors(
        fill = Color(0x33C0967A),
        border = Color(0x80C0967A),
    ),
    directionTake = BecalmDirectionColors(
        fill = Color(0x336589A1),
        border = Color(0x806589A1),
    ),

    dayBadgeToday = BecalmStateColors(
        fill = Color(0x4DBC8071),
        border = Color(0x99BC8071),
        text = Color(0xFFF3ECEA),
    ),
    dayBadgeSoon = BecalmStateColors(
        fill = Color(0x33C0967A),
        border = Color(0x80C0967A),
        text = Color(0xFFF2EDE7),
    ),
    dayBadgeUpcoming = BecalmStateColors(
        fill = Color(0x33202A3C),
        border = Color(0x66334054),
        text = Color(0xCCB8C0D0),
    ),
    dayBadgeOverdue = BecalmStateColors(
        fill = Color(0x4DBC8071),
        border = Color(0x99BC8071),
        text = Color(0xFFFFC7BD),
    ),

    sourceStatusOk = Color(0xFF7E948A),
    sourceStatusStale = Color(0xFFC0967A),
    sourceStatusError = Color(0xFFBC8071),
)

// ─── Light instance ───────────────────────────────────────────────────────────

internal val BecalmLightColors = BecalmColors(
    glassPanelFill = Color(0xFFFFFFFF),
    glassPanelFillSdkLegacy = Color(0xFFFFFFFF),
    glassPanelFillElevated = Color(0xFFFFFFFF),
    glassPanelFillElevatedLegacy = Color(0xFFFFFFFF),
    glassBorder = Color(0xFFE5E9F0),
    glassInsetElevated = Color(0xFFFFFFFF),
    glassOuterShadow = Color(0x0A000000),
    glassOuterShadowElevated = Color(0x1A000000),

    canvasBackground = Color(0xFFFFFFFF),
    ambientGlowCore = Color.Transparent,
    ambientGlowEdge = Color.Transparent,

    actionStatePending = BecalmStateColors(
        fill = Color(0xFFFFFFFF),
        border = Color(0xFFE5E9F0),
        text = Color(0xFF1A1F2E),
    ),
    actionStateReminded = BecalmStateColors(
        fill = Color(0xFFF2EDE7),
        border = Color(0xFFE5E9F0),
        text = Color(0xFFC0967A),
    ),
    actionStateFollowedUp = BecalmStateColors(
        fill = Color(0xFFECF0F4),
        border = Color(0xFFE5E9F0),
        text = Color(0xFF6589A1),
    ),
    actionStateCompleted = BecalmStateColors(
        fill = Color(0xFFEDF1EF),
        border = Color(0xFFE5E9F0),
        text = Color(0xFF7E948A),
    ),

    directionGive = BecalmDirectionColors(
        fill = Color(0xFFF2EDE7),
        border = Color(0xFFE5E9F0),
    ),
    directionTake = BecalmDirectionColors(
        fill = Color(0xFFECF0F4),
        border = Color(0xFFE5E9F0),
    ),

    dayBadgeToday = BecalmStateColors(
        fill = Color(0xFFF3ECEA),
        border = Color(0xFFE5E9F0),
        text = Color(0xFFBC8071),
    ),
    dayBadgeSoon = BecalmStateColors(
        fill = Color(0xFFF2EDE7),
        border = Color(0xFFE5E9F0),
        text = Color(0xFFC0967A),
    ),
    dayBadgeUpcoming = BecalmStateColors(
        fill = Color(0xFFF0F3F8),
        border = Color(0xFFE5E9F0),
        text = Color(0xFF5A6478),
    ),
    dayBadgeOverdue = BecalmStateColors(
        fill = Color(0xFFF3ECEA),
        border = Color(0xFFE5E9F0),
        text = Color(0xFFBC8071),
    ),

    sourceStatusOk = Color(0xFF7E948A),
    sourceStatusStale = Color(0xFFC0967A),
    sourceStatusError = Color(0xFFBC8071),
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
