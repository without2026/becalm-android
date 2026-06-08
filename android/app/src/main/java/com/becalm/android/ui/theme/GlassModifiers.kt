/**
 * Modifier extension functions for BeCalm's ink-mist panels.
 *
 * The current prototype uses white panels, cool hairline borders, restrained
 * shadows, and only a subtle elevated-sheet highlight. The historical "glass"
 * function names remain because many UI call sites already use them as common
 * surface recipes.
 *
 * The recipes:
 *   1. [glassPanel]         — relationship cards and list items (18 dp corners)
 *   2. [glassPanelElevated] — modals and bottom sheets (24 dp corners)
 *
 * Source of truth: `becalm-v4-ux-inkmist-soft.html`.
 */
package com.becalm.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ─── Internal helpers ─────────────────────────────────────────────────────────

/**
 * Draws a soft outer drop-shadow behind the composable by painting a blurred
 * filled rounded-rect using [drawWithCache].
 *
 * Compose has no direct `box-shadow` equivalent, so we approximate it by
 * painting a semi-transparent filled shape at [yOffset] behind the content.
 * The visual result is subtler than CSS box-shadow but preserves the depth cue
 * needed for the elevated panel aesthetic.
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
): Modifier = this.drawWithCache {
    val blurPx = blur.toPx()
    val yOffsetPx = yOffset.toPx()
    val radiusPx = cornerRadius.toPx()
    val shadowTopLeft = Offset(-blurPx, -blurPx + yOffsetPx)
    val shadowSize = Size(
        width = size.width + blurPx * 2,
        height = size.height + blurPx * 2,
    )
    val shadowCornerRadius = CornerRadius(radiusPx + blurPx, radiusPx + blurPx)

    onDrawBehind {
        drawRoundRect(
            color = shadowColor,
            topLeft = shadowTopLeft,
            size = shadowSize,
            cornerRadius = shadowCornerRadius,
            alpha = shadowColor.alpha,
        )
    }
}

/**
 * Draws a 1 dp inset highlight along the top edge of the composable.  This
 * mimics the CSS `inset 0 1px 0 rgba(255,255,255,0.05)` inner-shadow convention.
 */
private fun Modifier.glassInsetHighlight(
    highlightColor: Color,
    cornerRadius: Dp,
): Modifier = this.drawWithCache {
    val radiusPx = cornerRadius.toPx()
    val lineHeightPx = 1.dp.toPx()
    val highlightSize = Size(
        width = size.width,
        height = lineHeightPx,
    )

    onDrawBehind {
        drawRoundRect(
            color = highlightColor,
            topLeft = Offset.Zero,
            size = highlightSize,
            cornerRadius = CornerRadius(radiusPx, radiusPx),
        )
    }
}

// ─── Public recipes ───────────────────────────────────────────────────────────

/**
 * Default ink-mist surface recipe — cards and list items.
 *
 * Property stack:
 * - Background fill: white app surface
 * - Border: 1 dp cool grey hairline
 * - Corner radius: 18 dp (matches [BecalmShapes.medium])
 * - Outer shadow: very subtle, matching the prototype's flat card stack
 *
 * @param shape Override shape; defaults to [MaterialTheme.shapes.medium] (18 dp rounded).
 */
@Composable
public fun Modifier.glassPanel(shape: Shape = MaterialTheme.shapes.medium): Modifier {
    val colors = MaterialTheme.becalmColors
    val fill = colors.glassPanelFill
    val cornerRadius = 18.dp

    return this
        .glassShadow(
            shadowColor = colors.glassOuterShadow,
            cornerRadius = cornerRadius,
            yOffset = 2.dp,
            blur = 10.dp,
        )
        .background(fill, shape)
        .border(1.dp, colors.glassBorder, shape)
}

/**
 * Elevated ink-mist surface recipe — modals and bottom sheets.
 *
 * Property stack:
 * - Background fill: white app surface
 * - Border: 1 dp cool grey hairline
 * - Corner radius: 24 dp (matches [BecalmShapes.large])
 * - Outer shadow: moderate bottom-sheet depth
 * - Inset highlight: 1 dp top edge
 *
 * @param shape Override shape; defaults to [MaterialTheme.shapes.large] (24 dp rounded).
 */
@Composable
public fun Modifier.glassPanelElevated(shape: Shape = MaterialTheme.shapes.large): Modifier {
    val colors = MaterialTheme.becalmColors
    val fill = colors.glassPanelFillElevated
    val cornerRadius = 24.dp

    return this
        .glassShadow(
            shadowColor = colors.glassOuterShadowElevated,
            cornerRadius = cornerRadius,
            yOffset = 8.dp,
            blur = 24.dp,
        )
        .glassInsetHighlight(
            highlightColor = colors.glassInsetElevated,
            cornerRadius = cornerRadius,
        )
        .background(fill, shape)
        .border(1.dp, colors.glassBorder, shape)
}
