/**
 * SP-47: Glass-styled text input field for BeCalm Android.
 *
 * Wraps Material3 [OutlinedTextField] with the glass-panel visual recipe and
 * semantic color tokens routed through [MaterialTheme.becalmColors].
 */
package com.becalm.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors
import com.becalm.android.ui.theme.glassPanel

// ─── BecalmTextField ──────────────────────────────────────────────────────────

/**
 * Single glass-styled text input that wraps [OutlinedTextField] with BeCalm's
 * cosmic glass-panel surface and semantic token colors.
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

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .glassPanel(MaterialTheme.shapes.small),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let {
            { Icon(imageVector = it, contentDescription = null) }
        },
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = supportingText?.let {
            {
                Text(
                    text = it,
                    color = if (isError) colorScheme.error else colorScheme.onSurfaceVariant,
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = imeAction,
        ),
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            // Container transparent — glass background comes from glassPanel modifier
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            // Indicator (outline) colors
            focusedBorderColor = colorScheme.primary,
            unfocusedBorderColor = becalmColors.glassBorder,
            errorBorderColor = colorScheme.error,
            disabledBorderColor = becalmColors.glassBorder,
            // Label colors
            focusedLabelColor = colorScheme.primary,
            unfocusedLabelColor = colorScheme.onSurfaceVariant,
            errorLabelColor = colorScheme.error,
            disabledLabelColor = colorScheme.onSurfaceVariant,
            // Placeholder
            focusedPlaceholderColor = colorScheme.onSurfaceVariant,
            unfocusedPlaceholderColor = colorScheme.onSurfaceVariant,
            // Text and cursor
            focusedTextColor = colorScheme.onSurface,
            unfocusedTextColor = colorScheme.onSurface,
            cursorColor = colorScheme.primary,
            errorCursorColor = colorScheme.error,
            // Leading / trailing icons
            focusedLeadingIconColor = colorScheme.onSurfaceVariant,
            unfocusedLeadingIconColor = colorScheme.onSurfaceVariant,
            errorLeadingIconColor = colorScheme.error,
            focusedTrailingIconColor = colorScheme.onSurfaceVariant,
            unfocusedTrailingIconColor = colorScheme.onSurfaceVariant,
            errorTrailingIconColor = colorScheme.error,
            // Supporting text color handled inline above
            focusedSupportingTextColor = colorScheme.onSurfaceVariant,
            unfocusedSupportingTextColor = colorScheme.onSurfaceVariant,
            errorSupportingTextColor = colorScheme.error,
        ),
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
