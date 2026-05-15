package com.becalm.android.ui.onboarding

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSourceReconnectOr
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Onboarding step: Google Calendar OAuth connection.
 *
 * spec: ONB-001, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingGoogleCalendar]
 * Navigation exit: [BecalmRoute.OnboardingOutlookCalendar]
 */
@Composable
public fun GoogleCalendarOAuthScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    eventsOverride: Flow<CalendarConnectEvent>? = null,
    onConnect: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
    onNavigateDownstream: (() -> Unit)? = null,
) {
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingOAuthResumeRefresh by remember { mutableStateOf(false) }
    val downstream = BecalmRoute.OnboardingOutlookCalendar.path
    val onboardingViewModel = if (eventsOverride == null || onConnect == null || onSkip == null) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val navigateDownstream = onNavigateDownstream ?: { navController.navigateAfterSourceReconnectOr(downstream) }

    val errorCopyByCode = mapOf(
        "not_implemented" to stringResource(R.string.onb_gcal_error_unavailable),
        "oauth_not_configured" to stringResource(R.string.onb_gcal_error_unavailable),
        "browser_unavailable" to stringResource(R.string.onb_gcal_error_unknown),
        "oauth_timeout" to stringResource(R.string.onb_gcal_error_unknown),
        "unknown" to stringResource(R.string.onb_gcal_error_unknown),
    )

    DisposableEffect(lifecycleOwner, onboardingViewModel, eventsOverride, pendingOAuthResumeRefresh) {
        if (!pendingOAuthResumeRefresh || eventsOverride != null || onboardingViewModel == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    onboardingViewModel.refreshCalendarProviderConnection(CalendarOAuthProvider.GOOGLE_CALENDAR)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(eventsOverride, onboardingViewModel) {
        (eventsOverride ?: requireNotNull(onboardingViewModel).calendarConnectEvents)
            .filter { it.provider == CalendarOAuthProvider.GOOGLE_CALENDAR }
            .collect { event ->
                when (event) {
                    is CalendarConnectEvent.Connected -> {
                        pendingOAuthResumeRefresh = false
                        navigateDownstream()
                    }
                    is CalendarConnectEvent.Failed -> {
                        pendingOAuthResumeRefresh = false
                        snackbarHostState.showSnackbar(
                            errorCopyByCode[event.errorCode] ?: errorCopyByCode.getValue("unknown"),
                        )
                    }
                }
            }
    }

    BecalmScaffold(
        title = stringResource(R.string.onb_gcal_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        GoogleCalendarOAuthContent(
            modifier = Modifier.padding(padding),
            connectLoading = pendingOAuthResumeRefresh,
            onConnect = onConnect ?: {
                // Debounce: ignore taps while OAuth is already in flight (button
                // also visually disabled via connectLoading). Prevents the same
                // duplicate-OAuth-call pattern that triggered the Gmail crash on
                // the email path.
                if (!pendingOAuthResumeRefresh) {
                    val hostActivity = activity
                    if (hostActivity == null) {
                        scope.launch { snackbarHostState.showSnackbar(errorCopyByCode.getValue("unknown")) }
                    } else {
                        pendingOAuthResumeRefresh = true
                        requireNotNull(onboardingViewModel).onConnectCalendarProvider(
                            provider = CalendarOAuthProvider.GOOGLE_CALENDAR,
                            activity = hostActivity,
                        )
                    }
                }
                Unit
            },
            onSkip = onSkip ?: {
                requireNotNull(onboardingViewModel).onSkipCalendarSource(CalendarOAuthProvider.GOOGLE_CALENDAR)
                navigateDownstream()
                Unit
            },
        )
    }
}

@Composable
internal fun GoogleCalendarOAuthContent(
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    connectLoading: Boolean = false,
) {
    OAuthPlaceholderContent(
        modifier = modifier,
        headline = stringResource(R.string.onb_gcal_headline),
        body = stringResource(R.string.onb_gcal_body),
        connectLabel = stringResource(R.string.action_connect),
        onConnect = onConnect,
        onSkip = onSkip,
        connectLoading = connectLoading,
    )
}

@PreviewLightDark
@Composable
private fun PreviewGoogleCalendarOAuthScreen() {
    BecalmTheme {
        GoogleCalendarOAuthContent(
            onConnect = {},
            onSkip = {},
        )
    }
}
