package com.becalm.android.ui.onboarding

import androidx.annotation.StringRes
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
 * Renders the per-provider PIPA 제3자 제공 disclosure between `ContactsPermissionScreen`
 * and the downstream OAuth / credential screen (S6-D, plan
 * `docs/plans/ui-onboarding-pipa-email-consent.md`).
 *
 * Three instances are wired into the nav graph — one per email provider slug:
 * `gmail`, `outlook_mail`, `imap`. The disclosure copy is looked up by [providerSlug]
 * from the string resource bundle; the consent decision is persisted through
 * [OnboardingViewModel.onEmailPipaConsent] and an event is emitted on the
 * observability stream for the W7 PIPA action log.
 *
 * Tapping `[동의]` always routes forward to the downstream OAuth screen. Tapping
 * `[동의 안 함]` persists the `false` flag, marks the downstream OAuth step
 * [StepStatus.SKIPPED] via the ViewModel, and then navigates to the **same**
 * downstream route — that step's composable observes the SKIPPED status and
 * auto-forwards. This keeps the nav graph linear (a user who declines Gmail
 * still lands on the Gmail screen for one frame, then skips to Outlook).
 */
@Composable
public fun OnboardingEmailPipaConsentScreen(
    providerSlug: String,
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val copy = pipaCopyForSlug(providerSlug) ?: run {
        // Unknown slug — advance to Gmail to avoid trapping the user. An audit
        // message is logged via the ViewModel so this path is never silent.
        viewModel.reportOnboardingStepFailed(OnboardingStep.LINK_GMAIL, "pipa_email_unknown_provider")
        navController.navigate(BecalmRoute.OnboardingGmail.path)
        return
    }

    BecalmScaffold(title = stringResource(copy.titleRes)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(copy.headlineRes),
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
                PipaBulletLine(labelRes = R.string.onb_pipa_email_recipient, valueRes = copy.recipientRes)
                Spacer(modifier = Modifier.height(8.dp))
                PipaBulletLine(labelRes = R.string.onb_pipa_email_purpose, valueRes = R.string.onb_pipa_email_purpose_body)
                Spacer(modifier = Modifier.height(8.dp))
                PipaBulletLine(labelRes = R.string.onb_pipa_email_items, valueRes = R.string.onb_pipa_email_items_body)
                Spacer(modifier = Modifier.height(8.dp))
                PipaBulletLine(labelRes = R.string.onb_pipa_email_retention, valueRes = R.string.onb_pipa_email_retention_body)
                Spacer(modifier = Modifier.height(8.dp))
                PipaBulletLine(labelRes = R.string.onb_pipa_email_opt_out, valueRes = R.string.onb_pipa_email_opt_out_body)
            }
            Spacer(modifier = Modifier.height(32.dp))
            BecalmButton(
                text = stringResource(R.string.onb_pipa_email_cta_agree),
                onClick = {
                    viewModel.onEmailPipaConsent(copy.provider, granted = true)
                    navController.navigate(copy.downstreamRoute)
                },
                variant = BecalmButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.onb_pipa_email_cta_deny),
                onClick = {
                    viewModel.onEmailPipaConsent(copy.provider, granted = false)
                    navController.navigate(copy.downstreamRoute)
                },
                variant = BecalmButtonVariant.Text,
            )
        }
    }
}

/**
 * Single-line bullet inside the PIPA-structured disclosure panel: renders
 * `<label>: <value>` using the scaffold's body typography.
 */
@Composable
private fun PipaBulletLine(
    @StringRes labelRes: Int,
    @StringRes valueRes: Int,
) {
    Text(
        text = "${stringResource(labelRes)}: ${stringResource(valueRes)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * String-resource + enum bundle for a single PIPA disclosure screen.
 *
 * Centralised here so [OnboardingEmailPipaConsentScreen] stays dumb (one
 * composable function, no conditionals across provider slugs).
 */
private data class EmailPipaCopy(
    val provider: EmailPipaProvider,
    @StringRes val titleRes: Int,
    @StringRes val headlineRes: Int,
    @StringRes val recipientRes: Int,
    val downstreamRoute: String,
)

private fun pipaCopyForSlug(slug: String): EmailPipaCopy? = when (slug) {
    EmailPipaProvider.GMAIL.storageKey -> EmailPipaCopy(
        provider = EmailPipaProvider.GMAIL,
        titleRes = R.string.onb_pipa_email_title_gmail,
        headlineRes = R.string.onb_pipa_email_headline_gmail,
        recipientRes = R.string.onb_pipa_email_recipient_gmail,
        downstreamRoute = BecalmRoute.OnboardingGmail.path,
    )
    EmailPipaProvider.OUTLOOK_MAIL.storageKey -> EmailPipaCopy(
        provider = EmailPipaProvider.OUTLOOK_MAIL,
        titleRes = R.string.onb_pipa_email_title_outlook,
        headlineRes = R.string.onb_pipa_email_headline_outlook,
        recipientRes = R.string.onb_pipa_email_recipient_outlook,
        downstreamRoute = BecalmRoute.OnboardingOutlookMail.path,
    )
    EmailPipaProvider.IMAP.storageKey -> EmailPipaCopy(
        provider = EmailPipaProvider.IMAP,
        titleRes = R.string.onb_pipa_email_title_imap,
        headlineRes = R.string.onb_pipa_email_headline_imap,
        recipientRes = R.string.onb_pipa_email_recipient_imap,
        downstreamRoute = BecalmRoute.OnboardingImap.path,
    )
    else -> null
}

@PreviewLightDark
@Composable
private fun PreviewOnboardingEmailPipaConsentScreen() {
    BecalmTheme {
        OnboardingEmailPipaConsentScreen(
            providerSlug = EmailPipaProvider.GMAIL.storageKey,
            navController = rememberNavController(),
        )
    }
}
