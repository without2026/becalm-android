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
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Onboarding step: Outlook Mail OAuth connection placeholder.
 *
 * // TODO(BECALM-OAUTH-001): wire real Outlook OAuth via MSAL Android SDK.
 *
 * spec: ONB-001, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingOutlookMail]
 * Navigation exit: [BecalmRoute.OnboardingEmailPipa] (imap slug) — per S6-D the IMAP
 *   PIPA disclosure is shown before the provider selector.
 */
@Composable
public fun OutlookMailOAuthScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    BecalmScaffold(title = stringResource(R.string.onb_outlook_mail_title)) { padding ->
        OAuthPlaceholderContent(
            modifier = Modifier.padding(padding),
            headline = stringResource(R.string.onb_outlook_mail_headline),
            body = stringResource(R.string.onb_outlook_mail_body),
            connectLabel = stringResource(R.string.action_connect),
            onConnect = {
                // TODO(BECALM-OAUTH-001): wire real Outlook Mail OAuth via MSAL
                viewModel.onMarkStepStatus(OnboardingStep.LINK_OUTLOOK_MAIL, StepStatus.COMPLETE)
                navController.navigate(BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.IMAP.storageKey).path)
            },
            onSkip = {
                viewModel.onSkipStep()
                navController.navigate(BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.IMAP.storageKey).path)
            },
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewOutlookMailOAuthScreen() {
    BecalmTheme {
        OutlookMailOAuthScreen(navController = rememberNavController())
    }
}
