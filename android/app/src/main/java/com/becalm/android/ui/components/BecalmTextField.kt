/**
 * SP-47: Glass-styled text input field for BeCalm Android.
 *
 * Wraps Material3 [OutlinedTextField] with a warm glass field surface and
 * semantic color tokens routed through [MaterialTheme.becalmColors].
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors

// ─── BecalmTextField ──────────────────────────────────────────────────────────

/**
 * Single glass-styled text input that wraps [BasicTextField] with BeCalm's
 * single-outline field surface and semantic token colors.
 *
 * @param value               Current text value.
 * @param onValueChange       Callback invoked on each text change.
 * @param modifier            Optional [Modifier] applied to the outer container.
 *                            The caller is responsible for width — typically
 *                            `Modifier.fillMaxWidth()` in a form layout.
 * @param label               Floating label text, or `null` to omit.
 * @param placeholder         Hint text shown when [value] is empty, or `null` to omit.
 * @param leadingIcon         Optional icon displayed at the start of the field.
 * @param trailingIcon        Optional composable slot for a trailing icon or button.
 * @param isError             When `true`, indicator and supporting text switch to error color.
 * @param supportingText      Helper or error message shown below the field, or `null` to omit.
 * @param keyboardType        Software keyboard layout; defaults to [KeyboardType.Text].
 * @param imeAction           IME action button; defaults to [ImeAction.Default].
 * @param singleLine          When `true`, the field does not wrap to multiple lines.
 * @param enabled             When `false`, the field is non-interactive and visually dimmed.
 * @param visualTransformation Transforms the visible text. Callers rendering passwords or
 *                            other sensitive personal data MUST pass
 *                            `PasswordVisualTransformation()` and SHOULD also set
 *                            `WindowManager.LayoutParams.FLAG_SECURE` on the hosting
 *                            Activity per PIPA Article 29.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun BecalmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val becalmColors = MaterialTheme.becalmColors
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fieldShape = MaterialTheme.shapes.small
    val borderColor = when {
        isError -> colorScheme.error
        focused -> colorScheme.primary
        else -> becalmColors.glassBorder
    }
    val textColor = if (enabled) colorScheme.onSurface else colorScheme.onSurfaceVariant
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent,
        errorBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedLabelColor = colorScheme.primary,
        unfocusedLabelColor = colorScheme.onSurfaceVariant,
        errorLabelColor = colorScheme.error,
        disabledLabelColor = colorScheme.onSurfaceVariant,
        focusedPlaceholderColor = colorScheme.onSurfaceVariant,
        unfocusedPlaceholderColor = colorScheme.onSurfaceVariant,
        focusedTextColor = colorScheme.onSurface,
        unfocusedTextColor = colorScheme.onSurface,
        cursorColor = colorScheme.primary,
        errorCursorColor = colorScheme.error,
        focusedLeadingIconColor = colorScheme.onSurfaceVariant,
        unfocusedLeadingIconColor = colorScheme.onSurfaceVariant,
        errorLeadingIconColor = colorScheme.error,
        focusedTrailingIconColor = colorScheme.onSurfaceVariant,
        unfocusedTrailingIconColor = colorScheme.onSurfaceVariant,
        errorTrailingIconColor = colorScheme.error,
        focusedSupportingTextColor = colorScheme.onSurfaceVariant,
        unfocusedSupportingTextColor = colorScheme.onSurfaceVariant,
        errorSupportingTextColor = colorScheme.error,
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        cursorBrush = SolidColor(if (isError) colorScheme.error else colorScheme.primary),
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                interactionSource = interactionSource,
                isError = isError,
                label = label?.let { { Text(it) } },
                placeholder = placeholder?.let { { Text(it) } },
                leadingIcon = leadingIcon?.let {
                    { Icon(imageVector = it, contentDescription = null) }
                },
                trailingIcon = trailingIcon,
                supportingText = supportingText?.let {
                    {
                        Text(
                            text = it,
                            color = if (isError) colorScheme.error else colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = fieldColors,
                container = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.dp, borderColor, fieldShape),
                    )
                },
            )
        },
    )
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewBecalmTextFieldEmpty() {
    BecalmTheme {
        Box {
            BecalmTextField(
                value = "",
                onValueChange = {},
                label = "Email address",
                placeholder = "name@example.com",
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBecalmTextFieldErrorWithSupporting() {
    BecalmTheme {
        Box {
            BecalmTextField(
                value = "bad-input",
                onValueChange = {},
                label = "Password",
                isError = true,
                supportingText = "Must be at least 8 characters.",
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

@Preview
@Composable
private fun PreviewBecalmTextFieldWithLeadingIcon() {
    BecalmTheme {
        Box {
            BecalmTextField(
                value = "name@example.com",
                onValueChange = {},
                label = "Email",
                leadingIcon = Icons.Filled.Search,
            )
        }
    }
}
