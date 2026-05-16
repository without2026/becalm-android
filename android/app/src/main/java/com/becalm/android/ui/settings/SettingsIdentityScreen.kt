package com.becalm.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.uiMessageStringResource

@Composable
public fun SettingsIdentityScreen(
    navController: NavHostController,
    viewModel: SettingsIdentityViewModel? = null,
    stateOverride: SettingsIdentityUiState? = null,
) {
    val resolvedViewModel = if (stateOverride == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<SettingsIdentityViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collected by requireNotNull(resolvedViewModel).uiState.collectAsStateWithLifecycle()
        collected
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(
        errorMessage,
        snackbarHostState,
        { resolvedViewModel?.onErrorDismissed(); Unit },
    )

    BecalmScaffold(
        title = stringResource(R.string.settings_identity_title),
        navigationIcon = {
            IconButton(onClick = navController::popBackStack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            SettingsIdentityContent(
                state = state,
                onDisplayNameChange = { resolvedViewModel?.onDisplayNameChange(it) },
                onPhoneChange = { resolvedViewModel?.onPhoneChange(it) },
                onSaveProfile = { resolvedViewModel?.onSaveProfile() },
                onAnchorTypeChange = { resolvedViewModel?.onNewAnchorTypeChange(it) },
                onAnchorValueChange = { resolvedViewModel?.onNewAnchorValueChange(it) },
                onAddAnchor = { resolvedViewModel?.onAddAnchor() },
                onArchiveAnchor = { resolvedViewModel?.onArchiveAnchor(it) },
                onSetConnectionOwnership = { id, ownership ->
                    resolvedViewModel?.onSetConnectionOwnership(id, ownership)
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
internal fun SettingsIdentityContent(
    state: SettingsIdentityUiState,
    onDisplayNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onAnchorTypeChange: (String) -> Unit,
    onAnchorValueChange: (String) -> Unit,
    onAddAnchor: () -> Unit,
    onArchiveAnchor: (String) -> Unit,
    onSetConnectionOwnership: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings-identity-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsIdentityProfilePanel(
                state = state,
                onDisplayNameChange = onDisplayNameChange,
                onPhoneChange = onPhoneChange,
                onSaveProfile = onSaveProfile,
            )
        }
        item {
            SettingsIdentityAnchorPanel(
                state = state,
                onAnchorTypeChange = onAnchorTypeChange,
                onAnchorValueChange = onAnchorValueChange,
                onAddAnchor = onAddAnchor,
            )
        }
        items(state.anchors.filterNot { it.scope == "source_event" }, key = { it.id }) { anchor ->
            SettingsIdentityAnchorRow(anchor = anchor, onArchive = { onArchiveAnchor(anchor.id) })
        }
        item {
            SettingsSectionLabel(stringResource(R.string.settings_identity_connections_section))
        }
        if (state.connections.isEmpty()) {
            item {
                QuietPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_identity_connections_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(state.connections, key = { it.id }) { connection ->
                SettingsSourceOwnershipRow(
                    connection = connection,
                    updating = state.updatingConnectionId == connection.id,
                    onOwnership = { ownership -> onSetConnectionOwnership(connection.id, ownership) },
                )
            }
        }
    }
}

@Composable
private fun SettingsIdentityProfilePanel(
    state: SettingsIdentityUiState,
    onDisplayNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_identity_profile_section))
        QuietPanel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_identity_profile_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = stringResource(R.string.settings_identity_display_name_label),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            BecalmTextField(
                value = state.phone,
                onValueChange = onPhoneChange,
                label = stringResource(R.string.settings_identity_phone_label),
                placeholder = "+82 10 0000 0000",
                keyboardType = KeyboardType.Phone,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.action_save),
                onClick = onSaveProfile,
                loading = state.savingProfile,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings-identity-save"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsIdentityAnchorPanel(
    state: SettingsIdentityUiState,
    onAnchorTypeChange: (String) -> Unit,
    onAnchorValueChange: (String) -> Unit,
    onAddAnchor: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_identity_anchors_section))
        QuietPanel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.settings_identity_anchors_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("email" to R.string.settings_identity_anchor_email, "phone" to R.string.settings_identity_anchor_phone)
                    .forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = state.newAnchorType == option.first,
                            onClick = { onAnchorTypeChange(option.first) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        ) {
                            Text(stringResource(option.second))
                        }
                    }
            }
            Spacer(modifier = Modifier.height(8.dp))
            BecalmTextField(
                value = state.newAnchorValue,
                onValueChange = onAnchorValueChange,
                label = stringResource(R.string.settings_identity_anchor_value_label),
                modifier = Modifier.fillMaxWidth(),
                keyboardType = if (state.newAnchorType == "phone") KeyboardType.Phone else KeyboardType.Email,
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.settings_identity_anchor_add),
                onClick = onAddAnchor,
                loading = state.addingAnchor,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings-identity-anchor-add"),
            )
        }
    }
}

@Composable
private fun SettingsIdentityAnchorRow(
    anchor: SelfIdentityAnchorUi,
    onArchive: () -> Unit,
) {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anchor.value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${anchor.type} · ${anchor.status} · ${anchor.trust}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (anchor.status == "active") {
                BecalmButton(
                    text = stringResource(R.string.settings_identity_anchor_archive),
                    onClick = onArchive,
                    variant = BecalmButtonVariant.Text,
                    modifier = Modifier.testTag("settings-identity-anchor-archive"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSourceOwnershipRow(
    connection: SourceConnectionOwnershipUi,
    updating: Boolean,
    onOwnership: (String) -> Unit,
) {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = connection.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = connection.accountLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf(
                "self" to R.string.settings_identity_connection_self,
                "shared" to R.string.settings_identity_connection_shared,
                "delegated" to R.string.settings_identity_connection_delegated,
                "unknown" to R.string.settings_identity_connection_unknown,
            )
                .forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = connection.ownership == option.first,
                        enabled = !updating,
                        onClick = { onOwnership(option.first) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                    ) {
                        Text(stringResource(option.second))
                    }
                }
        }
    }
}
