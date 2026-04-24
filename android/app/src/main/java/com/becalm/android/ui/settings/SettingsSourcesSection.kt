package com.becalm.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.theme.glassPanel

/**
 * Data section of [SettingsScreen]. Renders the navigation row to the per-source list
 * (where SMG-001 6-source list + 연락처 pseudo-source row live) and the wipe button.
 * The wipe confirmation dialog is hoisted to the parent.
 *
 * Body is byte-identical with the original inlined "Data section" block.
 */
@Composable
internal fun SettingsSourcesSection(
    onSourcesClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onWipeClick: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_data_section))
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
    ) {
        SettingsNavigationRow(
            label = stringResource(R.string.settings_sources_label),
            onClick = onSourcesClick,
            rowTestTag = "settings-sources-row",
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsNavigationRow(
            label = stringResource(R.string.settings_privacy_label),
            onClick = onPrivacyClick,
            rowTestTag = "settings-privacy-row",
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.action_wipe_data),
            onClick = onWipeClick,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings-wipe-button"),
        )
    }
}
