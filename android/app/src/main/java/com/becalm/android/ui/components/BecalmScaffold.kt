/**
 * SP-51: Root scaffold wrapper for BeCalm Android screens.
 *
 * Provides the warm frosted canvas, subtle ambient wash, and a centered
 * [TopAppBar] pattern that all screens in the app share. Delegates all slot
 * forwarding (bottom bar, FAB, snackbar) to Material3 [Scaffold].
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors

// ─── BecalmScaffold ───────────────────────────────────────────────────────────

/**
 * Root scaffold that composes the warm background canvas, top-half ambient
 * wash, and a transparent [CenterAlignedTopAppBar] over a Material3
 * [Scaffold] with a transparent container.
 *
 * The ambient wash is drawn with [drawWithCache] using a radial gradient from
 * [becalmColors.ambientGlowCore] to [becalmColors.ambientGlowEdge], centered at
 * the top-half of the screen.
 *
 * @param title           Title string shown in the centered top app bar.
 * @param modifier        Optional [Modifier] applied to the root [Box].
 * @param navigationIcon  Optional back/up navigation icon composable shown at the
 *                        start of the top bar. Pass `null` to omit.
 * @param actions         Action icons composable for the end of the top bar.
 *                        Defaults to an empty slot.
 * @param bottomBar       Bottom navigation bar slot. Defaults to empty.
 * @param snackbarHost    Snackbar host slot. Defaults to empty.
 * @param floatingActionButton FAB slot. Defaults to empty.
 * @param content         Screen content, receives [PaddingValues] from the inner
 *                        [Scaffold] to respect top/bottom bar insets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BecalmScaffold(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val becalmColors = MaterialTheme.becalmColors
    val glowCore = becalmColors.ambientGlowCore
    val glowEdge = becalmColors.ambientGlowEdge
    val bgColor = becalmColors.canvasBackground
    val onSurface = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                // Build the radial-gradient brush once per layout-size change
                // (drawWithCache re-runs only when [size] or input state
                // invalidates) instead of allocating a fresh Brush every frame
                // inside the draw scope.
                val centerX = size.width / 2f
                val centerY = -size.height * 0.18f
                val glowRadius = size.width * 1.05f
                val ambientBrush = Brush.radialGradient(
                    colors = listOf(glowCore, glowEdge),
                    center = Offset(centerX, centerY),
                    radius = glowRadius,
                )
                onDrawBehind {
                    drawRect(color = bgColor)
                    drawCircle(
                        brush = ambientBrush,
                        radius = glowRadius,
                        center = Offset(centerX, centerY),
                    )
                }
            },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentColor = onSurface,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = navigationIcon ?: {},
                    actions = actions,
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = onSurface,
                        actionIconContentColor = onSurface,
                        navigationIconContentColor = onSurface,
                    ),
                )
            },
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            content = content,
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewBecalmScaffold() {
    BecalmTheme {
        BecalmScaffold(
            title = "Commitments",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                    )
                }
            },
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                Text(
                    text = "Screen content goes here",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
