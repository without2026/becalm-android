package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSourceReconnectOr
import com.becalm.android.ui.navigation.returnToSettingsSourcesAfterSourceConnect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
public fun OnboardingSourcesScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    emailEventsOverride: Flow<EmailConnectEvent>? = null,
    calendarEventsOverride: Flow<CalendarConnectEvent>? = null,
    stateOverride: OnboardingUiState? = null,
    onConnectSource: ((OnboardingSourceProvider, Activity) -> Unit)? = null,
    onSkipSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onSkipRemaining: (() -> Unit)? = null,
    onPersistEmailConsent: (suspend (EmailPipaProvider) -> Boolean)? = null,
    onRefreshSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onNavigateNext: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
) {
    SourceConnectionsScreen(
        navController = navController,
        entryPoint = SourceConnectionsEntryPoint.Onboarding,
        viewModel = viewModel,
        emailEventsOverride = emailEventsOverride,
        calendarEventsOverride = calendarEventsOverride,
        stateOverride = stateOverride,
        onConnectSource = onConnectSource,
        onSkipSource = onSkipSource,
        onSkipRemaining = onSkipRemaining,
        onPersistEmailConsent = onPersistEmailConsent,
        onRefreshSource = onRefreshSource,
        onNavigateComplete = onNavigateNext,
        onLaunchPendingIntent = onLaunchPendingIntent,
    )
}

@Composable
public fun SettingsSourceConnectionsScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    emailEventsOverride: Flow<EmailConnectEvent>? = null,
    calendarEventsOverride: Flow<CalendarConnectEvent>? = null,
    stateOverride: OnboardingUiState? = null,
    onConnectSource: ((OnboardingSourceProvider, Activity) -> Unit)? = null,
    onPersistEmailConsent: (suspend (EmailPipaProvider) -> Boolean)? = null,
    onRefreshSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onNavigateDone: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
) {
    SourceConnectionsScreen(
        navController = navController,
        entryPoint = SourceConnectionsEntryPoint.Settings,
        viewModel = viewModel,
        emailEventsOverride = emailEventsOverride,
        calendarEventsOverride = calendarEventsOverride,
        stateOverride = stateOverride,
        onConnectSource = onConnectSource,
        onPersistEmailConsent = onPersistEmailConsent,
        onRefreshSource = onRefreshSource,
        onNavigateComplete = onNavigateDone,
        onLaunchPendingIntent = onLaunchPendingIntent,
    )
}

