package com.becalm.android.ui.onboarding

import android.content.res.Resources
import androidx.activity.result.IntentSenderRequest
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

@Composable
internal fun SourceConnectionLifecycleRefreshEffect(
    lifecycleOwner: LifecycleOwner,
    transientStates: Map<OnboardingSourceProvider, SourceConnectionState>,
    onRefreshSource: (OnboardingSourceProvider) -> Unit,
) {
    DisposableEffect(lifecycleOwner, transientStates) {
        val waitingProviders = transientStates
            .filterValues { it.isBusy }
            .keys
        if (waitingProviders.isEmpty()) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    waitingProviders.forEach(onRefreshSource)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
}

@Composable
internal fun SourceConnectionVisibleProviderRefreshEffect(
    lifecycleOwner: LifecycleOwner,
    provider: OnboardingSourceProvider?,
    onRefreshSource: (OnboardingSourceProvider) -> Unit,
) {
    LaunchedEffect(provider) {
        provider?.let(onRefreshSource)
    }
    DisposableEffect(lifecycleOwner, provider) {
        if (provider == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    onRefreshSource(provider)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
}

@Composable
internal fun SourceConnectionEmailEventEffect(
    events: Flow<EmailConnectEvent>,
    entryPoint: SourceConnectionsEntryPoint,
    resources: Resources,
    snackbarHostState: SnackbarHostState,
    transientStates: MutableState<Map<OnboardingSourceProvider, SourceConnectionState>>,
    pendingIntentProvider: MutableState<OnboardingSourceProvider?>,
    onLaunchPendingIntent: (IntentSenderRequest) -> Unit,
    onConnected: (OnboardingSourceProvider) -> Unit = {},
) {
    LaunchedEffect(events, entryPoint, resources) {
        val syncingSnackbarShown = mutableSetOf<OnboardingSourceProvider>()
        events
            .filter { it.provider == EmailPipaProvider.GMAIL || it.provider == EmailPipaProvider.OUTLOOK_MAIL }
            .collect { event ->
                val provider = event.provider.onboardingSourceProvider() ?: return@collect
                when (event) {
                    is EmailConnectEvent.Syncing -> {
                        transientStates.value = transientStates.value + (provider to SourceConnectionState.Syncing)
                        if (
                            entryPoint != SourceConnectionsEntryPoint.Settings &&
                            syncingSnackbarShown.add(provider)
                        ) {
                            snackbarHostState.showSnackbar(
                                message = resources.getString(R.string.source_connection_completed_message),
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                    is EmailConnectEvent.Connected -> {
                        transientStates.value = connectedTransientState(
                            current = transientStates.value,
                            provider = provider,
                            entryPoint = entryPoint,
                        )
                        onConnected(provider)
                    }
                    is EmailConnectEvent.NotConnected -> {
                        syncingSnackbarShown.remove(provider)
                        transientStates.value = transientStates.value - provider
                    }
                    is EmailConnectEvent.PendingIntentRequired -> {
                        pendingIntentProvider.value = provider
                        transientStates.value = transientStates.value +
                            (provider to SourceConnectionState.PendingExternalAuth)
                        onLaunchPendingIntent(IntentSenderRequest.Builder(event.pendingIntent).build())
                    }
                    is EmailConnectEvent.Failed -> {
                        syncingSnackbarShown.remove(provider)
                        transientStates.value = if (event.errorCode == "user_cancelled") {
                            transientStates.value - provider
                        } else {
                            transientStates.value + (provider to SourceConnectionState.Failed)
                        }
                        if (event.errorCode != "user_cancelled") {
                            snackbarHostState.showSnackbar(
                                resources.getString(
                                    SourceConnectionProjector.emailErrorMessageRes(
                                        provider = event.provider,
                                        errorCode = event.errorCode,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
    }
}

@Composable
internal fun SourceConnectionCalendarEventEffect(
    events: Flow<CalendarConnectEvent>,
    entryPoint: SourceConnectionsEntryPoint,
    resources: Resources,
    snackbarHostState: SnackbarHostState,
    transientStates: MutableState<Map<OnboardingSourceProvider, SourceConnectionState>>,
    onConnected: (OnboardingSourceProvider) -> Unit = {},
) {
    LaunchedEffect(events, entryPoint, resources) {
        val syncingSnackbarShown = mutableSetOf<OnboardingSourceProvider>()
        events.collect { event ->
            val provider = event.provider.onboardingSourceProvider()
            when (event) {
                is CalendarConnectEvent.Syncing -> {
                    transientStates.value = transientStates.value + (provider to SourceConnectionState.Syncing)
                    if (
                        entryPoint != SourceConnectionsEntryPoint.Settings &&
                        syncingSnackbarShown.add(provider)
                    ) {
                        snackbarHostState.showSnackbar(
                            message = resources.getString(R.string.source_connection_completed_message),
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
                is CalendarConnectEvent.Connected -> {
                    transientStates.value = connectedTransientState(
                        current = transientStates.value,
                        provider = provider,
                        entryPoint = entryPoint,
                    )
                    onConnected(provider)
                }
                is CalendarConnectEvent.NotConnected -> {
                    syncingSnackbarShown.remove(provider)
                    transientStates.value = transientStates.value - provider
                }
                is CalendarConnectEvent.Failed -> {
                    syncingSnackbarShown.remove(provider)
                    transientStates.value = transientStates.value + (provider to SourceConnectionState.Failed)
                    snackbarHostState.showSnackbar(
                        resources.getString(
                            SourceConnectionProjector.calendarErrorMessageRes(
                                provider = event.provider,
                                errorCode = event.errorCode,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

private fun connectedTransientState(
    current: Map<OnboardingSourceProvider, SourceConnectionState>,
    provider: OnboardingSourceProvider,
    entryPoint: SourceConnectionsEntryPoint,
): Map<OnboardingSourceProvider, SourceConnectionState> =
    if (entryPoint == SourceConnectionsEntryPoint.Settings) {
        current + (provider to SourceConnectionState.Connected)
    } else {
        current - provider
    }
