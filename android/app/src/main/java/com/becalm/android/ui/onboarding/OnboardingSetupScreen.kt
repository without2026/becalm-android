package com.becalm.android.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import kotlinx.coroutines.flow.Flow

@Composable
public fun OnboardingSetupScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    emailEventsOverride: Flow<EmailConnectEvent>? = null,
    calendarEventsOverride: Flow<CalendarConnectEvent>? = null,
    stateOverride: OnboardingUiState? = null,
    onConnectSource: ((OnboardingSourceProvider, Activity) -> Unit)? = null,
    onPersistEmailConsent: (suspend (EmailPipaProvider) -> Boolean)? = null,
    onRefreshSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onCompleteSetup: (() -> Unit)? = null,
    onNavigateToday: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
) {
    val needsViewModel = stateOverride == null ||
        onCompleteSetup == null ||
        onConnectSource == null ||
        onPersistEmailConsent == null ||
        onRefreshSource == null
    val resolvedViewModel = if (needsViewModel) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(resolvedViewModel).uiState.collectAsStateWithLifecycle()
        collectedState
    }
    val context = LocalContext.current
    val detection by rememberRecordingFolderDetection()

    val recordingTreePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val vm = requireNotNull(resolvedViewModel)
        if (uri == null) {
            vm.onRecordingFolderPermissionResult(false)
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            vm.onRecordingFolderPermissionResult(false)
            return@rememberLauncherForActivityResult
        }
        vm.onRecordingFolderTreeGranted(uri.toString())
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val recordingPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val vm = requireNotNull(resolvedViewModel)
        if (!granted) {
            vm.onRecordingFolderPermissionResult(false)
            return@rememberLauncherForActivityResult
        }
        recordingTreePickerLauncher.launch(
            detection.preferredDocumentId?.let(::setupTreeUri),
        )
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requireNotNull(resolvedViewModel).onContactsPermissionResult(granted)
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requireNotNull(resolvedViewModel).onMarkStepStatus(
            OnboardingStep.NOTIFICATION_PERM,
            if (granted) StepStatus.GRANTED else StepStatus.DENIED,
        )
    }

    SourceConnectionsScreen(
        navController = navController,
        entryPoint = SourceConnectionsEntryPoint.Setup,
        viewModel = resolvedViewModel,
        emailEventsOverride = emailEventsOverride,
        calendarEventsOverride = calendarEventsOverride,
        stateOverride = state,
        onConnectSource = onConnectSource,
        onPersistEmailConsent = onPersistEmailConsent,
        onRefreshSource = onRefreshSource,
        onCompleteSetup = onCompleteSetup,
        onNavigateComplete = onNavigateToday,
        onLaunchPendingIntent = onLaunchPendingIntent,
        selfIdentity = OnboardingSelfIdentityUi(
            displayName = state.selfDisplayName,
            phone = state.selfPhone,
            confirmed = state.selfIdentityConfirmed,
            saving = state.isSavingSelfIdentity,
        ),
        onSelfDisplayNameChange = { value -> requireNotNull(resolvedViewModel).onSelfDisplayNameChange(value) },
        onSelfPhoneChange = { value -> requireNotNull(resolvedViewModel).onSelfPhoneChange(value) },
        onSaveSelfIdentity = { requireNotNull(resolvedViewModel).onSaveSelfIdentity() },
        sourceOwnerships = state.sourceOwnerships,
        updatingSourceOwnershipId = state.updatingSourceOwnershipId,
        onSourceOwnership = { id, ownership ->
            requireNotNull(resolvedViewModel).onSetSourceConnectionOwnership(id, ownership)
        },
        setupItems = setupItems(state.stepStates),
        onConnectSetupItem = { item ->
            val vm = requireNotNull(resolvedViewModel)
            when (item) {
                OnboardingSetupItem.RecordingFolder -> {
                    vm.onPipaConsentGranted()
                    recordingPermissionLauncher.launch(audioPermission)
                }
                OnboardingSetupItem.Contacts ->
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                OnboardingSetupItem.Notifications -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.onMarkStepStatus(OnboardingStep.NOTIFICATION_PERM, StepStatus.GRANTED)
                    }
                }
            }
        },
        onSkipSetupItem = { item ->
            val vm = requireNotNull(resolvedViewModel)
            when (item) {
                OnboardingSetupItem.RecordingFolder -> vm.onSkipRecordingFolder()
                OnboardingSetupItem.Contacts -> vm.onSkipContacts()
                OnboardingSetupItem.Notifications ->
                    vm.onMarkStepStatus(OnboardingStep.NOTIFICATION_PERM, StepStatus.SKIPPED)
            }
        },
    )
}

internal enum class OnboardingSetupItem {
    RecordingFolder,
    Contacts,
    Notifications,
}

internal data class OnboardingSetupItemUi(
    val item: OnboardingSetupItem,
    val title: String,
    val description: String,
    val detail: String? = null,
    val state: SourceConnectionState,
)

@Composable
internal fun setupItems(stepStates: Map<OnboardingStep, StepStatus>): List<OnboardingSetupItemUi> =
    listOf(
        OnboardingSetupItemUi(
            item = OnboardingSetupItem.RecordingFolder,
            title = stringResource(R.string.onb_setup_recordings_title),
            description = stringResource(R.string.onb_setup_recordings_body),
            detail = stringResource(R.string.onb_setup_recordings_consent),
            state = setupStateFor(stepStates[OnboardingStep.RECORDING_FOLDER]),
        ),
        OnboardingSetupItemUi(
            item = OnboardingSetupItem.Contacts,
            title = stringResource(R.string.onb_setup_contacts_title),
            description = stringResource(R.string.onb_setup_contacts_body),
            state = setupStateFor(stepStates[OnboardingStep.CONTACTS_PERM]),
        ),
        OnboardingSetupItemUi(
            item = OnboardingSetupItem.Notifications,
            title = stringResource(R.string.onb_setup_notifications_title),
            description = stringResource(R.string.onb_setup_notifications_body),
            state = setupStateFor(stepStates[OnboardingStep.NOTIFICATION_PERM]),
        ),
    )

private fun setupStateFor(status: StepStatus?): SourceConnectionState =
    when (status ?: StepStatus.NOT_STARTED) {
        StepStatus.GRANTED,
        StepStatus.COMPLETE,
        -> SourceConnectionState.Connected
        StepStatus.SKIPPED,
        StepStatus.DENIED,
        -> SourceConnectionState.Skipped
        StepStatus.IN_PROGRESS,
        StepStatus.NOT_STARTED,
        -> SourceConnectionState.Idle
    }

private fun setupTreeUri(documentId: String): Uri =
    DocumentsContract.buildTreeDocumentUri(
        "com.android.externalstorage.documents",
        documentId,
    )
