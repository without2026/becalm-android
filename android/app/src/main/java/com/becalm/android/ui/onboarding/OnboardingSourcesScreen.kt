package com.becalm.android.ui.onboarding

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSourceReconnectOr
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
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

private enum class SourceConnectionsEntryPoint {
    Onboarding,
    Settings,
}

@Composable
private fun SourceConnectionsScreen(
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
    onNavigateComplete: (() -> Unit)? = null,
    onLaunchPendingIntent: ((IntentSenderRequest) -> Unit)? = null,
) {
    val needsViewModel = emailEventsOverride == null ||
        calendarEventsOverride == null ||
        stateOverride == null ||
        onConnectSource == null ||
        (entryPoint == SourceConnectionsEntryPoint.Onboarding && onSkipSource == null) ||
        (entryPoint == SourceConnectionsEntryPoint.Onboarding && onSkipRemaining == null) ||
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
    val activity = LocalContext.current as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var transientStates by remember { mutableStateOf(emptyMap<OnboardingSourceProvider, SourceConnectionState>()) }
    var pendingIntentProvider by remember { mutableStateOf<OnboardingSourceProvider?>(null) }

    val connectSource = onConnectSource ?: { provider, hostActivity ->
        requireNotNull(resolvedViewModel).onConnectSourceProvider(provider, hostActivity)
    }
    val skipSource: (OnboardingSourceProvider) -> Unit = onSkipSource
        ?: { provider -> requireNotNull(resolvedViewModel).onSkipSourceProvider(provider) }
    val skipRemaining: (() -> Unit)? = when (entryPoint) {
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
        SourceConnectionsEntryPoint.Onboarding -> {
            { navController.navigateAfterSourceReconnectOr(BecalmRoute.OnboardingNotificationPerm.path) }
        }
        SourceConnectionsEntryPoint.Settings -> {
            {
                if (!navController.popBackStack()) {
                    navController.navigate(BecalmRoute.SettingsSources.path)
                }
            }
        }
    }

    val pendingIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        val provider = pendingIntentProvider
        val hostActivity = activity
        if (provider != null && hostActivity != null) {
            connectSource(provider, hostActivity)
        }
    }
    val launchPendingIntent = onLaunchPendingIntent ?: { request ->
        pendingIntentLauncher.launch(request)
    }

    val emailErrorCopy = emailErrorCopy()
    val calendarErrorCopy = calendarErrorCopy()
    val activityMissingCopy = stringResource(R.string.onb_sources_activity_missing)
    val consentWriteFailedCopy = stringResource(R.string.onb_sources_consent_write_failed)

    DisposableEffect(lifecycleOwner, transientStates, emailEventsOverride, calendarEventsOverride) {
        val waitingProviders = transientStates
            .filterValues { it == SourceConnectionState.PendingExternalAuth || it == SourceConnectionState.Connecting }
            .keys
        if (waitingProviders.isEmpty()) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    waitingProviders.forEach(refreshSource)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(emailEventsOverride, resolvedViewModel) {
        (emailEventsOverride ?: requireNotNull(resolvedViewModel).emailConnectEvents)
            .filter { it.provider == EmailPipaProvider.GMAIL || it.provider == EmailPipaProvider.OUTLOOK_MAIL }
            .collect { event ->
                val provider = event.provider.onboardingSourceProvider() ?: return@collect
                when (event) {
                    is EmailConnectEvent.Connected -> {
                        transientStates = if (entryPoint == SourceConnectionsEntryPoint.Settings) {
                            transientStates + (provider to SourceConnectionState.Connected)
                        } else {
                            transientStates - provider
                        }
                    }
                    is EmailConnectEvent.PendingIntentRequired -> {
                        pendingIntentProvider = provider
                        transientStates = transientStates + (provider to SourceConnectionState.PendingExternalAuth)
                        launchPendingIntent(IntentSenderRequest.Builder(event.pendingIntent).build())
                    }
                    is EmailConnectEvent.Failed -> {
                        transientStates = if (event.errorCode == "user_cancelled") {
                            transientStates - provider
                        } else {
                            transientStates + (provider to SourceConnectionState.Failed)
                        }
                        if (event.errorCode != "user_cancelled") {
                            snackbarHostState.showSnackbar(
                                emailErrorCopy[event.provider to event.errorCode]
                                    ?: emailErrorCopy[event.provider to "unknown"]
                                    ?: "",
                            )
                        }
                    }
                }
            }
    }

    LaunchedEffect(calendarEventsOverride, resolvedViewModel) {
        (calendarEventsOverride ?: requireNotNull(resolvedViewModel).calendarConnectEvents)
            .collect { event ->
                val provider = event.provider.onboardingSourceProvider()
                when (event) {
                    is CalendarConnectEvent.Connected -> {
                        transientStates = if (entryPoint == SourceConnectionsEntryPoint.Settings) {
                            transientStates + (provider to SourceConnectionState.Connected)
                        } else {
                            transientStates - provider
                        }
                    }
                    is CalendarConnectEvent.Failed -> {
                        transientStates = transientStates + (provider to SourceConnectionState.Failed)
                        snackbarHostState.showSnackbar(
                            calendarErrorCopy[event.provider to event.errorCode]
                                ?: calendarErrorCopy[event.provider to "unknown"]
                                ?: "",
                        )
                    }
                }
            }
    }

    val items = sourceConnectionItems(
        stepStates = state.stepStates,
        transientStates = transientStates,
        respectStepStates = entryPoint == SourceConnectionsEntryPoint.Onboarding,
    )
    val hasIncomplete = entryPoint == SourceConnectionsEntryPoint.Onboarding &&
        items.any { item ->
            item.state !in setOf(SourceConnectionState.Connected, SourceConnectionState.Skipped)
        }

    BecalmScaffold(
        title = stringResource(
            when (entryPoint) {
                SourceConnectionsEntryPoint.Onboarding -> R.string.onb_sources_title
                SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_title
            },
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        SourceConnectionsContent(
            items = items,
            headline = stringResource(
                when (entryPoint) {
                    SourceConnectionsEntryPoint.Onboarding -> R.string.onb_sources_headline
                    SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_headline
                },
            ),
            body = stringResource(
                when (entryPoint) {
                    SourceConnectionsEntryPoint.Onboarding -> R.string.onb_sources_body
                    SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_body
                },
            ),
            continueLabel = stringResource(
                when {
                    entryPoint == SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_done
                    hasIncomplete -> R.string.onb_sources_skip_remaining
                    else -> R.string.onb_sources_continue
                },
            ),
            skipLabel = stringResource(
                when (entryPoint) {
                    SourceConnectionsEntryPoint.Onboarding -> R.string.action_skip
                    SourceConnectionsEntryPoint.Settings -> R.string.settings_source_connections_not_now
                },
            ),
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
                    transientStates = transientStates + (provider to SourceConnectionState.PendingExternalAuth)
                    connectSource(provider, hostActivity)
                }
            },
            onSkip = { provider ->
                transientStates = transientStates - provider
                skipSource(provider)
            },
            onContinue = {
                if (entryPoint == SourceConnectionsEntryPoint.Onboarding) {
                    requireNotNull(skipRemaining).invoke()
                }
                navigateComplete()
            },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun SourceConnectionsContent(
    items: List<SourceConnectionItemUi>,
    headline: String = stringResource(R.string.onb_sources_headline),
    body: String = stringResource(R.string.onb_sources_body),
    continueLabel: String,
    skipLabel: String = stringResource(R.string.action_skip),
    onConnect: (OnboardingSourceProvider) -> Unit,
    onSkip: (OnboardingSourceProvider) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mailSection = stringResource(R.string.onb_sources_mail_section)
    val calendarSection = stringResource(R.string.onb_sources_calendar_section)
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = headline,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        sourceSection(
            title = mailSection,
            items = items.filter { it.category == SourceConnectionCategory.Mail },
            onConnect = onConnect,
            onSkip = onSkip,
            skipLabel = skipLabel,
        )
        sourceSection(
            title = calendarSection,
            items = items.filter { it.category == SourceConnectionCategory.Calendar },
            onConnect = onConnect,
            onSkip = onSkip,
            skipLabel = skipLabel,
        )
        item {
            Spacer(modifier = Modifier.height(8.dp))
            BecalmButton(
                text = continueLabel,
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sourceSection(
    title: String,
    items: List<SourceConnectionItemUi>,
    onConnect: (OnboardingSourceProvider) -> Unit,
    onSkip: (OnboardingSourceProvider) -> Unit,
    skipLabel: String,
) {
    item(key = "$title-title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(items = items, key = { item -> item.provider.name }) { item ->
        SourceConnectionRow(
            item = item,
            onConnect = { onConnect(item.provider) },
            onSkip = { onSkip(item.provider) },
            skipLabel = skipLabel,
        )
    }
}

@Composable
private fun SourceConnectionRow(
    item: SourceConnectionItemUi,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(MaterialTheme.shapes.medium)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = stateLabel(item.state),
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
        if (item.consentCopy != null && item.state == SourceConnectionState.ConsentRequired) {
            Text(
                text = item.consentCopy,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val terminal = item.state == SourceConnectionState.Connected || item.state == SourceConnectionState.Skipped
            BecalmButton(
                text = connectLabel(item.state, item.consentCopy != null),
                onClick = onConnect,
                enabled = !terminal && item.state != SourceConnectionState.Connecting &&
                    item.state != SourceConnectionState.PendingExternalAuth,
                loading = item.state == SourceConnectionState.Connecting ||
                    item.state == SourceConnectionState.PendingExternalAuth,
                modifier = Modifier.weight(1f),
            )
            BecalmButton(
                text = skipLabel,
                onClick = onSkip,
                variant = BecalmButtonVariant.Text,
                enabled = !terminal,
            )
        }
    }
}

@Composable
private fun connectLabel(state: SourceConnectionState, requiresConsent: Boolean): String =
    when {
        state == SourceConnectionState.Failed -> stringResource(R.string.onb_sources_retry)
        requiresConsent -> stringResource(R.string.onb_sources_connect_with_consent)
        else -> stringResource(R.string.action_connect)
    }

@Composable
private fun stateLabel(state: SourceConnectionState): String =
    stringResource(
        when (state) {
            SourceConnectionState.Idle -> R.string.onb_sources_status_ready
            SourceConnectionState.ConsentRequired -> R.string.onb_sources_status_consent
            SourceConnectionState.Connecting -> R.string.onb_sources_status_connecting
            SourceConnectionState.PendingExternalAuth -> R.string.onb_sources_status_waiting
            SourceConnectionState.Connected -> R.string.onb_sources_status_connected
            SourceConnectionState.Skipped -> R.string.onb_sources_status_skipped
            SourceConnectionState.Failed -> R.string.onb_sources_status_failed
        },
    )

@Composable
private fun sourceConnectionItems(
    stepStates: Map<OnboardingStep, StepStatus>,
    transientStates: Map<OnboardingSourceProvider, SourceConnectionState>,
    respectStepStates: Boolean = true,
): List<SourceConnectionItemUi> =
    listOf(
        SourceConnectionItemUi(
            provider = OnboardingSourceProvider.GMAIL,
            category = SourceConnectionCategory.Mail,
            title = stringResource(R.string.onb_gmail_title),
            description = stringResource(R.string.onb_gmail_body),
            consentCopy = stringResource(R.string.onb_sources_mail_consent_body),
            state = sourceStateFor(
                provider = OnboardingSourceProvider.GMAIL,
                stepStates = stepStates,
                transientStates = transientStates,
                respectStepStates = respectStepStates,
                defaultState = SourceConnectionState.ConsentRequired,
            ),
        ),
        SourceConnectionItemUi(
            provider = OnboardingSourceProvider.OUTLOOK_MAIL,
            category = SourceConnectionCategory.Mail,
            title = stringResource(R.string.onb_outlook_mail_title),
            description = stringResource(R.string.onb_outlook_mail_body),
            consentCopy = stringResource(R.string.onb_sources_mail_consent_body),
            state = sourceStateFor(
                provider = OnboardingSourceProvider.OUTLOOK_MAIL,
                stepStates = stepStates,
                transientStates = transientStates,
                respectStepStates = respectStepStates,
                defaultState = SourceConnectionState.ConsentRequired,
            ),
        ),
        SourceConnectionItemUi(
            provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
            category = SourceConnectionCategory.Calendar,
            title = stringResource(R.string.onb_gcal_title),
            description = stringResource(R.string.onb_gcal_body),
            consentCopy = null,
            state = sourceStateFor(
                provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                stepStates = stepStates,
                transientStates = transientStates,
                respectStepStates = respectStepStates,
            ),
        ),
        SourceConnectionItemUi(
            provider = OnboardingSourceProvider.OUTLOOK_CALENDAR,
            category = SourceConnectionCategory.Calendar,
            title = stringResource(R.string.onb_outlook_cal_title),
            description = stringResource(R.string.onb_outlook_cal_body),
            consentCopy = null,
            state = sourceStateFor(
                provider = OnboardingSourceProvider.OUTLOOK_CALENDAR,
                stepStates = stepStates,
                transientStates = transientStates,
                respectStepStates = respectStepStates,
            ),
        ),
    )

private fun sourceStateFor(
    provider: OnboardingSourceProvider,
    stepStates: Map<OnboardingStep, StepStatus>,
    transientStates: Map<OnboardingSourceProvider, SourceConnectionState>,
    respectStepStates: Boolean,
    defaultState: SourceConnectionState = SourceConnectionState.Idle,
): SourceConnectionState {
    if (!respectStepStates) return transientStates[provider] ?: defaultState
    return when (stepStates[provider.step] ?: StepStatus.NOT_STARTED) {
        StepStatus.GRANTED,
        StepStatus.COMPLETE,
        -> SourceConnectionState.Connected
        StepStatus.SKIPPED,
        StepStatus.DENIED,
        -> SourceConnectionState.Skipped
        StepStatus.IN_PROGRESS,
        StepStatus.NOT_STARTED,
        -> transientStates[provider] ?: defaultState
    }
}

@Composable
private fun emailErrorCopy(): Map<Pair<EmailPipaProvider, String>, String> =
    mapOf(
        EmailPipaProvider.GMAIL to "network_error" to stringResource(R.string.onb_gmail_error_network),
        EmailPipaProvider.GMAIL to "network" to stringResource(R.string.onb_gmail_error_network),
        EmailPipaProvider.GMAIL to "scope_denied" to stringResource(R.string.onb_gmail_error_permission_denied),
        EmailPipaProvider.GMAIL to "pipa_consent_missing" to stringResource(R.string.onb_sources_consent_write_failed),
        EmailPipaProvider.GMAIL to "unknown" to stringResource(R.string.onb_gmail_error_unknown),
        EmailPipaProvider.OUTLOOK_MAIL to "network_error" to stringResource(R.string.onb_outlook_error_network),
        EmailPipaProvider.OUTLOOK_MAIL to "network" to stringResource(R.string.onb_outlook_error_network),
        EmailPipaProvider.OUTLOOK_MAIL to "scope_denied" to stringResource(R.string.onb_outlook_error_permission_denied),
        EmailPipaProvider.OUTLOOK_MAIL to "pipa_consent_missing" to stringResource(R.string.onb_sources_consent_write_failed),
        EmailPipaProvider.OUTLOOK_MAIL to "unknown" to stringResource(R.string.onb_outlook_error_unknown),
    )

@Composable
private fun calendarErrorCopy(): Map<Pair<CalendarOAuthProvider, String>, String> =
    mapOf(
        CalendarOAuthProvider.GOOGLE_CALENDAR to "not_implemented" to stringResource(R.string.onb_gcal_error_unavailable),
        CalendarOAuthProvider.GOOGLE_CALENDAR to "oauth_not_configured" to stringResource(R.string.onb_gcal_error_unavailable),
        CalendarOAuthProvider.GOOGLE_CALENDAR to "browser_unavailable" to stringResource(R.string.onb_gcal_error_unknown),
        CalendarOAuthProvider.GOOGLE_CALENDAR to "network_error" to stringResource(R.string.onb_gcal_error_unknown),
        CalendarOAuthProvider.GOOGLE_CALENDAR to "unknown" to stringResource(R.string.onb_gcal_error_unknown),
        CalendarOAuthProvider.OUTLOOK_CALENDAR to "not_implemented" to stringResource(R.string.onb_outlook_cal_error_unavailable),
        CalendarOAuthProvider.OUTLOOK_CALENDAR to "oauth_not_configured" to stringResource(R.string.onb_outlook_cal_error_unavailable),
        CalendarOAuthProvider.OUTLOOK_CALENDAR to "browser_unavailable" to stringResource(R.string.onb_outlook_cal_error_unknown),
        CalendarOAuthProvider.OUTLOOK_CALENDAR to "network_error" to stringResource(R.string.onb_outlook_cal_error_unknown),
        CalendarOAuthProvider.OUTLOOK_CALENDAR to "unknown" to stringResource(R.string.onb_outlook_cal_error_unknown),
    )

@PreviewLightDark
@Composable
private fun PreviewSourceConnectionsContent() {
    BecalmTheme {
        SourceConnectionsContent(
            items = sourceConnectionItems(
                stepStates = mapOf(
                    OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE,
                    OnboardingStep.LINK_OUTLOOK_MAIL to StepStatus.NOT_STARTED,
                    OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.SKIPPED,
                    OnboardingStep.LINK_OUTLOOK_CALENDAR to StepStatus.NOT_STARTED,
                ),
                transientStates = mapOf(
                    OnboardingSourceProvider.OUTLOOK_CALENDAR to SourceConnectionState.Failed,
                ),
            ),
            continueLabel = stringResource(R.string.onb_sources_skip_remaining),
            onConnect = {},
            onSkip = {},
            onContinue = {},
        )
    }
}
