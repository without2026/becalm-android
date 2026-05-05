/**
 * Focus-state modifiers for BeCalm Android.
 *
 * The project's frosted glass surfaces need a visible keyboard focus state
 * beyond Material3's ripple. [becalmFocusRing] overlays an explicit 2dp warm
 * accent border so the Focus Visible WCAG 2.4.7 contract holds across light
 * glass and the dark alternate.
 *
 * Apply to the OUTER modifier of the interactive composable and thread the
 * same [InteractionSource] to the underlying primitive (Button / TextButton /
 * IconButton / NavigationBarItem) so focus state is shared rather than
 * duplicated.
 */
package com.becalm.android.ui.theme

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
public fun Modifier.becalmFocusRing(
    shape: Shape,
    interactionSource: InteractionSource,
): Modifier {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val ringColor = MaterialTheme.colorScheme.tertiary
    return if (isFocused) {
        this.border(2.dp, ringColor, shape)
    } else {
        this
    }
}
