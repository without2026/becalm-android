package com.becalm.android.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.EvidenceCard
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSignOut
import com.becalm.android.ui.theme.BecalmTheme

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
    viewModel: SettingsViewModel? = null,
    stateOverride: SettingsUiState? = null,
    signedOutOverride: Boolean? = null,
    onNavigateAfterSignOut: (() -> Unit)? = null,
    onErrorDismissed: (() -> Unit)? = null,
    onToggleNotifications: ((Boolean) -> Unit)? = null,
    onTogglePipaConsent: ((Boolean) -> Unit)? = null,
    onToggleCallLogMatchingConsent: ((Boolean) -> Unit)? = null,
    onCallLogPermissionDenied: (() -> Unit)? = null,
    onOpenSources: (() -> Unit)? = null,
    onOpenProcessingStatus: (() -> Unit)? = null,
    onOpenPrivacy: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
    onWipeLocalData: (() -> Unit)? = null,
) {
    val settingsViewModel = if (stateOverride == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<SettingsViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(settingsViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val signedOut = signedOutOverride ?: state.signedOut
    val navigateAfterSignOut = onNavigateAfterSignOut ?: { navController.navigateAfterSignOut() }
    val context = LocalContext.current
    val toggleCallLogMatching = onToggleCallLogMatchingConsent ?: { enabled: Boolean ->
        settingsViewModel?.onToggleCallLogMatchingConsent(enabled)
    }
    val handleCallLogPermissionDenied = onCallLogPermissionDenied ?: {
        settingsViewModel?.onCallLogPermissionDenied()
    }
    val callLogPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) toggleCallLogMatching(true) else handleCallLogPermissionDenied()
    }

    fun requestCallLogMatchingConsent() {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            toggleCallLogMatching(true)
        } else {
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    // Navigate to auth graph after successful sign-out so the user isn't left on a dead session.
    LaunchedEffect(signedOut) {
        if (signedOut) {
            navigateAfterSignOut()
        }
    }

    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(
        errorMessage,
        snackbarHostState,
        onErrorDismissed ?: { settingsViewModel?.onErrorDismissed(); Unit },
    )

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    // PIPA 제3자 제공 동의 toggle dialogs (ONB-PIPA / VOI-004)
    var showPipaEnableDialog by remember { mutableStateOf(false) }
    var showPipaDisableDialog by remember { mutableStateOf(false) }
    var showCallLogEnableDialog by remember { mutableStateOf(false) }

    if (showSignOutDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_sign_out_confirm_title),
            confirmText = stringResource(R.string.action_sign_out),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showSignOutDialog = false
                (onSignOut ?: { settingsViewModel?.onSignOut(); Unit })()
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
                (onWipeLocalData ?: { settingsViewModel?.onWipeLocalData(); Unit })()
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
                (onTogglePipaConsent ?: { enabled: Boolean -> settingsViewModel?.onTogglePipaConsent(enabled); Unit })(true)
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
                (onTogglePipaConsent ?: { enabled: Boolean -> settingsViewModel?.onTogglePipaConsent(enabled); Unit })(false)
            },
            onDismiss = { showPipaDisableDialog = false },
        ) {
            Text(stringResource(R.string.settings_pipa_disable_dialog_warning))
        }
    }

    if (showCallLogEnableDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_call_log_matching_enable_dialog_title),
            confirmText = stringResource(R.string.action_confirm),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                showCallLogEnableDialog = false
                requestCallLogMatchingConsent()
            },
            onDismiss = { showCallLogEnableDialog = false },
            primaryConfirm = true,
        ) {
            Text(stringResource(R.string.settings_call_log_matching_enable_dialog_message))
        }
    }

    SettingsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = navController::popBackStack,
        onToggleNotifications = onToggleNotifications ?: { enabled -> settingsViewModel?.onToggleNotifications(enabled); Unit },
        onTogglePipa = { wantsEnabled ->
            if (wantsEnabled) showPipaEnableDialog = true
            else showPipaDisableDialog = true
        },
        onToggleCallLogMatching = { wantsEnabled ->
            if (wantsEnabled) showCallLogEnableDialog = true
            else toggleCallLogMatching(false)
        },
        onSourcesClick = onOpenSources ?: { navController.navigate(BecalmRoute.SettingsSources.path) },
        onProcessingStatusClick = onOpenProcessingStatus ?: { navController.navigate(BecalmRoute.ProcessingStatus.path) },
        onPrivacyClick = onOpenPrivacy ?: { navController.navigate(BecalmRoute.PrivacyManagement.path) },
        onRequestSignOut = { showSignOutDialog = true },
        onRequestWipe = { showWipeDialog = true },
    )
}

@Composable
public fun SettingsScreenContent(
    state: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onTogglePipa: (Boolean) -> Unit,
    onToggleCallLogMatching: (Boolean) -> Unit = {},
    onSourcesClick: () -> Unit,
    onProcessingStatusClick: () -> Unit = {},
    onPrivacyClick: () -> Unit,
    onRequestSignOut: () -> Unit,
    onRequestWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.settings_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
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
                if (state.processingPaused) {
                    SettingsStatusBanner(
                        message = stringResource(R.string.processing_paused_banner),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                SettingsAccountSection(
                    userEmail = state.userEmail,
                    onSignOutClick = onRequestSignOut,
                )

                Spacer(modifier = Modifier.height(24.dp))

                SettingsPipaSection(
                    notificationsEnabled = state.notificationsEnabled,
                    pipaConsentEnabled = state.pipaConsentEnabled,
                    callLogMatchingConsentEnabled = state.callLogMatchingConsentEnabled,
                    onToggleNotifications = onToggleNotifications,
                    onTogglePipa = onTogglePipa,
                    onToggleCallLogMatching = onToggleCallLogMatching,
                )

                Spacer(modifier = Modifier.height(24.dp))

                SettingsSourcesSection(
                    onSourcesClick = onSourcesClick,
                    onProcessingStatusClick = onProcessingStatusClick,
                    onPrivacyClick = onPrivacyClick,
                    onWipeClick = onRequestWipe,
                )
            }
        }
    }
}

@Composable
private fun SettingsStatusBanner(
    message: String,
) {
    EvidenceCard(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
internal fun SettingsSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    toggleTestTag: String? = null,
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
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (toggleTestTag != null) Modifier.testTag(toggleTestTag) else Modifier,
        )
    }
}

@Composable
internal fun SettingsNavigationRow(
    label: String,
    onClick: () -> Unit,
    rowTestTag: String? = null,
    modifier: Modifier = Modifier,
) {
    SettingsActionRow(
        title = label,
        onClick = onClick,
        rowTestTag = rowTestTag,
        modifier = modifier,
    )
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    rowTestTag: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .then(if (rowTestTag != null) Modifier.testTag(rowTestTag) else Modifier)
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (subtitle == null) 48.dp else 64.dp)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                if (enabled) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewSettingsScreen() {
    BecalmTheme {
        BecalmScaffold(
            title = stringResource(R.string.settings_title),
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
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
                QuietPanel(
                    modifier = Modifier
                        .fillMaxWidth(),
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
