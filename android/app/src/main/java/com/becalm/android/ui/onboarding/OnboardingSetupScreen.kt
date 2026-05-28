package com.becalm.android.ui.onboarding

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.auth.AuthUiState
import com.becalm.android.ui.auth.AuthViewModel
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmNavigationDefaults
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSignOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
public fun OnboardingSetupScreen(
    navController: NavHostController,
    setupRoutePath: String? = null,
    viewModel: OnboardingViewModel? = null,
    emailEventsOverride: Flow<EmailConnectEvent>? = null,
    calendarEventsOverride: Flow<CalendarConnectEvent>? = null,
    stateOverride: OnboardingUiState? = null,
    onConnectSource: ((OnboardingSourceProvider, android.app.Activity) -> Unit)? = null,
    onSkipSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onPersistEmailConsent: (suspend (EmailPipaProvider) -> Boolean)? = null,
    onRefreshSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onStartGmailActivationPreview: (() -> Unit)? = null,
    onConnectContacts: (() -> Unit)? = null,
    onConnectRecording: (() -> Unit)? = null,
    onIntroNext: (() -> Unit)? = null,
    onIntroBack: (() -> Unit)? = null,
    onCompleteSetup: (() -> Unit)? = null,
    onNavigateToday: (() -> Unit)? = null,
    authViewModel: AuthViewModel? = null,
    onChangeAccount: (() -> Unit)? = null,
    onNavigateAfterSignOut: (() -> Unit)? = null,
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
    val needsAuthViewModel = onChangeAccount == null || onNavigateAfterSignOut == null
    val resolvedAuthViewModel = if (needsAuthViewModel) {
        authViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<AuthViewModel>()
    } else {
        authViewModel
    }
    val authState = if (resolvedAuthViewModel != null && onNavigateAfterSignOut == null) {
        val collectedAuthState by resolvedAuthViewModel.uiState.collectAsStateWithLifecycle()
        collectedAuthState
    } else {
        null
    }
    if (resolvedViewModel != null && onCompleteSetup == null) {
        val setupEffects = resolvedViewModel.setupEffects
        LaunchedEffect(setupEffects, onNavigateToday) {
            setupEffects.collect { effect ->
                when (effect) {
                    OnboardingSetupEffect.NavigateToPeople -> {
                        val navigate = onNavigateToday ?: {
                            navController.navigate(BecalmNavigationDefaults.authenticatedHomeRoute) {
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                        navigate()
                    }
                    is OnboardingSetupEffect.NavigateToSetupRoute -> {
                        if (navController.currentDestination?.route != effect.route) {
                            navController.navigate(effect.route) {
                                launchSingleTop = true
                            }
                        }
                    }
                    is OnboardingSetupEffect.NavigateToCompletion -> {
                        navController.navigate(BecalmRoute.OnboardingComplete(effect.personId).path) {
                            popUpTo(0) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(authState, onNavigateAfterSignOut) {
        if (authState is AuthUiState.SignedOut) {
            val navigate = onNavigateAfterSignOut ?: { navController.navigateAfterSignOut() }
            navigate()
        }
    }
    LaunchedEffect(setupRoutePath, resolvedViewModel) {
        resolvedViewModel?.onSetupRouteVisible(setupRoutePath)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val transientStatesState = remember {
        mutableStateOf(emptyMap<OnboardingSourceProvider, SourceConnectionState>())
    }
    val transientStates = transientStatesState.value
    val connectSource = onConnectSource ?: { provider, hostActivity ->
        requireNotNull(resolvedViewModel).onConnectSourceProvider(provider, hostActivity)
    }
    val persistEmailConsent = onPersistEmailConsent ?: { provider ->
        requireNotNull(resolvedViewModel).onEmailPipaConsent(listOf(provider), granted = true)
    }
    val refreshSource = onRefreshSource ?: { provider ->
        requireNotNull(resolvedViewModel).refreshSourceProviderConnection(provider)
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        resolvedViewModel?.onContactsPermissionResult(granted)
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val recordingPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            resolvedViewModel?.onRecordingPathSelected(targetSourceType = SourceType.CALL_RECORDING)
        } else {
            resolvedViewModel?.onRecordingFolderPermissionResult(
                granted = false,
                targetSourceType = SourceType.CALL_RECORDING,
            )
        }
    }
    val connectContacts = onConnectContacts ?: {
        if (resolvedViewModel != null) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }
    val connectCallRecording = onConnectRecording ?: {
        recordingPermissionLauncher.launch(audioPermission)
    }
    val skipCallRecordingAndContinue = {
        resolvedViewModel?.onSkipRecordingFolder(targetSourceType = SourceType.CALL_RECORDING)
        val next = onIntroNext ?: { resolvedViewModel?.onIntroNext(); Unit }
        next()
    }
    val activityMissingCopy = stringResource(R.string.onb_sources_activity_missing)
    val consentWriteFailedCopy = stringResource(R.string.onb_sources_consent_write_failed)
    val stateErrorMessage = state.error?.let { uiMessageStringResource(it) }
    val stateNoticeMessage = state.notice?.let { uiMessageStringResource(it) }

    LaunchedEffect(stateErrorMessage) {
        if (!stateErrorMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(stateErrorMessage)
        }
    }
    LaunchedEffect(stateNoticeMessage) {
        if (!stateNoticeMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(stateNoticeMessage)
            resolvedViewModel?.onNoticeDismissed()
        }
    }
    SourceConnectionLifecycleRefreshEffect(
        lifecycleOwner = lifecycleOwner,
        transientStates = transientStates,
        onRefreshSource = refreshSource,
    )
    val setupDestination = OnboardingSetupDestination.fromRoutePath(setupRoutePath)
    val displayStage = setupDestination?.setupStage ?: state.setupStage
    val displayIntroPageIndex = setupDestination?.introPageIndex ?: state.introPageIndex

    SourceConnectionVisibleProviderRefreshEffect(
        lifecycleOwner = lifecycleOwner,
        provider = introSourceProviderNeedingRefresh(
            setupStage = displayStage,
            introPageIndex = displayIntroPageIndex,
            stepStates = state.stepStates,
        ),
        onRefreshSource = refreshSource,
    )
    SourceConnectionEmailEventEffect(
        events = emailEventsOverride ?: requireNotNull(resolvedViewModel).emailConnectEvents,
        entryPoint = SourceConnectionsEntryPoint.Setup,
        resources = context.resources,
        snackbarHostState = snackbarHostState,
        transientStates = transientStatesState,
        pendingIntentProvider = remember { mutableStateOf<OnboardingSourceProvider?>(null) },
        onLaunchPendingIntent = onLaunchPendingIntent ?: {},
        onConnected = {},
    )
    SourceConnectionCalendarEventEffect(
        events = calendarEventsOverride ?: requireNotNull(resolvedViewModel).calendarConnectEvents,
        entryPoint = SourceConnectionsEntryPoint.Setup,
        resources = context.resources,
        snackbarHostState = snackbarHostState,
        transientStates = transientStatesState,
        onConnected = {},
    )
    BackHandler {
        resolvedViewModel?.onSetupBackRequested()
    }
    BecalmScaffold(
        title = stringResource(R.string.onb_setup_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            TextButton(
                onClick = onChangeAccount ?: { requireNotNull(resolvedAuthViewModel).onSignOut() },
            ) {
                Text(text = stringResource(R.string.action_sign_out))
            }
        },
    ) { padding ->
        val contentModifier = Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)

        when (displayStage) {
            OnboardingSetupStage.INTRO -> {
                val gmailState = transientStates[OnboardingSourceProvider.GMAIL]
                    ?: if (state.gmailActivationPreview.loading) {
                        SourceConnectionState.Syncing
                    } else if (state.stepStates[OnboardingStep.LINK_GMAIL] == StepStatus.COMPLETE) {
                        SourceConnectionState.Connected
                    } else {
                        SourceConnectionState.Idle
                    }
                val calendarState = transientStates[OnboardingSourceProvider.GOOGLE_CALENDAR]
                    ?: SourceConnectionProjector.sourceStateFor(
                        provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                        stepStates = state.stepStates,
                        transientStates = transientStates,
                        respectStepStates = true,
                    )
                val contactsState = setupStateFor(state.stepStates[OnboardingStep.CONTACTS_PERM])
                OnboardingIntroContent(
                    pageIndex = displayIntroPageIndex,
                    gmailConnectionState = gmailState,
                    contactsConnectionState = contactsState,
                    calendarConnectionState = calendarState,
                    callRecordingConnectionState = state.callRecordingConnectionState,
                    contactsPreview = state.contactsPreview,
                    calendarPreview = state.calendarPreview,
                    gmailActivationPreview = state.gmailActivationPreview,
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = state.selfDisplayName,
                        email = state.selfEmail,
                        phone = state.selfPhone,
                        alias = state.selfAlias,
                        confirmed = state.selfIdentityConfirmed,
                        saving = state.isSavingSelfIdentity,
                        authProvider = state.selfAuthProvider,
                        displayNameReadOnly = state.selfDisplayNameReadOnly,
                        emailReadOnly = state.selfEmailReadOnly,
                        phoneReadOnly = state.selfPhoneReadOnly,
                        phoneVerified = state.selfPhoneVerified,
                    ),
                    gmailActivationReturnAvailable = false,
                    onNext = onIntroNext ?: { resolvedViewModel?.onIntroNext(); Unit },
                    onBack = onIntroBack ?: { resolvedViewModel?.onIntroBack(); Unit },
                    onConnectContacts = connectContacts,
                    onIdentityNext = { resolvedViewModel?.onIdentityNext() },
                    onSelfDisplayNameChange = { resolvedViewModel?.onSelfDisplayNameChange(it) },
                    onSelfEmailChange = { resolvedViewModel?.onSelfEmailChange(it) },
                    onSelfPhoneChange = { resolvedViewModel?.onSelfPhoneChange(it) },
                    onConnectGoogleCalendar = {
                        val hostActivity = activity
                        if (hostActivity == null) {
                            scope.launch { snackbarHostState.showSnackbar(activityMissingCopy) }
                            return@OnboardingIntroContent
                        }
                        transientStatesState.value = transientStatesState.value +
                            (OnboardingSourceProvider.GOOGLE_CALENDAR to SourceConnectionState.PendingExternalAuth)
                        connectSource(OnboardingSourceProvider.GOOGLE_CALENDAR, hostActivity)
                    },
                    onConnectCallRecording = connectCallRecording,
                    onSkipCallRecording = skipCallRecordingAndContinue,
                    onConnectGmail = {
                        val hostActivity = activity
                        if (hostActivity == null) {
                            scope.launch { snackbarHostState.showSnackbar(activityMissingCopy) }
                            return@OnboardingIntroContent
                        }
                        scope.launch {
                            if (!persistEmailConsent(EmailPipaProvider.GMAIL)) {
                                snackbarHostState.showSnackbar(consentWriteFailedCopy)
                                return@launch
                            }
                            transientStatesState.value = transientStatesState.value +
                                (OnboardingSourceProvider.GMAIL to SourceConnectionState.PendingExternalAuth)
                            connectSource(OnboardingSourceProvider.GMAIL, hostActivity)
                        }
                    },
                    onReturnToGmailActivation = {
                        resolvedViewModel?.onReturnToGmailActivationPreview()
                    },
                    modifier = contentModifier,
                )
            }

            OnboardingSetupStage.GMAIL_PREVIEW -> {
                GmailActivationPreviewContent(
                    state = state.gmailActivationPreview,
                    onUsePreview = { requireNotNull(resolvedViewModel).onUseGmailActivationPreview() },
                    onRetry = { requireNotNull(resolvedViewModel).onRetryGmailActivationPreview() },
                    onStartWithoutPreview = {
                        requireNotNull(resolvedViewModel).onStartWithoutGmailActivationPreview()
                    },
                    modifier = contentModifier,
                )
            }

            OnboardingSetupStage.FIRST_MEMORY -> {
                Column(
                    modifier = contentModifier,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OnboardingSetupProgress(
                        currentStep = ONBOARDING_INTRO_PAGE_COUNT,
                        totalSteps = ONBOARDING_INTRO_PAGE_COUNT,
                    )
                    FirstMemoryActivationContent(
                        state = state.firstMemory,
                        onOriginChange = { requireNotNull(resolvedViewModel).onFirstMemoryOriginChange(it) },
                        onPersonNameChange = { requireNotNull(resolvedViewModel).onFirstMemoryPersonNameChange(it) },
                        onPromiseTextChange = { requireNotNull(resolvedViewModel).onFirstMemoryPromiseTextChange(it) },
                        onKindChange = { requireNotNull(resolvedViewModel).onFirstMemoryKindChange(it) },
                        onDueHintChange = { requireNotNull(resolvedViewModel).onFirstMemoryDueHintChange(it) },
                        onSave = {
                            if (onCompleteSetup != null) {
                                onCompleteSetup.invoke()
                                onNavigateToday?.invoke()
                            } else {
                                requireNotNull(resolvedViewModel).onSaveFirstMemory()
                            }
                        },
                        onSkip = {
                            if (onCompleteSetup != null) {
                                onCompleteSetup.invoke()
                                onNavigateToday?.invoke()
                            } else {
                                requireNotNull(resolvedViewModel).onSkipFirstMemory()
                            }
                        },
                    )
                }
            }
        }
        if (state.firstMemoryExitPromptVisible) {
            FirstMemoryExitPrompt(
                onKeep = { resolvedViewModel?.onKeepFirstMemoryDraft() },
                onDiscard = { resolvedViewModel?.onDiscardFirstMemoryDraft() },
            )
        }
    }
}

@Composable
private fun FirstMemoryExitPrompt(
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(text = stringResource(R.string.first_memory_exit_title)) },
        text = { Text(text = stringResource(R.string.first_memory_exit_body)) },
        confirmButton = {
            TextButton(onClick = onKeep) {
                Text(text = stringResource(R.string.first_memory_exit_keep))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(text = stringResource(R.string.first_memory_exit_discard))
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
        StepStatus.IN_PROGRESS -> SourceConnectionState.Syncing
        StepStatus.NOT_STARTED -> SourceConnectionState.Idle
    }

private fun OnboardingUiState.introSourceProviderNeedingRefresh(): OnboardingSourceProvider? {
    return introSourceProviderNeedingRefresh(
        setupStage = setupStage,
        introPageIndex = introPageIndex,
        stepStates = stepStates,
    )
}

private fun introSourceProviderNeedingRefresh(
    setupStage: OnboardingSetupStage,
    introPageIndex: Int,
    stepStates: Map<OnboardingStep, StepStatus>,
): OnboardingSourceProvider? {
    if (setupStage != OnboardingSetupStage.INTRO) return null
    val provider = when (introPageIndex) {
        ONBOARDING_INTRO_CALENDAR_PAGE_INDEX -> OnboardingSourceProvider.GOOGLE_CALENDAR
        ONBOARDING_INTRO_EMAIL_PAGE_INDEX -> OnboardingSourceProvider.GMAIL
        else -> null
    } ?: return null
    return if (stepStates[provider.step] == StepStatus.COMPLETE) null else provider
}

private const val ONBOARDING_INTRO_CALENDAR_PAGE_INDEX = 3
private const val ONBOARDING_INTRO_EMAIL_PAGE_INDEX = ONBOARDING_INTRO_PAGE_COUNT - 1
