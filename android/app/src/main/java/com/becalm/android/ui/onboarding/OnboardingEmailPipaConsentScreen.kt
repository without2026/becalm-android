package com.becalm.android.ui.onboarding

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.local.secure.ImapCredentials
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.navigation.navigateAfterSourceReconnectOr
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Renders the per-provider PIPA 제3자 제공 disclosure and starts the downstream
 * connection from the same screen.
 *
 * Three instances are wired into the nav graph — one per email disclosure slug:
 * `gmail`, `outlook_mail`, `imap`. IMAP writes consent for both Naver Corp and Kakao
 * Corp atomically (plan §5.1 "combined disclosure, per-recipient record"); Gmail and
 * Outlook write a single record.
 *
 * Navigation contract:
 * - **Agree**: await the consent write via [OnboardingViewModel.onEmailPipaConsent].
 *   Gmail / Outlook then launch backend-managed OAuth immediately from this screen;
 *   IMAP continues to the credential form after the consent write succeeds.
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
    viewModel: OnboardingViewModel? = null,
    onPersistConsent: (suspend (List<EmailPipaProvider>, Boolean) -> Boolean)? = null,
    onReportUnknownProvider: ((OnboardingStep, String) -> Unit)? = null,
    emailConnectEventsOverride: Flow<EmailConnectEvent>? = null,
    onConnectEmailProvider: ((EmailPipaProvider, Activity) -> Unit)? = null,
    onNavigate: ((String) -> Unit)? = null,
) {
    val resolvedViewModel = if (onPersistConsent == null || onReportUnknownProvider == null) {
        viewModel ?: hiltViewModel()
    } else {
        viewModel
    }
    val persistConsent = onPersistConsent ?: requireNotNull(resolvedViewModel)::onEmailPipaConsent
    val reportUnknownProvider = onReportUnknownProvider ?: requireNotNull(resolvedViewModel)::reportOnboardingStepFailed
    val connectEmailProvider = onConnectEmailProvider ?: { provider: EmailPipaProvider, hostActivity: Activity ->
        requireNotNull(resolvedViewModel).onConnectEmailProvider(provider, hostActivity)
    }
    val emailConnectEvents = emailConnectEventsOverride ?: resolvedViewModel?.emailConnectEvents
    val saveImapCredentials = { provider: ImapProvider, username: String, appPassword: String ->
        requireNotNull(resolvedViewModel).saveImapCredentials(
            sourceType = provider.sourceType,
            credentials = ImapCredentials(
                host = provider.host,
                port = provider.port,
                username = username,
                appPassword = appPassword,
            ),
        )
    }
    val navigate = onNavigate ?: { route: String -> navController.navigateAfterSourceReconnectOr(route) }
    val activity = LocalContext.current as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingResumeRefreshProvider by rememberSaveable { mutableStateOf<String?>(null) }
    val writeFailedCopy = stringResource(R.string.onb_pipa_email_error_write_failed)
    val missingActivityCopy = stringResource(R.string.onb_gmail_error_unknown)
    val imapErrorCopyByCode = imapErrorStringMap(
        network = stringResource(R.string.onb_imap_error_network),
        unknown = stringResource(R.string.onb_imap_error_save_failed),
    )

    val copy = pipaCopyForSlug(providerSlug) ?: run {
        // Unknown slug — advance to Gmail to avoid trapping the user. An audit
        // message is logged via the ViewModel so this path is never silent.
        reportUnknownProvider(OnboardingStep.LINK_GMAIL, "pipa_email_unknown_provider")
        navigate(BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.GMAIL.storageKey).path)
        return
    }
    val oauthProvider = copy.oauthProvider
    val connectionFailedCopy = when (oauthProvider) {
        EmailPipaProvider.GMAIL -> stringResource(R.string.onb_gmail_error_unknown)
        EmailPipaProvider.OUTLOOK_MAIL -> stringResource(R.string.onb_outlook_error_unknown)
        EmailPipaProvider.NAVER_IMAP,
        EmailPipaProvider.DAUM_IMAP,
        null,
        -> null
    }

    EmailConnectionEventsEffect(
        copy = copy,
        events = emailConnectEvents,
        oauthFailureCopy = connectionFailedCopy,
        imapErrorCopyByCode = imapErrorCopyByCode,
        fallbackFailureCopy = writeFailedCopy,
        snackbarHostState = snackbarHostState,
        navigate = navigate,
        onEventConsumed = { pendingResumeRefreshProvider = null },
    )
    EmailOAuthResumeRefreshEffect(
        lifecycleOwner = lifecycleOwner,
        viewModel = resolvedViewModel,
        provider = oauthProvider,
        enabled = pendingResumeRefreshProvider == oauthProvider?.storageKey,
    )
    SecureImapWindowEffect(activity = activity, enabled = copy.connectionTarget == EmailPipaConnectionTarget.Imap)

    BecalmScaffold(
        title = stringResource(copy.titleRes),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (copy.connectionTarget) {
            is EmailPipaConnectionTarget.OAuth -> OAuthEmailConsentConnectContent(
                copy = copy,
                activity = activity,
                persistConsent = persistConsent,
                connectEmailProvider = connectEmailProvider,
                onOAuthLaunchRequested = {
                    pendingResumeRefreshProvider = oauthProvider?.storageKey
                },
                navigate = navigate,
                missingActivityCopy = missingActivityCopy,
                writeFailedCopy = writeFailedCopy,
                snackbarHostState = snackbarHostState,
                scope = scope,
                modifier = Modifier.padding(padding),
            )
            EmailPipaConnectionTarget.Imap -> ImapConsentConnectContent(
                copy = copy,
                persistConsent = persistConsent,
                saveImapCredentials = saveImapCredentials,
                navigate = navigate,
                writeFailedCopy = writeFailedCopy,
                snackbarHostState = snackbarHostState,
                scope = scope,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun EmailConnectionEventsEffect(
    copy: EmailPipaCopy,
    events: Flow<EmailConnectEvent>?,
    oauthFailureCopy: String?,
    imapErrorCopyByCode: Map<String, String>,
    fallbackFailureCopy: String,
    snackbarHostState: SnackbarHostState,
    navigate: (String) -> Unit,
    onEventConsumed: () -> Unit,
) {
    LaunchedEffect(events, copy) {
        val eventFlow = events ?: return@LaunchedEffect
        when (val target = copy.connectionTarget) {
            is EmailPipaConnectionTarget.OAuth -> {
                eventFlow.filter { it.provider == target.provider }.collect { event ->
                    onEventConsumed()
                    when (event) {
                        is EmailConnectEvent.Connected -> navigate(copy.skipAheadRoute)
                        is EmailConnectEvent.PendingIntentRequired -> Unit
                        is EmailConnectEvent.Failed -> snackbarHostState.showSnackbar(
                            oauthFailureCopy ?: fallbackFailureCopy,
                        )
                    }
                }
            }
            EmailPipaConnectionTarget.Imap -> {
                eventFlow.filter { it.provider in EmailPipaProvider.IMAP_GROUP }.collect { event ->
                    when (event) {
                        is EmailConnectEvent.Connected -> navigate(copy.skipAheadRoute)
                        is EmailConnectEvent.PendingIntentRequired -> Unit
                        is EmailConnectEvent.Failed -> snackbarHostState.showSnackbar(
                            imapErrorCopyByCode[event.errorCode]
                                ?: imapErrorCopyByCode.getValue("unknown"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailOAuthResumeRefreshEffect(
    lifecycleOwner: LifecycleOwner,
    viewModel: OnboardingViewModel?,
    provider: EmailPipaProvider?,
    enabled: Boolean,
) {
    DisposableEffect(lifecycleOwner, viewModel, provider, enabled) {
        if (!enabled || viewModel == null || provider == null) {
            return@DisposableEffect onDispose { }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshEmailProviderConnection(provider)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
private fun SecureImapWindowEffect(activity: Activity?, enabled: Boolean) {
    DisposableEffect(activity, enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
private fun OAuthEmailConsentConnectContent(
    copy: EmailPipaCopy,
    activity: Activity?,
    persistConsent: suspend (List<EmailPipaProvider>, Boolean) -> Boolean,
    connectEmailProvider: (EmailPipaProvider, Activity) -> Unit,
    onOAuthLaunchRequested: () -> Unit,
    navigate: (String) -> Unit,
    missingActivityCopy: String,
    writeFailedCopy: String,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val provider = (copy.connectionTarget as EmailPipaConnectionTarget.OAuth).provider
    OnboardingEmailPipaConsentContent(
        copy = copy,
        onAgree = {
            scope.launch {
                val ok = persistConsent(copy.recipients, true)
                when {
                    !ok -> snackbarHostState.showSnackbar(writeFailedCopy)
                    activity == null -> snackbarHostState.showSnackbar(missingActivityCopy)
                    else -> {
                        onOAuthLaunchRequested()
                        connectEmailProvider(provider, activity)
                    }
                }
            }
        },
        onDeny = {
            scope.launch {
                val ok = persistConsent(copy.recipients, false)
                if (ok) {
                    navigate(copy.skipAheadRoute)
                } else {
                    snackbarHostState.showSnackbar(writeFailedCopy)
                }
            }
        },
        agreeConnect = true,
        modifier = modifier,
    )
}

@Composable
private fun ImapConsentConnectContent(
    copy: EmailPipaCopy,
    persistConsent: suspend (List<EmailPipaProvider>, Boolean) -> Boolean,
    saveImapCredentials: (ImapProvider, String, String) -> Unit,
    navigate: (String) -> Unit,
    writeFailedCopy: String,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    ImapForm(
        modifier = modifier,
        onSave = { provider, username, appPassword ->
            scope.launch {
                val ok = persistConsent(copy.recipients, true)
                if (ok) {
                    saveImapCredentials(provider, username, appPassword)
                } else {
                    snackbarHostState.showSnackbar(writeFailedCopy)
                }
            }
        },
        onSkip = {
            scope.launch {
                val ok = persistConsent(copy.recipients, false)
                if (ok) {
                    navigate(copy.skipAheadRoute)
                } else {
                    snackbarHostState.showSnackbar(writeFailedCopy)
                }
            }
        },
        header = {
            EmailPipaDisclosureHeader(copy)
            Spacer(modifier = Modifier.height(24.dp))
            ImapFormHeader()
        },
    )
}

@Composable
internal fun OnboardingEmailPipaConsentContent(
    providerSlug: String,
    onAgree: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    agreeConnect: Boolean = false,
) {
    val copy = pipaCopyForSlug(providerSlug) ?: return
    OnboardingEmailPipaConsentContent(
        copy = copy,
        onAgree = onAgree,
        onDeny = onDeny,
        modifier = modifier,
        agreeConnect = agreeConnect,
    )
}

@Composable
private fun OnboardingEmailPipaConsentContent(
    copy: EmailPipaCopy,
    onAgree: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    agreeConnect: Boolean = false,
) {
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
        EmailPipaDisclosurePanel(copy)
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(
                if (agreeConnect) {
                    R.string.onb_pipa_email_cta_agree_connect
                } else {
                    R.string.onb_pipa_email_cta_agree
                },
            ),
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

@Composable
private fun EmailPipaDisclosureHeader(copy: EmailPipaCopy) {
    Text(
        text = stringResource(copy.headlineRes),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(16.dp))
    EmailPipaDisclosurePanel(copy)
}

@Composable
private fun EmailPipaDisclosurePanel(copy: EmailPipaCopy) {
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
 * element for Gmail / Outlook, two for IMAP (Naver + Daum). [connectionTarget] tells
 * the screen whether Agree launches OAuth or saves IMAP credentials in-place.
 * [skipAheadRoute] is the post-provider screen after Decline or successful connection.
 *
 * Centralised here so [OnboardingEmailPipaConsentScreen] stays dumb (one composable
 * function, no conditionals across provider slugs).
 */
private data class EmailPipaCopy(
    val recipients: List<EmailPipaProvider>,
    @StringRes val titleRes: Int,
    @StringRes val headlineRes: Int,
    @StringRes val recipientRes: Int,
    val skipAheadRoute: String,
    val connectionTarget: EmailPipaConnectionTarget,
)

private sealed interface EmailPipaConnectionTarget {
    data class OAuth(val provider: EmailPipaProvider) : EmailPipaConnectionTarget
    data object Imap : EmailPipaConnectionTarget
}

private val EmailPipaCopy.oauthProvider: EmailPipaProvider?
    get() = (connectionTarget as? EmailPipaConnectionTarget.OAuth)?.provider

private fun pipaCopyForSlug(slug: String): EmailPipaCopy? = when (slug) {
    "gmail" -> EmailPipaCopy(
        recipients = listOf(EmailPipaProvider.GMAIL),
        titleRes = R.string.onb_pipa_email_title_gmail,
        headlineRes = R.string.onb_pipa_email_headline_gmail,
        recipientRes = R.string.onb_pipa_email_recipient_gmail,
        skipAheadRoute = BecalmRoute.OnboardingEmailPipa("outlook_mail").path,
        connectionTarget = EmailPipaConnectionTarget.OAuth(EmailPipaProvider.GMAIL),
    )
    "outlook_mail" -> EmailPipaCopy(
        recipients = listOf(EmailPipaProvider.OUTLOOK_MAIL),
        titleRes = R.string.onb_pipa_email_title_outlook,
        headlineRes = R.string.onb_pipa_email_headline_outlook,
        recipientRes = R.string.onb_pipa_email_recipient_outlook,
        skipAheadRoute = BecalmRoute.OnboardingEmailPipa("imap").path,
        connectionTarget = EmailPipaConnectionTarget.OAuth(EmailPipaProvider.OUTLOOK_MAIL),
    )
    "imap" -> EmailPipaCopy(
        recipients = EmailPipaProvider.IMAP_GROUP,
        titleRes = R.string.onb_pipa_email_title_imap,
        headlineRes = R.string.onb_pipa_email_headline_imap,
        recipientRes = R.string.onb_pipa_email_recipient_imap,
        skipAheadRoute = BecalmRoute.OnboardingGoogleCalendar.path,
        connectionTarget = EmailPipaConnectionTarget.Imap,
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
