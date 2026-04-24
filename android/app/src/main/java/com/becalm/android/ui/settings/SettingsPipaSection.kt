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
import com.becalm.android.ui.theme.glassPanel

/**
 * Preferences section of [SettingsScreen] — notifications toggle plus the VOI-004
 * PIPA 제3자 제공 동의 toggle. Toggle dialogs (PIPA enable / disable) are hoisted to the
 * parent because they require its dialog-visibility state.
 *
 * The PIPA disclosure helpers ([PipaDisclosureList] / [SettingsPipaDisclosureBullet])
 * live here because they are only consumed by the PIPA enable dialog rendered above
 * this section.
 *
 * Body is byte-identical with the original inlined "Preferences section" block.
 */
@Composable
internal fun SettingsPipaSection(
    notificationsEnabled: Boolean,
    pipaConsentEnabled: Boolean,
    onToggleNotifications: (Boolean) -> Unit,
    onTogglePipa: (Boolean) -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_preferences_section))
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SettingsToggleRow(
            label = stringResource(R.string.settings_notifications_label),
            checked = notificationsEnabled,
            onCheckedChange = onToggleNotifications,
            toggleTestTag = "settings-notifications-toggle",
        )
        SettingsToggleRow(
            label = stringResource(R.string.settings_pipa_toggle_label),
            checked = pipaConsentEnabled,
            onCheckedChange = onTogglePipa,
            toggleTestTag = "settings-pipa-toggle",
        )
    }
}

// ── PIPA disclosure bullets (reused in Settings consent dialog) ───────────────
// Pulled into a private helper to avoid duplicating the glass-panel layout
// between PipaThirdPartyConsentScreen and this confirm dialog.
// The six string resource keys match the ONB-PIPA spec disclosure items exactly.

@Composable
internal fun PipaDisclosureList() {
    val bullets = listOf(
        R.string.onb_pipa_bullet_1_label to R.string.onb_pipa_bullet_1_value,
        R.string.onb_pipa_bullet_2_label to R.string.onb_pipa_bullet_2_value,
        R.string.onb_pipa_bullet_3_label to R.string.onb_pipa_bullet_3_value,
        R.string.onb_pipa_bullet_4_label to R.string.onb_pipa_bullet_4_value,
        R.string.onb_pipa_bullet_5_label to R.string.onb_pipa_bullet_5_value,
        R.string.onb_pipa_bullet_6_label to R.string.onb_pipa_bullet_6_value,
    )
    Column {
        bullets.forEachIndexed { index, (labelRes, valueRes) ->
            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
            SettingsPipaDisclosureBullet(
                label = stringResource(labelRes),
                value = stringResource(valueRes),
            )
        }
    }
}

@Composable
private fun SettingsPipaDisclosureBullet(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
