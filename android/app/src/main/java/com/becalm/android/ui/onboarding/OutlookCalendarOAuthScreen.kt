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
 * Onboarding step: Outlook Calendar OAuth connection placeholder.
 *
 * // TODO(BECALM-OAUTH-001): wire real Outlook Calendar OAuth via MSAL Android SDK.
 *
 * spec: ONB-001, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingOutlookCalendar]
 * Navigation exit: [BecalmRoute.OnboardingNotificationPerm]
 */
@Composable
public fun OutlookCalendarOAuthScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    BecalmScaffold(title = stringResource(R.string.onb_outlook_cal_title)) { padding ->
        OAuthPlaceholderContent(
            modifier = Modifier.padding(padding),
            headline = stringResource(R.string.onb_outlook_cal_headline),
            body = stringResource(R.string.onb_outlook_cal_body),
            connectLabel = stringResource(R.string.action_connect),
            onConnect = {
                // TODO(BECALM-OAUTH-001): wire real Outlook Calendar OAuth via MSAL
                viewModel.onMarkStepStatus(OnboardingStep.LINK_OUTLOOK_CALENDAR, StepStatus.COMPLETE)
                navController.navigate(BecalmRoute.OnboardingNotificationPerm.path)
            },
            onSkip = {
                viewModel.onSkipStep(OnboardingStep.LINK_OUTLOOK_CALENDAR)
                navController.navigate(BecalmRoute.OnboardingNotificationPerm.path)
            },
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewOutlookCalendarOAuthScreen() {
    BecalmTheme {
        OutlookCalendarOAuthScreen(navController = rememberNavController())
    }
}
