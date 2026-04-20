/**
 * Modifier extension functions that replicate BeCalm v3's cosmic
 * glassmorphism panel recipes in Jetpack Compose.
 *
 * Each recipe is a precise port of a CSS pattern from v3 global.css:
 *   - translucent fill + hairline border + outer drop-shadow + inset highlight
 *
 * Blur implementation:
 *   - API 31+ (Android 12+): [Modifier.blur] with [BlurredEdgeTreatment.Unbounded]
 *   - SDK 28–30 fallback: blur is omitted; background fill alpha is raised to
 *     partially compensate for the absent backdrop effect (spec §3 fallback values).
 *
 * The recipes:
 *   1. [glassPanel]         — default cards and list items (12 dp corners)
 *   2. [glassPanelElevated] — modals and bottom sheets (20 dp corners)
 *
 * Source of truth: design token spec §3.
 */
package com.becalm.android.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─── Internal helpers ─────────────────────────────────────────────────────────

/**
 * True when the device supports [Modifier.blur] (renderEffect-backed, API 31+).
 * The property is evaluated at call-site, not stored, to survive configuration
 * changes correctly.
 */
private val canBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Draws a soft outer drop-shadow behind the composable by painting a blurred
 * filled rounded-rect using [drawBehind].
 *
 * Compose has no direct `box-shadow` equivalent, so we approximate it by
 * painting a semi-transparent filled shape at [yOffset] behind the content.
 * The visual result is subtler than CSS box-shadow but preserves the depth cue
 * needed for the floating-glass aesthetic.
 *
 * @param shadowColor  ARGB color of the shadow (includes desired opacity).
 * @param cornerRadius Corner radius that matches the panel shape.
 * @param yOffset      Downward shift of the shadow.
 * @param blur         Conceptual blur spread — implemented as horizontal/vertical
 *                     expansion of the shadow rect beyond the composable bounds.
 */
private fun Modifier.glassShadow(
    shadowColor: Color,
    cornerRadius: Dp,
    yOffset: Dp = 4.dp,
    blur: Dp = 24.dp,
): Modifier = this.drawBehind {
    val blurPx = blur.toPx()
    val yOffsetPx = yOffset.toPx()
    val radiusPx = cornerRadius.toPx()

    // Expand the shadow rect by the blur radius on all sides, then shift down.
    drawRoundRect(
        color = shadowColor,
        topLeft = Offset(-blurPx, -blurPx + yOffsetPx),
        size = androidx.compose.ui.geometry.Size(
            width = size.width + blurPx * 2,
            height = size.height + blurPx * 2,
        ),
        cornerRadius = CornerRadius(radiusPx + blurPx, radiusPx + blurPx),
        alpha = shadowColor.alpha,
    )
}

/**
 * Draws a 1 dp inset highlight along the top edge of the composable.  This
 * mimics the CSS `inset 0 1px 0 rgba(255,255,255,0.05)` inner-shadow convention
 * used in v3's glass recipe.
 */
private fun Modifier.glassInsetHighlight(
    highlightColor: Color,
    cornerRadius: Dp,
): Modifier = this.drawBehind {
    val radiusPx = cornerRadius.toPx()
    val lineHeightPx = 1.dp.toPx()

    drawRoundRect(
        color = highlightColor,
        topLeft = Offset(0f, 0f),
        size = androidx.compose.ui.geometry.Size(
            width = size.width,
            height = lineHeightPx,
        ),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
    )
}

// ─── Public recipes ───────────────────────────────────────────────────────────

/**
 * Default glass surface recipe — cards and list items.
 *
 * Property stack (spec §3 `glassPanel`):
 * - Background fill: `0x0AFFFFFF` (α=0.04); SDK 28–30 raises to `0x1AFFFFFF` (α=0.10)
 * - Border: 1 dp `0x14FFFFFF` (α=0.08)
 * - Corner radius: 12 dp (matches [BecalmShapes.medium])
 * - Outer shadow: y-offset 4 dp, blur 24 dp, `rgba(0,0,0,0.30)`
 * - Blur: 10 dp (API 31+ only; omitted on SDK 28–30)
 *
 * @param shape Override shape; defaults to [MaterialTheme.shapes.medium] (12 dp rounded).
 */
@Composable
public fun Modifier.glassPanel(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val colors = MaterialTheme.becalmColors
    val fill = if (canBlur) colors.glassPanelFill else colors.glassPanelFillSdkLegacy
    val cornerRadius = 12.dp

    return this
        .glassShadow(
            shadowColor = colors.glassOuterShadow,
            cornerRadius = cornerRadius,
            yOffset = 4.dp,
            blur = 24.dp,
        )
        .then(
            if (canBlur) Modifier.blur(10.dp, BlurredEdgeTreatment.Unbounded) else Modifier
        )
        .background(fill, shape)
        .border(1.dp, colors.glassBorder, shape)
}

/**
 * Elevated glass surface recipe — modals and bottom sheets.
 *
 * Property stack (spec §3 `glassPanelElevated`):
 * - Background fill: `0x0FFFFFFF` (α=0.06); SDK 28–30 raises to `0x26FFFFFF` (α=0.15)
 * - Border: 1 dp `0x14FFFFFF` (α=0.08)
 * - Corner radius: 20 dp (matches [BecalmShapes.large])
 * - Outer shadow: y-offset 8 dp, blur 32 dp, `rgba(0,0,0,0.40)`
 * - Inset highlight: 1 dp top-edge `0x0DFFFFFF` (α=0.05)
 * - Blur: 14 dp (API 31+ only; omitted on SDK 28–30)
 *
 * @param shape Override shape; defaults to [MaterialTheme.shapes.large] (20 dp rounded).
 */
@Composable
public fun Modifier.glassPanelElevated(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val colors = MaterialTheme.becalmColors
    val fill = if (canBlur) colors.glassPanelFillElevated else colors.glassPanelFillElevatedLegacy
    val cornerRadius = 20.dp

    return this
        .glassShadow(
            shadowColor = colors.glassOuterShadowElevated,
            cornerRadius = cornerRadius,
            yOffset = 8.dp,
            blur = 32.dp,
        )
        .glassInsetHighlight(
            highlightColor = colors.glassInsetElevated,
            cornerRadius = cornerRadius,
        )
        .then(
            if (canBlur) Modifier.blur(14.dp, BlurredEdgeTreatment.Unbounded) else Modifier
        )
        .background(fill, shape)
        .border(1.dp, colors.glassBorder, shape)
}

