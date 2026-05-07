package com.becalm.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant

/**
 * Data section of [SettingsScreen]. Renders the navigation row to the per-source list
 * (where SMG-001 6-source list + 연락처 pseudo-source row live) and the wipe button.
 * The wipe confirmation dialog is hoisted to the parent.
 *
 * R8 audit: routine source/privacy navigation stays in a plain list, while the
 * destructive local wipe action is separated into its own danger section.
 */
@Composable
internal fun SettingsSourcesSection(
    onSourcesClick: () -> Unit,
    onProcessingStatusClick: () -> Unit,
    onPrivacyClick: () -> Unit,
    onWipeClick: () -> Unit,
) {
    SettingsSectionLabel(stringResource(R.string.settings_data_section))
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsNavigationRow(
            label = stringResource(R.string.settings_sources_label),
            onClick = onSourcesClick,
            rowTestTag = "settings-sources-row",
        )
        HorizontalDivider()
        SettingsNavigationRow(
            label = stringResource(R.string.settings_processing_status_label),
            onClick = onProcessingStatusClick,
            rowTestTag = "settings-processing-status-row",
        )
        HorizontalDivider()
        SettingsNavigationRow(
            label = stringResource(R.string.settings_privacy_label),
            onClick = onPrivacyClick,
            rowTestTag = "settings-privacy-row",
        )
    }
    Spacer(modifier = Modifier.height(24.dp))
    SettingsSectionLabel(stringResource(R.string.settings_danger_section))
    Spacer(modifier = Modifier.height(8.dp))
    Column(modifier = Modifier.fillMaxWidth()) {
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
