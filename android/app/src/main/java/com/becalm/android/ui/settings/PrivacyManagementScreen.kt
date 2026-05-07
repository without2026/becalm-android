package com.becalm.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.HandleSnackbarMessage
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSignOut
import java.io.IOException
import kotlinx.coroutines.flow.Flow

public fun interface ExportDocumentLauncher {
    public fun launch(
        fileName: String,
        bytes: ByteArray,
        onSaved: () -> Unit,
        onFailed: (String) -> Unit,
    )
}

@Composable
public fun PrivacyManagementScreen(
    navController: NavHostController,
    viewModel: PrivacyManagementViewModel? = null,
    stateOverride: PrivacyManagementUiState? = null,
    effectsOverride: Flow<PrivacyManagementEffect>? = null,
    exportDocumentLauncher: ExportDocumentLauncher? = null,
    onNavigateAfterSignOut: (() -> Unit)? = null,
    onErrorDismissed: (() -> Unit)? = null,
    onExportRequested: (() -> Unit)? = null,
    onExportSaved: (() -> Unit)? = null,
    onExportFailed: ((String) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onOpenConsentWithdraw: (() -> Unit)? = null,
    onOpenProcessingPause: (() -> Unit)? = null,
    onOpenAccountDeletion: (() -> Unit)? = null,
    onOpenActivityLog: (() -> Unit)? = null,
    onDeleteSourceArchiveBefore: ((String) -> Unit)? = null,
) {
    val privacyViewModel = if (
        stateOverride == null ||
            effectsOverride == null ||
            onErrorDismissed == null ||
            onExportRequested == null ||
            onExportSaved == null ||
            onExportFailed == null ||
            onBack == null ||
            onOpenConsentWithdraw == null ||
            onOpenProcessingPause == null ||
            onOpenAccountDeletion == null ||
            onOpenActivityLog == null ||
            onDeleteSourceArchiveBefore == null
    ) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<PrivacyManagementViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(privacyViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showExportConfirm by remember { mutableStateOf(false) }
    var showArchiveDeleteConfirm by remember { mutableStateOf(false) }
    var archiveCutoffDate by remember { mutableStateOf("") }
    val createDocument = rememberLauncherForActivityResult(CreateDocument("application/zip")) { uri ->
        val bytes = pendingExportBytes
        if (uri == null || bytes == null) {
            (onExportFailed ?: requireNotNull(privacyViewModel)::onExportFailed)(
                context.getString(R.string.privacy_export_cancelled),
            )
            pendingExportBytes = null
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(bytes)
            } ?: throw IOException("failed to open export destination")
        }.onSuccess {
            (onExportSaved ?: requireNotNull(privacyViewModel)::onExportSaved)()
        }.onFailure { error ->
            (onExportFailed ?: requireNotNull(privacyViewModel)::onExportFailed)(
                error.message ?: context.getString(R.string.privacy_export_failed),
            )
        }
        pendingExportBytes = null
    }
    val launchExportDocument = exportDocumentLauncher ?: ExportDocumentLauncher { fileName, bytes, onSaved, onFailed ->
        pendingExportBytes = bytes
        createDocument.launch(fileName)
    }

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) {
            (onNavigateAfterSignOut ?: { navController.navigateAfterSignOut() })()
        }
    }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(
        errorMessage,
        snackbarHostState,
        onErrorDismissed ?: requireNotNull(privacyViewModel)::onErrorDismissed,
    )
    CollectFlowEffect(effectsOverride ?: requireNotNull(privacyViewModel).effects) { effect ->
        when (effect) {
            is PrivacyManagementEffect.CreateExportDocument -> {
                launchExportDocument.launch(
                    effect.fileName,
                    effect.bytes,
                    onSaved = onExportSaved ?: requireNotNull(privacyViewModel)::onExportSaved,
                    onFailed = onExportFailed ?: requireNotNull(privacyViewModel)::onExportFailed,
                )
            }
            is PrivacyManagementEffect.SourceArchiveDeleted -> {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.privacy_source_archive_deleted_fmt, effect.deletedCount),
                )
            }
        }
    }

    PrivacyManagementScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack ?: {
            navController.popBackStack()
            Unit
        },
        onExportClick = { showExportConfirm = true },
        onOpenConsentWithdraw = onOpenConsentWithdraw ?: { navController.navigate(BecalmRoute.ConsentWithdraw.path) },
        onOpenProcessingPause = onOpenProcessingPause ?: { navController.navigate(BecalmRoute.ProcessingPause.path) },
        onOpenAccountDeletion = onOpenAccountDeletion ?: { navController.navigate(BecalmRoute.AccountDeletion.path) },
        onOpenActivityLog = onOpenActivityLog ?: { navController.navigate(BecalmRoute.ActivityLog.path) },
        onOpenSourceArchiveDelete = { showArchiveDeleteConfirm = true },
    )

    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            title = { Text(stringResource(R.string.privacy_export_title)) },
            text = { Text(stringResource(R.string.privacy_export_confirm_body)) },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        showExportConfirm = false
                        (onExportRequested ?: requireNotNull(privacyViewModel)::onExportRequested)()
                    },
                    variant = BecalmButtonVariant.Primary,
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showExportConfirm = false },
                    variant = BecalmButtonVariant.Text,
                )
            },
        )
    }

    if (showArchiveDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showArchiveDeleteConfirm = false },
            title = { Text(stringResource(R.string.privacy_source_archive_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.privacy_source_archive_subtitle_fmt, state.sourceArchiveCount, formatBytes(state.sourceArchiveBytes)))
                    OutlinedTextField(
                        value = archiveCutoffDate,
                        onValueChange = { archiveCutoffDate = it },
                        label = { Text(stringResource(R.string.privacy_source_archive_cutoff_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy-source-archive-cutoff"),
                    )
                }
            },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.privacy_source_archive_delete_confirm),
                    enabled = archiveCutoffDate.isNotBlank() && !state.sourceArchiveDeleting,
                    onClick = {
                        showArchiveDeleteConfirm = false
                        (onDeleteSourceArchiveBefore ?: requireNotNull(privacyViewModel)::onDeleteSourceArchiveBefore)(
                            archiveCutoffDate,
                        )
                    },
                    variant = BecalmButtonVariant.Secondary,
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showArchiveDeleteConfirm = false },
                    variant = BecalmButtonVariant.Text,
                )
            },
        )
    }
}

