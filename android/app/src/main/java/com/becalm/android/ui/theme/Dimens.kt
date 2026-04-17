/**
 * Spacing and component dimension tokens for BeCalm Android.
 *
 * All values follow an 8 dp grid inherited from v3 desktop
 * (`--panel-padding: 24px`, nav `gap: 12px`, card `border-radius: 24px`).
 * Mobile-specific adjustments are noted inline (e.g. [panelPaddingHorizontal]
 * is 16 dp rather than v3's 24 dp to fit narrower screens).
 *
 * Consume via [MaterialTheme.dimens] extension property, backed by
 * [LocalBecalmDimens] CompositionLocal. The theme provides the default instance
 * through [BecalmTheme].
 *
 * Source of truth: design token spec §5.
 */
package com.becalm.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Immutable bag of all spacing and component-slot dimension tokens.
 *
 * Defined as a data class rather than an object so that specific screens can
 * override individual values via `CompositionLocalProvider` without forking the
 * entire token set (useful for compact/expanded window-size classes in future).
 */
@Immutable
public data class BecalmDimens(

    // ── Base spacing scale ────────────────────────────────────────────────────
    /** 2 dp — icon-to-label micro gap. */
    val spacingXxs: Dp,
    /** 4 dp — badge internal padding. */
    val spacingXs: Dp,
    /** 8 dp — between list items, inner chip padding. */
    val spacingS: Dp,
    /** 12 dp — navigation item gap. v3 `gap: 12px`. */
    val spacingM: Dp,
    /** 16 dp — card internal padding, input padding. */
    val spacingL: Dp,
    /** 24 dp — panel padding. v3 `.sidebar { padding: 24px }`. */
    val spacingXl: Dp,
    /** 32 dp — section separation. */
    val spacingXxl: Dp,

    // ── Icon sizes ────────────────────────────────────────────────────────────
    /** 16 dp — status dots, inline icons. */
    val iconSizeSmall: Dp,
    /** 24 dp — tab bar icons, action icons. */
    val iconSizeMedium: Dp,
    /** 32 dp — feature icons on empty states. */
    val iconSizeLarge: Dp,

    // ── Component slots ───────────────────────────────────────────────────────
    /** 36 dp — person_ref avatar (initials circle). */
    val avatarSize: Dp,
    /** 28 dp — filter tabs (전체/내가 한/상대가 한), source status chips. */
    val chipHeight: Dp,
    /** 12 dp — [glassPanel] default corner radius (mirrors [BecalmShapes.medium]). */
    val cardCornerRadius: Dp,
    /** 20 dp — [glassPanelElevated] corner radius (mirrors [BecalmShapes.large]). */
    val cardCornerRadiusLarge: Dp,
    /** 48 dp — primary / ghost buttons. One UI touch-target minimum. */
    val buttonHeight: Dp,
    /** 8 dp — button corner radius. v3 `.nav-button { border-radius: 8px }`. */
    val buttonCornerRadius: Dp,
    /** 52 dp — text input fields. */
    val inputHeight: Dp,
    /** 8 dp — input corner radius. v3 `input { border-radius: 8px }`. */
    val inputCornerRadius: Dp,
    /** 64 dp — bottom navigation bar. */
    val bottomNavHeight: Dp,
    /** 16 dp — screen edge horizontal padding.
     *  Tighter than v3 desktop 24 dp to fit mobile viewport. */
    val panelPaddingHorizontal: Dp,
    /** 12 dp — panel top/bottom internal padding. */
    val panelPaddingVertical: Dp,
    /** 44 dp — SourceStatusStrip chip row height (TDY-003). */
    val sourceStatusStripHeight: Dp,
)

// ─── Default instance ─────────────────────────────────────────────────────────

/** Default dimension values populated directly from design token spec §5. */
public val BecalmDimensDefault: BecalmDimens = BecalmDimens(
    spacingXxs = 2.dp,
    spacingXs = 4.dp,
    spacingS = 8.dp,
    spacingM = 12.dp,
    spacingL = 16.dp,
    spacingXl = 24.dp,
    spacingXxl = 32.dp,
    iconSizeSmall = 16.dp,
    iconSizeMedium = 24.dp,
    iconSizeLarge = 32.dp,
    avatarSize = 36.dp,
    chipHeight = 28.dp,
    cardCornerRadius = 12.dp,
    cardCornerRadiusLarge = 20.dp,
    buttonHeight = 48.dp,
    buttonCornerRadius = 8.dp,
    inputHeight = 52.dp,
    inputCornerRadius = 8.dp,
    bottomNavHeight = 64.dp,
    panelPaddingHorizontal = 16.dp,
    panelPaddingVertical = 12.dp,
    sourceStatusStripHeight = 44.dp,
)

// ─── CompositionLocal ─────────────────────────────────────────────────────────

/**
 * Provides the current [BecalmDimens] instance down the composition tree.
 * [BecalmTheme] supplies [BecalmDimensDefault] via `CompositionLocalProvider`.
 */
public val LocalBecalmDimens: ProvidableCompositionLocal<BecalmDimens> =
    staticCompositionLocalOf { BecalmDimensDefault }

// ─── MaterialTheme extension ──────────────────────────────────────────────────

/**
 * Convenience accessor — retrieve [BecalmDimens] from anywhere inside [BecalmTheme].
 *
 * Usage:
 * ```kotlin
 * val horizontalPad = MaterialTheme.dimens.panelPaddingHorizontal
 * ```
 */
public val MaterialTheme.dimens: BecalmDimens
    @Composable
    @ReadOnlyComposable
    get() = LocalBecalmDimens.current
