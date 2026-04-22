package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Onboarding step: Gmail OAuth connection placeholder.
 *
 * Displays PIPA disclosure for Gmail read access and a "Connect" CTA.
 * No real OAuth flow is implemented here — that is deferred to R10.
 *
 * // TODO(BECALM-OAUTH-001): wire real Gmail OAuth flow via Google Identity Services SDK.
 *
 * spec: ONB-001, SMG-001
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingGmail]
 * Navigation exit: [BecalmRoute.OnboardingEmailPipa] (outlook_mail slug) — per S6-D the
 *   PIPA disclosure for the next provider is shown before its OAuth screen.
 */
@Composable
public fun GmailOAuthScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    BecalmScaffold(title = stringResource(R.string.onb_gmail_title)) { padding ->
        OAuthPlaceholderContent(
            modifier = Modifier.padding(padding),
            headline = stringResource(R.string.onb_gmail_headline),
            body = stringResource(R.string.onb_gmail_body),
            connectLabel = stringResource(R.string.action_connect),
            onConnect = {
                // TODO(BECALM-OAUTH-001): wire real Gmail OAuth
                viewModel.onMarkStepStatus(OnboardingStep.LINK_GMAIL, StepStatus.COMPLETE)
                navController.navigate(BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.OUTLOOK_MAIL.storageKey).path)
            },
            onSkip = {
                viewModel.onSkipStep()
                navController.navigate(BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.OUTLOOK_MAIL.storageKey).path)
            },
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewGmailOAuthScreen() {
    BecalmTheme {
        GmailOAuthScreen(navController = rememberNavController())
    }
}

// ─── Shared OAuth placeholder layout ──────────────────────────────────────────

@Composable
internal fun OAuthPlaceholderContent(
    headline: String,
    body: String,
    connectLabel: String,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(MaterialTheme.shapes.medium)
                .padding(16.dp),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = connectLabel,
            onClick = onConnect,
            variant = BecalmButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.action_skip),
            onClick = onSkip,
            variant = BecalmButtonVariant.Text,
        )
    }
}
