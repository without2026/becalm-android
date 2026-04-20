package com.becalm.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.theme.glassPanel

/**
 * Account section of [SettingsScreen]. Renders the signed-in email (when present) and the
 * sign-out button. Confirmation dialog state is hoisted to the parent so this composable
 * stays state-free (rubric D1).
 *
 * Body is byte-identical with the original inlined "Account section" block.
 */
@Composable
internal fun SettingsAccountSection(
    userEmail: String?,
    onSignOutClick: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_account_section))
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        if (userEmail != null) {
            Text(
                text = userEmail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        BecalmButton(
            text = stringResource(R.string.action_sign_out),
            onClick = onSignOutClick,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
