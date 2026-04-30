/**
 * Reduced-motion preference reader for Compose composables.
 *
 * Android exposes user-controlled animation scaling via Settings.Global —
 * accessibility users (and developers using the "remove animations" toggle)
 * set ANIMATOR_DURATION_SCALE / TRANSITION_ANIMATION_SCALE to 0. Compose
 * does not respect this automatically; consumers opt in by reading
 * [rememberReducedMotion] and either skipping the animation or running it
 * with `tween(durationMillis = 0)`.
 *
 * DESIGN.md commits to respecting reduced-motion. This helper centralizes
 * the lookup so callers don't repeat the Settings.Global plumbing.
 */
package com.becalm.android.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * `true` when the device has system-level animations disabled.
 *
 * Reads `Settings.Global.ANIMATOR_DURATION_SCALE` once per Context — the
 * value rarely changes within a single composition tree, so a lifecycle
 * listener is unnecessary for a property that is queried at composition.
 * Consumers needing live updates should observe the setting through their
 * own ContentObserver and pass the result in.
 */
@Composable
public fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