@Composable
internal fun PrivacyManagementScreenContent(
    state: PrivacyManagementUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onExportClick: () -> Unit,
    onOpenConsentWithdraw: () -> Unit,
    onOpenProcessingPause: () -> Unit,
    onOpenAccountDeletion: () -> Unit,
    onOpenActivityLog: () -> Unit,
    onOpenSourceArchiveDelete: () -> Unit,
) {
    BecalmScaffold(
        title = stringResource(R.string.privacy_management_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsSectionLabel(stringResource(R.string.privacy_data_control_section))
                SettingsActionRow(
                    title = stringResource(R.string.privacy_export_title),
                    subtitle = stringResource(R.string.privacy_export_subtitle),
                    onClick = onExportClick,
                    enabled = !state.exporting,
                    rowTestTag = "privacy-export-card",
                )
                HorizontalDivider()
                SettingsActionRow(
                    title = stringResource(R.string.privacy_withdraw_title),
                    subtitle = stringResource(R.string.privacy_withdraw_subtitle),
                    onClick = onOpenConsentWithdraw,
                    rowTestTag = "privacy-withdraw-card",
                )
                HorizontalDivider()
                SettingsActionRow(
                    title = stringResource(R.string.privacy_source_archive_title),
                    subtitle = if (state.sourceArchiveCount == 0) {
                        stringResource(R.string.privacy_source_archive_empty)
                    } else {
                        stringResource(
                            R.string.privacy_source_archive_subtitle_fmt,
                            state.sourceArchiveCount,
                            formatBytes(state.sourceArchiveBytes),
                        )
                    },
                    onClick = onOpenSourceArchiveDelete,
                    enabled = state.sourceArchiveCount > 0 && !state.sourceArchiveDeleting,
                    rowTestTag = "privacy-source-archive-card",
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionLabel(stringResource(R.string.privacy_processing_section))
                SettingsActionRow(
                    title = stringResource(R.string.privacy_pause_title),
                    subtitle = stringResource(R.string.privacy_pause_subtitle),
                    onClick = onOpenProcessingPause,
                    rowTestTag = "privacy-pause-card",
                )
                HorizontalDivider()
                SettingsActionRow(
                    title = stringResource(R.string.privacy_activity_log_title),
                    subtitle = stringResource(R.string.privacy_activity_log_subtitle),
                    onClick = onOpenActivityLog,
                    rowTestTag = "privacy-activity-log-card",
                )
                Spacer(modifier = Modifier.height(12.dp))
                SettingsSectionLabel(stringResource(R.string.privacy_danger_section))
                SettingsActionRow(
                    title = stringResource(R.string.privacy_delete_title),
                    subtitle = stringResource(R.string.privacy_delete_subtitle_fmt, state.commitmentCount, state.enrichmentCount, state.emailCount),
                    onClick = onOpenAccountDeletion,
                    rowTestTag = "privacy-delete-card",
                    destructive = true,
                )
                Text(
                    text = stringResource(R.string.privacy_correction_guidance),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return "%.1f KB".format(kib)
    return "%.1f MB".format(kib / 1024.0)
}

@Composable
internal fun ConsentWithdrawScreen(
    navController: NavHostController,
    viewModel: PrivacyManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)
    BecalmScaffold(
        title = stringResource(R.string.privacy_withdraw_title),
        navigationIcon = {
            IconButton(onClick = navController::popBackStack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ConsentWithdrawContent(
            state = state,
            onWithdrawConsent = viewModel::onWithdrawConsent,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun ProcessingPauseScreen(
    navController: NavHostController,
    viewModel: PrivacyManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPauseConfirm by remember { mutableStateOf(false) }
    BecalmScaffold(
        title = stringResource(R.string.privacy_pause_title),
        navigationIcon = {
            IconButton(onClick = navController::popBackStack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
    ) { padding ->
        ProcessingPauseContent(
            state = state,
            onSetProcessingPaused = viewModel::onSetProcessingPaused,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun AccountDeletionScreen(
    navController: NavHostController,
    viewModel: PrivacyManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var emailInput by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf("") }
    LaunchedEffect(state.signedOut) {
        if (state.signedOut) {
            navController.navigateAfterSignOut()
        }
    }
    val errorMessage = state.error?.let { uiMessageStringResource(it) }
    HandleSnackbarMessage(errorMessage, snackbarHostState, viewModel::onErrorDismissed)
    BecalmScaffold(
        title = stringResource(R.string.privacy_delete_title),
        navigationIcon = {
            IconButton(onClick = navController::popBackStack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        AccountDeletionContent(
            state = state,
            onConfirmDeletion = { email, keyword ->
                emailInput = email
                confirmText = keyword
                viewModel.onConfirmAccountDeletion(email, keyword)
            },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun ActivityLogScreen(
    navController: NavHostController,
    viewModel: PrivacyManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BecalmScaffold(
        title = stringResource(R.string.privacy_activity_log_title),
        navigationIcon = {
            IconButton(onClick = navController::popBackStack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
    ) { padding ->
        ActivityLogContent(
            state = state,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun ConsentWithdrawContent(
    state: PrivacyManagementUiState,
    onWithdrawConsent: (WithdrawConsentTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_voice_label),
            checked = state.voiceConsentEnabled,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.VOICE) },
            testTag = "privacy-withdraw-voice",
        )
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_gmail_label),
            checked = state.gmailConnected,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.GMAIL) },
            testTag = "privacy-withdraw-gmail",
        )
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_outlook_mail_label),
            checked = state.outlookConnected,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.OUTLOOK_MAIL) },
            testTag = "privacy-withdraw-outlook-mail",
        )
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_naver_label),
            checked = state.naverConnected,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.NAVER_IMAP) },
            testTag = "privacy-withdraw-naver",
        )
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_daum_label),
            checked = state.daumConnected,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.DAUM_IMAP) },
            testTag = "privacy-withdraw-daum",
        )
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_google_calendar_label),
            checked = state.googleCalendarEnabled,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.GOOGLE_CALENDAR) },
            testTag = "privacy-withdraw-google-calendar",
        )
        ConsentToggleRow(
            label = stringResource(R.string.privacy_withdraw_outlook_calendar_label),
            checked = state.outlookCalendarEnabled,
            onCheckedChange = { checked -> if (!checked) onWithdrawConsent(WithdrawConsentTarget.OUTLOOK_CALENDAR) },
            testTag = "privacy-withdraw-outlook-calendar",
        )
    }
}

