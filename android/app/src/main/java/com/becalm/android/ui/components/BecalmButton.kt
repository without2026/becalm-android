/**
 * SP-46: Reusable button component for BeCalm Android.
 *
 * Provides visual variants for BeCalm's CTA hierarchy through a single entry
 * point driven by [BecalmButtonVariant].
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmFocusRing
import com.becalm.android.ui.theme.dimens

// ─── Variant enum ──────────────────────────────────────────────────────────────

/**
 * Defines the visual intent hierarchy of [BecalmButton].
 *
 * - [Primary]: highest-emphasis forward action, usually one per area.
 * - [Secondary]: tonal support action such as details, evidence, or alternate flow.
 * - [Tertiary]: low-emphasis text action such as skip, later, cancel, or dismiss.
 * - [Destructive]: high-emphasis destructive action that requires clear intent.
 * - [DestructiveTertiary]: low-emphasis destructive action inside confirmation UI.
 * - [Text]: legacy alias for [Tertiary].
 */
public enum class BecalmButtonVariant {
    Primary,
    Secondary,
    Tertiary,
    Destructive,
    DestructiveTertiary,
    Text,
}

/**
 * Size presets for [BecalmButton]. Regular keeps the 48 dp app-wide touch target;
 * Compact is for dense rows and dialogs that still need a reliable tap area.
 */
public enum class BecalmButtonSize {
    Regular,
    Compact,
}

// ─── BecalmButton ─────────────────────────────────────────────────────────────

/**
 * Unified button component that renders one visual intent variant based on
 * [variant], with built-in loading and disabled states.
 *
 * @param text         Label displayed inside the button. Supply a localized string.
 * @param onClick      Invoked when the button is tapped. No-op while [loading] is true.
 * @param modifier     Optional [Modifier] applied to the outer container.
 * @param variant      Visual style matching action intent.
 * @param size         Regular or compact button density.
 * @param enabled      When `false`, the button is non-interactive and rendered at 0.38 alpha.
 * @param loading      When `true`, replaces the label with a 16 dp [CircularProgressIndicator]
 *                     and disables interaction.
 * @param leadingIcon  Optional icon drawn to the left of the label (not shown during loading).
 */
@Composable
public fun BecalmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BecalmButtonVariant = BecalmButtonVariant.Primary,
    size: BecalmButtonSize = BecalmButtonSize.Regular,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val isInteractive = enabled && !loading
    val interactionSource = remember { MutableInteractionSource() }
    val minHeight = when (size) {
        BecalmButtonSize.Regular -> MaterialTheme.dimens.buttonHeight
        BecalmButtonSize.Compact -> ButtonHeightCompact
    }
    val effectiveModifier = modifier
        .defaultMinSize(minHeight = minHeight)
        .then(if (!enabled) Modifier.alpha(0.38f) else Modifier)
        .semantics { role = Role.Button }

    when (variant) {
        BecalmButtonVariant.Primary -> {
            Button(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier
                    .becalmFocusRing(MaterialTheme.shapes.small, interactionSource),
                enabled = isInteractive,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 2.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 3.dp,
                    hoveredElevation = 3.dp,
                    disabledElevation = 0.dp,
                ),
                shape = MaterialTheme.shapes.small,
                contentPadding = buttonPadding(size, horizontal = ButtonHorizontalPaddingFilled),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading, size = size)
            }
        }

        BecalmButtonVariant.Secondary -> {
            val shape = MaterialTheme.shapes.small
            Button(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, shape)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
                    .becalmFocusRing(shape, interactionSource),
                enabled = isInteractive,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 1.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 2.dp,
                    hoveredElevation = 2.dp,
                    disabledElevation = 0.dp,
                ),
                shape = MaterialTheme.shapes.small,
                contentPadding = buttonPadding(size, horizontal = ButtonHorizontalPaddingFilled),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading, size = size)
            }
        }

        BecalmButtonVariant.Destructive -> {
            val shape = MaterialTheme.shapes.small
            Button(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier
                    .background(MaterialTheme.colorScheme.error, shape)
                    .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.30f), shape)
                    .becalmFocusRing(shape, interactionSource),
                enabled = isInteractive,
                interactionSource = interactionSource,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    disabledContainerColor = MaterialTheme.colorScheme.error,
                    disabledContentColor = MaterialTheme.colorScheme.onError,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 1.dp,
                    pressedElevation = 0.dp,
                    focusedElevation = 2.dp,
                    hoveredElevation = 2.dp,
                    disabledElevation = 0.dp,
                ),
                shape = shape,
                contentPadding = buttonPadding(size, horizontal = ButtonHorizontalPaddingFilled),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading, size = size)
            }
        }

        BecalmButtonVariant.Tertiary,
        BecalmButtonVariant.DestructiveTertiary,
        BecalmButtonVariant.Text -> {
            val contentColor = if (variant == BecalmButtonVariant.DestructiveTertiary) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            TextButton(
                onClick = { if (isInteractive) onClick() },
                modifier = effectiveModifier
                    .becalmFocusRing(MaterialTheme.shapes.small, interactionSource),
                enabled = isInteractive,
                interactionSource = interactionSource,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = contentColor,
                    disabledContentColor = contentColor,
                ),
                contentPadding = buttonPadding(size, horizontal = ButtonHorizontalPaddingText),
            ) {
                ButtonContent(text = text, leadingIcon = leadingIcon, loading = loading, size = size)
            }
        }
    }
}

