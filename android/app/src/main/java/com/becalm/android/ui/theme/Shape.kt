/**
 * Material3 shape tokens for BeCalm Android.
 *
 * BeCalm follows the light frosted system in DESIGN.md: pill chips, 12 dp
 * controls, 20 dp relationship cards, and 28 dp sheets.
 *
 * `surfaceTint = Color.Transparent` on the color scheme (see Color.kt) means
 * M3 tonal elevation surfaces will not be tinted, so shape radius is the sole
 * differentiator of surface depth.
 */
package com.becalm.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * BeCalm shape scale.
 *
 * | M3 slot       | Radius | Applied to                                         |
 * |---------------|--------|----------------------------------------------------|
 * | extraSmall    | 999 dp | Chips and small source/status pills                |
 * | small         | 12 dp  | Buttons, inputs, nav items                         |
 * | medium        | 20 dp  | Cards, list items, relationship panels             |
 * | large         | 28 dp  | Bottom sheets, modals, approval panels             |
 * | extraLarge    | 32 dp  | Full-screen focused surfaces                       |
 */
internal val BecalmShapes = Shapes(
    extraSmall = RoundedCornerShape(999.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