@Composable
internal fun ProcessingPauseContent(
    state: PrivacyManagementUiState,
    onSetProcessingPaused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPauseConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.privacy_pause_description), style = MaterialTheme.typography.bodyMedium)
        ConsentToggleRow(
            label = stringResource(R.string.privacy_pause_switch_label),
            checked = state.processingPaused,
            onCheckedChange = { checked ->
                if (checked) showPauseConfirm = true else onSetProcessingPaused(false)
            },
            testTag = "privacy-pause-switch",
        )
        state.pauseStartedAt?.let { pausedAt ->
            Text(
                text = stringResource(R.string.privacy_pause_started_fmt, pausedAt.toString()),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (showPauseConfirm) {
        AlertDialog(
            onDismissRequest = { showPauseConfirm = false },
            title = { Text(stringResource(R.string.privacy_pause_title)) },
            text = { Text(stringResource(R.string.privacy_pause_confirm_body)) },
            confirmButton = {
                BecalmButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        showPauseConfirm = false
                        onSetProcessingPaused(true)
                    },
                    variant = BecalmButtonVariant.Primary,
                )
            },
            dismissButton = {
                BecalmButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showPauseConfirm = false },
                    variant = BecalmButtonVariant.Text,
                )
            },
        )
    }
}

@Composable
internal fun AccountDeletionContent(
    state: PrivacyManagementUiState,
    onConfirmDeletion: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var emailInput by remember { mutableStateOf("") }
    var confirmText by remember { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.privacy_delete_warning_fmt, state.commitmentCount, state.enrichmentCount, state.emailCount),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = emailInput,
            onValueChange = { emailInput = it },
            label = { Text(stringResource(R.string.privacy_delete_email_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy-delete-email"),
        )
        OutlinedTextField(
            value = confirmText,
            onValueChange = { confirmText = it },
            label = { Text(stringResource(R.string.privacy_delete_keyword_label)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy-delete-keyword"),
        )
        BecalmButton(
            text = stringResource(R.string.privacy_delete_confirm),
            onClick = { onConfirmDeletion(emailInput, confirmText) },
            variant = BecalmButtonVariant.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("privacy-delete-confirm"),
        )
    }
}

@Composable
internal fun ActivityLogContent(
    state: PrivacyManagementUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.activityLog.isEmpty()) {
            Text(stringResource(R.string.privacy_activity_log_empty))
        } else {
            state.activityLog.forEach { entry ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(entry.action, style = MaterialTheme.typography.titleSmall)
                    Text(entry.timestampIso, style = MaterialTheme.typography.bodySmall)
                    if (entry.details.isNotEmpty()) {
                        Text(entry.details.entries.joinToString(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        )
    }
}