// ─── Private constants ────────────────────────────────────────────────────────

private val ButtonHeightCompact = 40.dp
private val ButtonHorizontalPaddingFilled = 24.dp
private val ButtonHorizontalPaddingText = 12.dp
private val ButtonHorizontalPaddingCompactDelta = 6.dp
private val ButtonLoadingIndicatorSize = 16.dp
private val ButtonLeadingIconSize = 18.dp
private val ButtonLeadingIconSpacing = 8.dp

// ─── Private helpers ──────────────────────────────────────────────────────────

private fun buttonPadding(size: BecalmButtonSize, horizontal: Dp): PaddingValues {
    val resolvedHorizontal = when (size) {
        BecalmButtonSize.Regular -> horizontal
        BecalmButtonSize.Compact -> (horizontal - ButtonHorizontalPaddingCompactDelta)
            .coerceAtLeast(ButtonHorizontalPaddingText)
    }
    return PaddingValues(horizontal = resolvedHorizontal, vertical = 0.dp)
}

@Composable
private fun ButtonContent(
    text: String,
    leadingIcon: ImageVector?,
    loading: Boolean,
    size: BecalmButtonSize,
) {
    val labelStyle = when (size) {
        BecalmButtonSize.Regular -> MaterialTheme.typography.labelLarge
        BecalmButtonSize.Compact -> MaterialTheme.typography.labelMedium
    }
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(ButtonLoadingIndicatorSize),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(ButtonLeadingIconSpacing))
            Text(
                text = text,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonLeadingIconSize),
                )
                Spacer(modifier = Modifier.width(ButtonLeadingIconSpacing))
            }
            Text(
                text = text,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonPrimary() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Confirm", onClick = {}, variant = BecalmButtonVariant.Primary)
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonSecondary() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Cancel", onClick = {}, variant = BecalmButtonVariant.Secondary)
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonText() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Skip", onClick = {}, variant = BecalmButtonVariant.Tertiary)
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonDestructive() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Delete", onClick = {}, variant = BecalmButtonVariant.Destructive)
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmButtonCompact() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(
                text = "Later",
                onClick = {},
                variant = BecalmButtonVariant.Tertiary,
                size = BecalmButtonSize.Compact,
            )
        }
    }
}

@Preview
@Composable
private fun PreviewBecalmButtonLoading() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Loading", onClick = {}, loading = true)
        }
    }
}

@Preview
@Composable
private fun PreviewBecalmButtonDisabled() {
    BecalmTheme {
        Box(contentAlignment = Alignment.Center) {
            BecalmButton(text = "Disabled", onClick = {}, enabled = false)
        }
    }
}
