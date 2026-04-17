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

// ── PIPA disclosure bullets (reused in Settings consent dialog) ───────────────
// Pulled into a private helper to avoid duplicating the glass-panel layout
// between PipaThirdPartyConsentScreen and this confirm dialog.
// The six string resource keys match the ONB-PIPA spec disclosure items exactly.

@Composable
private fun PipaDisclosureList() {
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

    // Navigate to auth graph after successful sign-out so the user isn't left on a dead session.
    LaunchedEffect(state.signedOut) {
        if (state.signedOut) {
            navController.navigate(BecalmRoute.Splash.path) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

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
    // PIPA 제3자 제공 동의 toggle dialogs (ONB-PIPA / VOI-004)
    var showPipaEnableDialog by remember { mutableStateOf(false) }
    var showPipaDisableDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sign_out_confirm_title),
            confirmText = stringResource(R.string.action_sign_out),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showSignOutDialog = false
                viewModel.onSignOut()
            },
            onDismiss = { showSignOutDialog = false },
        ) {
            Text(stringResource(R.string.settings_sign_out_confirm_message))
        }
    }

    if (showWipeDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_wipe_confirm_title),
            confirmText = stringResource(R.string.action_wipe_data),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showWipeDialog = false
                viewModel.onWipeLocalData()
            },
            onDismiss = { showWipeDialog = false },
        ) {
            Text(stringResource(R.string.settings_wipe_confirm_message))
        }
    }

    // PIPA 동의 ON — re-show all 6 disclosure bullets; user must confirm before consent is written.
    if (showPipaEnableDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_pipa_enable_dialog_title),
            confirmText = stringResource(R.string.onb_pipa_button_agree),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showPipaEnableDialog = false
                viewModel.onTogglePipaConsent(true)
            },
            onDismiss = { showPipaEnableDialog = false },
            primaryConfirm = true,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PipaDisclosureList()
            }
        }
    }

    // PIPA 동의 OFF — warn that future recordings will not be auto-uploaded.
    if (showPipaDisableDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_pipa_toggle_label),
            confirmText = stringResource(R.string.action_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showPipaDisableDialog = false
                viewModel.onTogglePipaConsent(false)
            },
            onDismiss = { showPipaDisableDialog = false },
        ) {
            Text(stringResource(R.string.settings_pipa_disable_dialog_warning))
        }
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
                    SettingsToggleRow(
                        label = stringResource(R.string.settings_pipa_toggle_label),
                        checked = state.pipaConsentEnabled,
                        onCheckedChange = { wantsEnabled ->
                            if (wantsEnabled) showPipaEnableDialog = true
                            else showPipaDisableDialog = true
                        },
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
            }
        }
    }
}

/**
 * 네 개의 거의 동일한 AlertDialog(sign-out / wipe / PIPA on·off)를 하나로 묶기 위해 추출.
 * 호출자는 본문 slot에 단일 Text 또는 스크롤 Column을 직접 전달해야 하며,
 * confirm/dismiss 버튼 텍스트와 콜백은 원본과 동일한 순서로 전달해야 한다.
 * primaryConfirm=true일 때만 confirm 버튼이 Primary variant로 표시되며, 그 외 동작은 원본과 완전히 동일하다.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    primaryConfirm: Boolean = false,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { content() },
        confirmButton = {
            BecalmButton(
                text = confirmText,
                onClick = onConfirm,
                variant = if (primaryConfirm) BecalmButtonVariant.Primary else BecalmButtonVariant.Text,
            )
        },
        dismissButton = {
            BecalmButton(
                text = dismissText,
                onClick = onDismiss,
                variant = BecalmButtonVariant.Text,
            )
        },
    )
}
