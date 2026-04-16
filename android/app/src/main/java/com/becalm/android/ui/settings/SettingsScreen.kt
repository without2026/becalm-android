package com.becalm.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.coroutines.launch

/**
 * Settings root screen.
 *
 * Sections: Account (email, sign out), Preferences (language, notifications),
 * Data (sources, storage, wipe).
 *
 * PIPA note: sign out and wipe actions show a confirmation dialog before executing.
 *
 * spec: AUTH-005 (sign out / wipe)
 *
 * Primary VM: [SettingsViewModel]
 * Navigation entry: [BecalmRoute.Settings]
 * Navigation exit: [BecalmRoute.SettingsSources] | back to [BecalmRoute.Today]
 */
@Composable
public fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error) {
        state.error?.let { err ->
            scope.launch {
                snackbarHostState.showSnackbar(err)
                viewModel.onErrorDismissed()
            }
        }
    }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.settings_sign_out_confirm_title)) },
            text = { Text(stringResource(R.string.settings_sign_out_confirm_message)) },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.action_sign_out),
                    onClick = {
                        showSignOutDialog = false
                        viewModel.onSignOut()
                    },
                    variant = BecalmButtonVariant.Text,
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showSignOutDialog = false },
                    variant = BecalmButtonVariant.Text,
                )
            },
        )
    }

    if (showWipeDialog) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text(stringResource(R.string.settings_wipe_confirm_title)) },
            text = { Text(stringResource(R.string.settings_wipe_confirm_message)) },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.action_wipe_data),
                    onClick = {
                        showWipeDialog = false
                        viewModel.onWipeLocalData()
                    },
                    variant = BecalmButtonVariant.Text,
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showWipeDialog = false },
                    variant = BecalmButtonVariant.Text,
                )
            },
        )
    }

    BecalmScaffold(
        title = stringResource(R.string.settings_title),
        navigationIcon = {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                // ── Account section ──────────────────────────────────────────
                SettingsSectionLabel(stringResource(R.string.settings_account_section))
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(MaterialTheme.shapes.medium)
                        .padding(16.dp),
                ) {
                    if (state.userEmail != null) {
                        Text(
                            text = state.userEmail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    BecalmButton(
                        text = stringResource(R.string.action_sign_out),
                        onClick = { showSignOutDialog = true },
                        variant = BecalmButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ── Preferences section ──────────────────────────────────────
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
                        checked = state.notificationsEnabled,
                        onCheckedChange = viewModel::onToggleNotifications,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // ── Data section ─────────────────────────────────────────────
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
                        onClick = { navController.navigate(BecalmRoute.SettingsSources.path) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BecalmButton(
                        text = stringResource(R.string.action_wipe_data),
                        onClick = { showWipeDialog = true },
                        variant = BecalmButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsNavigationRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
            .semantics { role = Role.Button },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewSettingsScreen() {
    BecalmTheme {
        BecalmScaffold(
            title = "Settings",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                SettingsSectionLabel("Account")
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(MaterialTheme.shapes.medium)
                        .padding(16.dp),
                ) {
                    Text(
                        text = "name@example.com",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    BecalmButton(
                        text = "Sign Out",
                        onClick = {},
                        variant = BecalmButtonVariant.Secondary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionLabel("Preferences")
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(MaterialTheme.shapes.medium)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SettingsToggleRow(
                        label = "Notifications",
                        checked = true,
                        onCheckedChange = {},
                    )
                }
            }
        }
    }
}
