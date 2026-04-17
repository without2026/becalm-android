/**
 * Material3 shape tokens for BeCalm Android.
 *
 * BeCalm follows a rounded-corner aesthetic inherited from v3 desktop (`.card`
 * used 24 dp corner radius).  The values are mapped to Android One UI conventions
 * while preserving the rounded feel — see design token spec §6.
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
 * | extraSmall    | 4 dp   | Badges, D-N pills                                  |
 * | small         | 8 dp   | Buttons, inputs, nav items (v3 `.nav-button`)      |
 * | medium        | 12 dp  | Cards, list items — matches [glassPanel] recipe    |
 * | large         | 20 dp  | Bottom sheets, modals — [glassPanelElevated]       |
 * | extraLarge    | 28 dp  | Full-screen surfaces, ColdSyncScreen container     |
 */
internal val BecalmShapes = Shapes(
    /** Badges, D-N urgency pills. */
    extraSmall = RoundedCornerShape(4.dp),
    /** Buttons, text inputs, navigation items. Matches v3 `.nav-button { border-radius: 8px }`. */
    small = RoundedCornerShape(8.dp),
    /** Default card / list-item shape. Matches [glassPanel] recipe corner radius. */
    medium = RoundedCornerShape(12.dp),
    /** Bottom sheets and modal dialogs. Matches [glassPanelElevated] recipe.
     *  v3 used 24 dp on `.card`; reduced to 20 dp for Android bottom-sheet convention. */
    large = RoundedCornerShape(20.dp),
    /** Full-screen container surfaces, e.g. ColdSyncScreen. */
    extraLarge = RoundedCornerShape(28.dp),
)
