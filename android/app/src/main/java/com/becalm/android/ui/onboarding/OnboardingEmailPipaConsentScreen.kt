package com.becalm.android.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
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
import kotlinx.coroutines.launch

/**
 * Renders the per-provider PIPA 제3자 제공 disclosure between `ContactsPermissionScreen`
 * and the downstream OAuth / credential screen (S6-D, plan
 * `docs/plans/ui-onboarding-pipa-email-consent.md`).
 *
 * Three instances are wired into the nav graph — one per email disclosure slug:
 * `gmail`, `outlook_mail`, `imap`. IMAP writes consent for both Naver Corp and Kakao
 * Corp atomically (plan §5.1 "combined disclosure, per-recipient record"); Gmail and
 * Outlook write a single record.
 *
 * Navigation contract:
 * - **Agree**: await the consent write via [OnboardingViewModel.onEmailPipaConsent];
 *   only navigate to the downstream OAuth / credential screen after the write returns
 *   `true` (ensures the provider screen's hard gate sees the persisted flag).
 * - **Decline**: same write-await pattern with `granted=false`; on completion the
 *   ViewModel has already marked the downstream step [StepStatus.SKIPPED], so the
 *   screen navigates to the **post-provider** route (the next PIPA disclosure or the
 *   Google Calendar screen for IMAP) — the user never lands on a connectable
 *   screen for a provider they declined (PIPA Article 17 defence-in-depth).
 * - **DataStore write failure**: surfaces a Snackbar and leaves the user on-screen
 *   so they can retry. Navigation stays gated behind a confirmed write so a transient
 *   DataStore fault never bypasses consent.
 */
@Composable
public fun OnboardingEmailPipaConsentScreen(
    providerSlug: String,
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val writeFailedCopy = stringResource(R.string.onb_pipa_email_error_write_failed)

    val copy = pipaCopyForSlug(providerSlug) ?: run {
        // Unknown slug — advance to Gmail to avoid trapping the user. An audit
        // message is logged via the ViewModel so this path is never silent.
        viewModel.reportOnboardingStepFailed(OnboardingStep.LINK_GMAIL, "pipa_email_unknown_provider")
        navController.navigate(BecalmRoute.OnboardingGmail.path)
        return
    }

    BecalmScaffold(
        title = stringResource(copy.titleRes),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        OnboardingEmailPipaConsentContent(
            providerSlug = providerSlug,
            onAgree = {
                scope.launch {
                    val ok = viewModel.onEmailPipaConsent(copy.recipients, granted = true)
                    if (ok) {
                        navController.navigate(copy.connectRoute)
                    } else {
                        snackbarHostState.showSnackbar(writeFailedCopy)
                    }
                }
            },
            onDeny = {
                scope.launch {
                    val ok = viewModel.onEmailPipaConsent(copy.recipients, granted = false)
                    if (ok) {
                        navController.navigate(copy.skipAheadRoute)
                    } else {
                        snackbarHostState.showSnackbar(writeFailedCopy)
                    }
                }
            },
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun OnboardingEmailPipaConsentContent(
    providerSlug: String,
    onAgree: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = pipaCopyForSlug(providerSlug) ?: return
    Column(
        modifier = modifier
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
            onClick = onAgree,
            variant = BecalmButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.onb_pipa_email_cta_deny),
            onClick = onDeny,
            variant = BecalmButtonVariant.Text,
        )
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
 * [recipients] is the list of concrete PIPA recipients consent is recorded for — one
 * element for Gmail / Outlook, two for IMAP (Naver + Daum). [connectRoute] is the
 * downstream screen the user sees on Agree; [skipAheadRoute] is the post-provider
 * screen the user sees on Decline so no connectable provider screen is reachable
 * without a persisted consent grant for that provider.
 *
 * Centralised here so [OnboardingEmailPipaConsentScreen] stays dumb (one composable
 * function, no conditionals across provider slugs).
 */
private data class EmailPipaCopy(
    val recipients: List<EmailPipaProvider>,
    @StringRes val titleRes: Int,
    @StringRes val headlineRes: Int,
    @StringRes val recipientRes: Int,
    val connectRoute: String,
    val skipAheadRoute: String,
)

private fun pipaCopyForSlug(slug: String): EmailPipaCopy? = when (slug) {
    "gmail" -> EmailPipaCopy(
        recipients = listOf(EmailPipaProvider.GMAIL),
        titleRes = R.string.onb_pipa_email_title_gmail,
        headlineRes = R.string.onb_pipa_email_headline_gmail,
        recipientRes = R.string.onb_pipa_email_recipient_gmail,
        connectRoute = BecalmRoute.OnboardingGmail.path,
        skipAheadRoute = BecalmRoute.OnboardingEmailPipa("outlook_mail").path,
    )
    "outlook_mail" -> EmailPipaCopy(
        recipients = listOf(EmailPipaProvider.OUTLOOK_MAIL),
        titleRes = R.string.onb_pipa_email_title_outlook,
        headlineRes = R.string.onb_pipa_email_headline_outlook,
        recipientRes = R.string.onb_pipa_email_recipient_outlook,
        connectRoute = BecalmRoute.OnboardingOutlookMail.path,
        skipAheadRoute = BecalmRoute.OnboardingEmailPipa("imap").path,
    )
    "imap" -> EmailPipaCopy(
        recipients = EmailPipaProvider.IMAP_GROUP,
        titleRes = R.string.onb_pipa_email_title_imap,
        headlineRes = R.string.onb_pipa_email_headline_imap,
        recipientRes = R.string.onb_pipa_email_recipient_imap,
        connectRoute = BecalmRoute.OnboardingImap.path,
        skipAheadRoute = BecalmRoute.OnboardingGoogleCalendar.path,
    )
    else -> null
}

@PreviewLightDark
@Composable
private fun PreviewOnboardingEmailPipaConsentScreen() {
    BecalmTheme {
        OnboardingEmailPipaConsentScreen(
            providerSlug = "gmail",
            navController = rememberNavController(),
        )
    }
}
