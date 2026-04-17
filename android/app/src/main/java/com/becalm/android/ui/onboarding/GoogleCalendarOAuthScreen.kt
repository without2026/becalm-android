package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Onboarding step: Google Calendar OAuth connection placeholder.
 *
 * // TODO(BECALM-OAUTH-001): wire real Google Calendar OAuth via Google Identity Services SDK.
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
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    BecalmScaffold(title = stringResource(R.string.onb_gcal_title)) { padding ->
        OAuthPlaceholderContent(
            modifier = Modifier.padding(padding),
            headline = stringResource(R.string.onb_gcal_headline),
            body = stringResource(R.string.onb_gcal_body),
            connectLabel = stringResource(R.string.action_connect),
            onConnect = {
                // TODO(BECALM-OAUTH-001): wire real Google Calendar OAuth
                viewModel.onMarkStepStatus(OnboardingStep.CALENDAR_PERM, StepStatus.COMPLETE)
                navController.navigate(BecalmRoute.OnboardingOutlookCalendar.path)
            },
            onSkip = {
                viewModel.onSkipStep()
                navController.navigate(BecalmRoute.OnboardingOutlookCalendar.path)
            },
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewGoogleCalendarOAuthScreen() {
    BecalmTheme {
        GoogleCalendarOAuthScreen(navController = rememberNavController())
    }
}
