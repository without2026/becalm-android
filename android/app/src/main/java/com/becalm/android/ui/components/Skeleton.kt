/**
 * Static placeholder primitives for cold-start surfaces.
 *
 * Skeletons replace the legacy center-screen [CircularProgressIndicator] on
 * first-line and detail surfaces (DESIGN.md Process-Hidden Rule). Each screen
 * composes its own row geometry from [SkeletonBlock] so real data lands in
 * place without layout pop. No motion: a "loading shimmer" reads as process
 * noise on a calm interface and would also bypass `prefers-reduced-motion`.
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Subtle visible color used for cold-start placeholder blocks. `onSurfaceVariant`
 * (muted-silver on dark, mid-gray on light) at α=0.14 reads as "loading"
 * over the cosmic-near-black ground without the process noise of motion.
 *
 * Earlier iterations used `outlineVariant` at α=0.32 — that token is `#1E1E1E`
 * on dark, near-invisible against the cosmic background. The fix is captured
 * in commit `10f73e3`; this helper is the canonical source so future skeletons
 * cannot regress to the wrong token.
 */
@Composable
@ReadOnlyComposable
public fun becalmSkeletonColor(): Color =
    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)

/**
 * Rounded placeholder rectangle used to compose row skeletons. The caller
 * controls width and height through [modifier] (typically `fillMaxWidth(0.4f)`
 * or an explicit `width(Dp).height(Dp)`).
 */
@Composable
public fun SkeletonBlock(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
) {
    Box(
        modifier = modifier
            .background(becalmSkeletonColor(), RoundedCornerShape(cornerRadius)),
    )
}
