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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

/**
 * Generic detail / sheet skeleton used by cold-start of bottom sheets,
 * detail screens, and settings panes. Renders a title block and three body
 * lines stacked at 16dp gutter — a neutral "the content is on its way"
 * shape that fits any title-plus-body destination without coupling to a
 * specific row geometry.
 *
 * For first-line list surfaces (Today / Persons / Commitments) compose
 * row-shaped skeletons inline using [SkeletonBlock] instead — this pane
 * is for non-list destinations.
 */
@Composable
public fun BecalmSheetSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.65f).height(20.dp))
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth().height(14.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.92f).height(14.dp))
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.45f).height(14.dp))
    }
}
