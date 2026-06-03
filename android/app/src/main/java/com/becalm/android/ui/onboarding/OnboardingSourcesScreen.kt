package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.uiMessageStringResource
import com.becalm.android.ui.navigation.BecalmNavigationDefaults
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
    targetProviderSlug: String? = null,
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
    val targetProvider = onboardingSourceProviderFromSettingsRouteSlug(targetProviderSlug)
    SourceConnectionsScreen(
        navController = navController,
        entryPoint = SourceConnectionsEntryPoint.Settings,
        includedProviders = targetProvider?.let(::setOf),
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
    onSelfEmailChange: (String) -> Unit = {},
    onSelfPhoneChange: (String) -> Unit = {},
    onSelfAliasChange: (String) -> Unit = {},
    onSaveSelfIdentity: () -> Unit = {},
    sourceOwnerships: List<OnboardingSourceOwnershipUi>? = null,
    onConnectSetupItem: ((OnboardingSetupItem) -> Unit)? = null,
    onSkipSetupItem: ((OnboardingSetupItem) -> Unit)? = null,
    includedProviders: Set<OnboardingSourceProvider>? = null,
    actions: @Composable RowScope.() -> Unit = {},
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
                navController.navigate(BecalmNavigationDefaults.authenticatedHomeRoute) {
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
    SourceConnectionEmailEventEffect(
        events = emailEventsOverride ?: requireNotNull(resolvedViewModel).emailConnectEvents,
        entryPoint = entryPoint,
        resources = resources,
        snackbarHostState = snackbarHostState,
        transientStates = transientStatesState,
        pendingIntentProvider = pendingIntentProviderState,
        onLaunchPendingIntent = launchPendingIntent,
        onConnected = {
            if (
                entryPoint == SourceConnectionsEntryPoint.Settings &&
                (includedProviders == null || it in includedProviders)
            ) {
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
            if (
                entryPoint == SourceConnectionsEntryPoint.Settings &&
                (includedProviders == null || it in includedProviders)
            ) {
                navigateComplete()
            }
        },
    )

    val effectiveIncludedProviders = includedProviders ?: SourceConnectionProjector.sourceProvidersFor(entryPoint)
    val effectiveSourceOwnerships = (sourceOwnerships ?: if (entryPoint == SourceConnectionsEntryPoint.Settings) {
        state.sourceOwnerships
    } else {
        emptyList()
    }).filterForProviders(if (includedProviders == null) null else effectiveIncludedProviders)
    val existingConnectionProviders = if (entryPoint == SourceConnectionsEntryPoint.Settings) {
        effectiveSourceOwnerships.mapNotNull(OnboardingSourceOwnershipUi::toSourceProvider).toSet()
    } else {
        emptySet()
    }

    val items = SourceConnectionProjector.sourceConnectionItems(
        stepStates = state.stepStates,
        transientStates = transientStates,
        respectStepStates = SourceConnectionProjector.respectStepStatesFor(entryPoint),
        respectConnectedStepStates = entryPoint != SourceConnectionsEntryPoint.Settings || includedProviders == null,
        includedProviders = effectiveIncludedProviders,
        existingConnectionProviders = existingConnectionProviders,
        stringFor = resources::getString,
    )
    val hasIncomplete = entryPoint == SourceConnectionsEntryPoint.Onboarding &&
        items.any { item ->
            item.state !in setOf(SourceConnectionState.Connected, SourceConnectionState.Skipped)
        }
    val copy = SourceConnectionCopy.copyFor(entryPoint)
    val connectProvider: (OnboardingSourceProvider) -> Unit = connectProvider@{ provider ->
        val hostActivity = activity
        if (hostActivity == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    activityMissingCopy,
                )
            }
            return@connectProvider
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
    }
    val focusedSettingsItem = if (
        entryPoint == SourceConnectionsEntryPoint.Settings &&
        includedProviders != null &&
        items.size == 1
    ) {
        items.single()
    } else {
        null
    }
    BecalmScaffold(
        title = stringResource(copy.titleRes),
        actions = actions,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val onContinue = {
            if (entryPoint == SourceConnectionsEntryPoint.Setup) {
                if (onCompleteSetup != null) {
                    onCompleteSetup.invoke()
                    navigateComplete()
                } else {
                    requireNotNull(resolvedViewModel).onCompleteSetup()
                }
            } else if (entryPoint == SourceConnectionsEntryPoint.Onboarding) {
                requireNotNull(skipRemaining).invoke()
                navigateComplete()
            } else {
                navigateComplete()
            }
        }
        if (focusedSettingsItem != null) {
            FocusedSettingsSourceConnectionContent(
                item = focusedSettingsItem,
                connectedAccounts = effectiveSourceOwnerships,
                onConnect = { connectProvider(focusedSettingsItem.provider) },
                continueLabel = stringResource(SourceConnectionCopy.continueLabelRes(entryPoint, hasIncomplete)),
                onContinue = onContinue,
                continueEnabled = !state.isCompleting,
                continueLoading = state.isCompleting,
                modifier = Modifier.padding(padding),
            )
        } else {
            SourceConnectionsContent(
                items = items,
                headline = stringResource(copy.headlineRes),
                body = stringResource(copy.bodyRes),
                continueLabel = stringResource(SourceConnectionCopy.continueLabelRes(entryPoint, hasIncomplete)),
                skipLabel = stringResource(SourceConnectionCopy.skipLabelRes(entryPoint)),
                onConnect = connectProvider,
                onSkip = { provider ->
                    transientStatesState.value = transientStatesState.value - provider
                    skipSource(provider)
                },
                setupItems = setupItems,
                selfIdentity = selfIdentity,
                onSelfDisplayNameChange = onSelfDisplayNameChange,
                onSelfEmailChange = onSelfEmailChange,
                onSelfPhoneChange = onSelfPhoneChange,
                onSelfAliasChange = onSelfAliasChange,
                onSaveSelfIdentity = onSaveSelfIdentity,
                connectedAccounts = effectiveSourceOwnerships,
                onConnectSetupItem = onConnectSetupItem ?: {},
                onSkipSetupItem = onSkipSetupItem ?: {},
                continueEnabled = !state.isCompleting,
                continueLoading = state.isCompleting,
                showImapLaterNotice = entryPoint != SourceConnectionsEntryPoint.Settings,
                progressiveSetup = entryPoint == SourceConnectionsEntryPoint.Setup,
                onContinue = onContinue,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun FocusedSettingsSourceConnectionContent(
    item: SourceConnectionItemUi,
    connectedAccounts: List<OnboardingSourceOwnershipUi>,
    onConnect: () -> Unit,
    continueLabel: String,
    onContinue: () -> Unit,
    continueEnabled: Boolean,
    continueLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.testTag("source-connections-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "settings-source-story-hero") {
            FocusedSourceStoryHero(
                item = item,
            )
        }
        item(key = "settings-source-story-action") {
            FocusedSourceActionPanel(
                item = item,
                onConnect = onConnect,
            )
        }
        if (connectedAccounts.isNotEmpty()) {
            item(key = "connected-accounts-title") {
                Text(
                    text = stringResource(R.string.settings_identity_connections_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            items(connectedAccounts, key = { account -> account.id }) { account ->
                SourceConnectedAccountRow(item = account)
            }
        }
        item(key = "settings-source-story-done") {
            BecalmButton(
                text = continueLabel,
                onClick = onContinue,
                enabled = continueEnabled,
                loading = continueLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source-connections-continue"),
            )
        }
    }
}

@Composable
private fun FocusedSourceStoryHero(
    item: SourceConnectionItemUi,
) {
    SourceStoryHeader(
        icon = sourceStoryIcon(item.provider),
        headline = stringResource(focusedSourceHeadlineRes(item.provider)),
        body = stringResource(focusedSourceBodyRes(item.provider)),
    )
}

@Composable
private fun FocusedSourceActionPanel(
    item: SourceConnectionItemUi,
    onConnect: () -> Unit,
) {
    val busy = item.state.isBusy
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = focusedSourceActionTitle(item.state),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = focusedSourceActionBody(item.provider),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BecalmButton(
                text = focusedConnectLabel(item),
                onClick = onConnect,
                enabled = item.state != SourceConnectionState.Connected && !busy,
                loading = busy,
                variant = if (item.state == SourceConnectionState.Connected) {
                    BecalmButtonVariant.Secondary
                } else {
                    BecalmButtonVariant.Primary
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source-connection-primary"),
            )
        }
    }
}

@Composable
private fun focusedSourceActionTitle(state: SourceConnectionState): String =
    when (state) {
        SourceConnectionState.Connected -> stringResource(R.string.settings_source_story_connected_title)
        SourceConnectionState.Failed -> stringResource(R.string.settings_source_story_retry_title)
        SourceConnectionState.Connecting,
        SourceConnectionState.PendingExternalAuth,
        SourceConnectionState.Syncing,
        -> stringResource(R.string.settings_source_story_connecting_title)
        SourceConnectionState.Idle,
        SourceConnectionState.ConsentRequired,
        SourceConnectionState.Skipped,
        -> stringResource(R.string.settings_source_story_ready_title)
    }

@Composable
private fun focusedSourceActionBody(provider: OnboardingSourceProvider): String =
    stringResource(
        focusedSourceBodyRes(provider),
    )

private fun focusedSourceHeadlineRes(provider: OnboardingSourceProvider): Int =
    when (provider) {
        OnboardingSourceProvider.GMAIL,
        OnboardingSourceProvider.OUTLOOK_MAIL,
        -> R.string.onb_intro_email_title
        OnboardingSourceProvider.GOOGLE_CALENDAR,
        OnboardingSourceProvider.OUTLOOK_CALENDAR,
        -> R.string.onb_intro_calendar_title
    }

private fun focusedSourceBodyRes(provider: OnboardingSourceProvider): Int =
    when (provider) {
        OnboardingSourceProvider.GMAIL,
        OnboardingSourceProvider.OUTLOOK_MAIL,
        -> R.string.onb_intro_email_body
        OnboardingSourceProvider.GOOGLE_CALENDAR,
        OnboardingSourceProvider.OUTLOOK_CALENDAR,
        -> R.string.onb_intro_calendar_body
    }

@Composable
private fun focusedConnectLabel(item: SourceConnectionItemUi): String {
    item.primaryActionLabel?.let { return it }
    return when (item.state) {
        SourceConnectionState.Failed -> stringResource(R.string.onb_sources_retry)
        SourceConnectionState.Connected -> stringResource(R.string.onb_sources_status_connected)
        SourceConnectionState.Syncing -> stringResource(R.string.onb_sources_status_syncing)
        SourceConnectionState.ConsentRequired -> stringResource(R.string.onb_sources_connect_with_consent)
        else -> stringResource(R.string.action_connect)
    }
}

private fun sourceStoryIcon(provider: OnboardingSourceProvider): ImageVector =
    when (provider) {
        OnboardingSourceProvider.GMAIL,
        OnboardingSourceProvider.OUTLOOK_MAIL,
        -> Icons.Outlined.Email
        OnboardingSourceProvider.GOOGLE_CALENDAR,
        OnboardingSourceProvider.OUTLOOK_CALENDAR,
        -> Icons.Outlined.CalendarMonth
    }

private fun List<OnboardingSourceOwnershipUi>.filterForProviders(
    providers: Set<OnboardingSourceProvider>?,
): List<OnboardingSourceOwnershipUi> {
    if (providers == null) return this
    return filter { ownership -> ownership.toSourceProvider() in providers }
}

private fun OnboardingSourceOwnershipUi.toSourceProvider(): OnboardingSourceProvider? =
    when {
        provider == "google" && capability == "mail" -> OnboardingSourceProvider.GMAIL
        provider == "google" && capability == "calendar" -> OnboardingSourceProvider.GOOGLE_CALENDAR
        provider == "outlook" && capability == "mail" -> OnboardingSourceProvider.OUTLOOK_MAIL
        provider == "outlook" && capability == "calendar" -> OnboardingSourceProvider.OUTLOOK_CALENDAR
        else -> null
    }