@Composable
internal fun SourceConnectionsScreen(
    navController: NavHostController,
    entryPoint: SourceConnectionsEntryPoint,
    viewModel: OnboardingViewModel? = null,
    emailEventsOverride: Flow<EmailConnectEvent>? = null,
    calendarEventsOverride: Flow<CalendarConnectEvent>? = null,
    stateOverride: OnboardingUiState? = null,
    onConnectSource: ((OnboardingSourceProvider, Activity) -> Unit)? = null,
    onSkipSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onSkipRemaining: (() -> Unit)? = null,
    onPersistEmailConsent: (suspend (EmailPipaProvider) -> Boolean)? = null,
    onRefreshSource: ((OnboardingSourceProvider) -> Unit)? = null,
    onCompleteSetup: (() -> Unit)? = null,
    onNavigateComplete: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
    setupItems: List<OnboardingSetupItemUi> = emptyList(),
    selfIdentity: OnboardingSelfIdentityUi? = null,
    onSelfDisplayNameChange: (String) -> Unit = {},
    onSelfPhoneChange: (String) -> Unit = {},
    onSaveSelfIdentity: () -> Unit = {},
    onConnectSetupItem: ((OnboardingSetupItem) -> Unit)? = null,
    onSkipSetupItem: ((OnboardingSetupItem) -> Unit)? = null,
) {
    val needsViewModel = emailEventsOverride == null ||
        calendarEventsOverride == null ||
        stateOverride == null ||
        onConnectSource == null ||
        (entryPoint != SourceConnectionsEntryPoint.Settings && onSkipSource == null) ||
        (entryPoint == SourceConnectionsEntryPoint.Onboarding && onSkipRemaining == null) ||
        (
            entryPoint == SourceConnectionsEntryPoint.Setup &&
                (onCompleteSetup == null || onConnectSetupItem == null || onSkipSetupItem == null)
            ) ||
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
    val resources = context.resources
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val transientStatesState = remember {
        mutableStateOf(emptyMap<OnboardingSourceProvider, SourceConnectionState>())
    }
    val pendingIntentProviderState = remember { mutableStateOf<OnboardingSourceProvider?>(null) }
    val transientStates = transientStatesState.value

    val connectSource = onConnectSource ?: { provider, hostActivity ->
        requireNotNull(resolvedViewModel).onConnectSourceProvider(provider, hostActivity)
    }
    val skipSource: (OnboardingSourceProvider) -> Unit = onSkipSource
        ?: { provider -> requireNotNull(resolvedViewModel).onSkipSourceProvider(provider) }
    val skipRemaining: (() -> Unit)? = when (entryPoint) {
        SourceConnectionsEntryPoint.Setup -> null
        SourceConnectionsEntryPoint.Onboarding -> onSkipRemaining ?: {
            requireNotNull(resolvedViewModel).onSkipRemainingSourceConnections()
        }
        SourceConnectionsEntryPoint.Settings -> null
    }
    val persistEmailConsent = onPersistEmailConsent ?: { provider ->
        requireNotNull(resolvedViewModel).onEmailPipaConsent(listOf(provider), granted = true)
    }
    val refreshSource = onRefreshSource ?: { provider ->
        requireNotNull(resolvedViewModel).refreshSourceProviderConnection(provider)
    }
    val navigateComplete = onNavigateComplete ?: when (entryPoint) {
        SourceConnectionsEntryPoint.Setup -> {
            {
                navController.navigate(BecalmRoute.Today.path) {
                    popUpTo(BecalmRoute.OnboardingSetup.path) { inclusive = true }
                }
            }
        }
        SourceConnectionsEntryPoint.Onboarding -> {
            { navController.navigateAfterSourceReconnectOr(BecalmRoute.OnboardingNotificationPerm.path) }
        }
        SourceConnectionsEntryPoint.Settings -> {
            { navController.returnToSettingsSourcesAfterSourceConnect() }
        }
    }

    val pendingIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        val provider = pendingIntentProviderState.value
        val hostActivity = activity
        if (provider != null && hostActivity != null) {
            connectSource(provider, hostActivity)
        }
    }
    val launchPendingIntent = onLaunchPendingIntent ?: { request ->
        pendingIntentLauncher.launch(request)
    }

    val activityMissingCopy = stringResource(R.string.onb_sources_activity_missing)
    val consentWriteFailedCopy = stringResource(R.string.onb_sources_consent_write_failed)

    SourceConnectionLifecycleRefreshEffect(
        lifecycleOwner = lifecycleOwner,
        transientStates = transientStates,
        onRefreshSource = refreshSource,
    )
    SourceConnectionEmailEventEffect(
        events = emailEventsOverride ?: requireNotNull(resolvedViewModel).emailConnectEvents,
        entryPoint = entryPoint,
        resources = resources,
        snackbarHostState = snackbarHostState,
        transientStates = transientStatesState,
        pendingIntentProvider = pendingIntentProviderState,
        onLaunchPendingIntent = launchPendingIntent,
        onConnected = {
            if (entryPoint == SourceConnectionsEntryPoint.Settings) {
                navigateComplete()
            }
        },
    )
    SourceConnectionCalendarEventEffect(
        events = calendarEventsOverride ?: requireNotNull(resolvedViewModel).calendarConnectEvents,
        entryPoint = entryPoint,
        resources = resources,
        snackbarHostState = snackbarHostState,
        transientStates = transientStatesState,
        onConnected = {
            if (entryPoint == SourceConnectionsEntryPoint.Settings) {
                navigateComplete()
            }
        },
    )

    val items = SourceConnectionProjector.sourceConnectionItems(
        stepStates = state.stepStates,
        transientStates = transientStates,
        respectStepStates = SourceConnectionProjector.respectStepStatesFor(entryPoint),
        includedProviders = SourceConnectionProjector.sourceProvidersFor(entryPoint),
        stringFor = resources::getString,
    )
    val hasIncomplete = entryPoint == SourceConnectionsEntryPoint.Onboarding &&
        items.any { item ->
            item.state !in setOf(SourceConnectionState.Connected, SourceConnectionState.Skipped)
        }
    val copy = SourceConnectionCopy.copyFor(entryPoint)
    BecalmScaffold(
        title = stringResource(copy.titleRes),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        SourceConnectionsContent(
            items = items,
            headline = stringResource(copy.headlineRes),
            body = stringResource(copy.bodyRes),
            continueLabel = stringResource(SourceConnectionCopy.continueLabelRes(entryPoint, hasIncomplete)),
            skipLabel = stringResource(SourceConnectionCopy.skipLabelRes(entryPoint)),
            onConnect = { provider ->
                val hostActivity = activity
                if (hostActivity == null) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            activityMissingCopy,
                        )
                    }
                    return@SourceConnectionsContent
                }
                scope.launch {
                    val emailProvider = provider.emailProvider
                    if (emailProvider != null && !persistEmailConsent(emailProvider)) {
                        snackbarHostState.showSnackbar(consentWriteFailedCopy)
                        return@launch
                    }
                    transientStatesState.value = transientStatesState.value +
                        (provider to SourceConnectionState.PendingExternalAuth)
                    connectSource(provider, hostActivity)
                }
            },
            onSkip = { provider ->
                transientStatesState.value = transientStatesState.value - provider
                skipSource(provider)
            },
            setupItems = setupItems,
            selfIdentity = selfIdentity,
            onSelfDisplayNameChange = onSelfDisplayNameChange,
            onSelfPhoneChange = onSelfPhoneChange,
            onSaveSelfIdentity = onSaveSelfIdentity,
            onConnectSetupItem = onConnectSetupItem ?: {},
            onSkipSetupItem = onSkipSetupItem ?: {},
            onContinue = {
                if (entryPoint == SourceConnectionsEntryPoint.Setup) {
                    (onCompleteSetup ?: { requireNotNull(resolvedViewModel).onCompleteSetup() }).invoke()
                } else if (entryPoint == SourceConnectionsEntryPoint.Onboarding) {
                    requireNotNull(skipRemaining).invoke()
                }
                navigateComplete()
            },
            modifier = Modifier.padding(padding),
        )
    }
}
