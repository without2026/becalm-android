package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.theme.BecalmTheme

@Composable
internal fun SourceConnectionsContent(
    items: List<SourceConnectionItemUi>,
    continueLabel: String,
    onConnect: (OnboardingSourceProvider) -> Unit,
    onSkip: (OnboardingSourceProvider) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    headline: String = stringResource(R.string.onb_sources_headline),
    body: String = stringResource(R.string.onb_sources_body),
    skipLabel: String = stringResource(R.string.action_skip),
    setupItems: List<OnboardingSetupItemUi> = emptyList(),
    selfIdentity: OnboardingSelfIdentityUi? = null,
    onSelfDisplayNameChange: (String) -> Unit = {},
    onSelfEmailChange: (String) -> Unit = {},
    onSelfPhoneChange: (String) -> Unit = {},
    onSelfAliasChange: (String) -> Unit = {},
    onSaveSelfIdentity: () -> Unit = {},
    connectedAccounts: List<OnboardingSourceOwnershipUi> = emptyList(),
    connectedAccountActionIds: Set<String> = emptySet(),
    onDeleteConnectedAccount: ((OnboardingSourceOwnershipUi) -> Unit)? = null,
    onConnectSetupItem: (OnboardingSetupItem) -> Unit = {},
    onSkipSetupItem: (OnboardingSetupItem) -> Unit = {},
    continueEnabled: Boolean = true,
    continueLoading: Boolean = false,
    showImapLaterNotice: Boolean = false,
    progressiveSetup: Boolean = false,
) {
    val requiredSection = stringResource(R.string.onb_setup_required_section)
    val recommendedSection = stringResource(R.string.onb_setup_recommended_section)
    val optionalSection = stringResource(R.string.onb_setup_optional_section)
    val mailSection = stringResource(R.string.onb_sources_mail_section)
    val calendarSection = stringResource(R.string.onb_sources_calendar_section)
    val visibleSourceItems = if (progressiveSetup) items.take(1) else items
    val mailItems = visibleSourceItems.filter { it.category == SourceConnectionCategory.Mail }
    val calendarItems = visibleSourceItems.filter { it.category == SourceConnectionCategory.Calendar }
    val selfIdentityGateOpen = selfIdentity?.confirmed != false
    val continueGateOpen = selfIdentityGateOpen
    val showRequiredSetup = !progressiveSetup && (setupItems.isNotEmpty() || selfIdentity != null)
    val showSetupRecommendedCalendar = !progressiveSetup && setupItems.isNotEmpty() && calendarItems.isNotEmpty() && selfIdentityGateOpen
    val showImapLaterNoticePanel = !progressiveSetup && showImapLaterNotice && selfIdentityGateOpen && mailItems.isNotEmpty()
    LazyColumn(
        modifier = modifier.testTag("source-connections-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            if (progressiveSetup) {
                OnboardingSetupProgress(
                    currentStep = ONBOARDING_INTRO_PAGE_COUNT,
                    totalSteps = ONBOARDING_INTRO_PAGE_COUNT,
                )
                Spacer(modifier = Modifier.height(16.dp))
                ProgressiveSourceSetupIcon(provider = visibleSourceItems.firstOrNull()?.provider)
                Spacer(modifier = Modifier.height(12.dp))
            }
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
        if (progressiveSetup) {
            if (selfIdentity != null && !selfIdentity.confirmed) {
                item(key = "progressive-self-identity") {
                    SelfIdentitySetupPanel(
                        state = selfIdentity,
                        onDisplayNameChange = onSelfDisplayNameChange,
                        onEmailChange = onSelfEmailChange,
                        onPhoneChange = onSelfPhoneChange,
                        onAliasChange = onSelfAliasChange,
                        onSave = onSaveSelfIdentity,
                    )
                }
            }
            if (selfIdentityGateOpen && visibleSourceItems.isNotEmpty()) {
                item(key = "progressive-first-source-card") {
                    val firstSource = visibleSourceItems.first()
                    ProgressiveSourceConnectionCard(
                        item = firstSource,
                        onConnect = { onConnect(firstSource.provider) },
                        onSkip = { onSkip(firstSource.provider) },
                        skipLabel = skipLabel,
                    )
                }
            }
            if (selfIdentityGateOpen) {
                item(key = "setup-add-later-notice") {
                    ProgressiveAddLaterNote()
                }
            }
        } else if (showRequiredSetup) {
            item(key = "required-setup-title") {
                Text(
                    text = requiredSection,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "required-setup-summary") {
                RequiredSetupSummary()
            }
            if (selfIdentity != null) {
                item(key = "required-self-identity") {
                    SelfIdentitySetupPanel(
                        state = selfIdentity,
                        onDisplayNameChange = onSelfDisplayNameChange,
                        onEmailChange = onSelfEmailChange,
                        onPhoneChange = onSelfPhoneChange,
                        onAliasChange = onSelfAliasChange,
                        onSave = onSaveSelfIdentity,
                    )
                }
            }
        }
        if (!progressiveSetup && setupItems.isNotEmpty()) {
            item(key = "recommended-setup-title") {
                Text(
                    text = recommendedSection,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(items = setupItems, key = { item -> item.item.name }) { item ->
                SetupConnectionRow(
                    item = item,
                    onConnect = { onConnectSetupItem(item.item) },
                    onSkip = { onSkipSetupItem(item.item) },
                    skipLabel = skipLabel,
                )
            }
            if (showSetupRecommendedCalendar) {
                sourceSection(
                    title = calendarSection,
                    items = calendarItems,
                    onConnect = onConnect,
                    onSkip = onSkip,
                    skipLabel = skipLabel,
                )
            }
            item(key = "optional-setup-title") {
                Text(
                    text = optionalSection,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (!progressiveSetup && selfIdentityGateOpen && mailItems.isNotEmpty()) {
            sourceSection(
                title = mailSection,
                items = mailItems,
                onConnect = onConnect,
                onSkip = onSkip,
                skipLabel = skipLabel,
            )
            if (showImapLaterNoticePanel) {
                item(key = "imap-later-notice") {
                    ImapLaterNotice()
                }
            }
        }
        if (!progressiveSetup && selfIdentityGateOpen && !showSetupRecommendedCalendar && calendarItems.isNotEmpty()) {
            sourceSection(
                title = calendarSection,
                items = calendarItems,
                onConnect = onConnect,
                onSkip = onSkip,
                skipLabel = skipLabel,
            )
        }
        if (!progressiveSetup && selfIdentityGateOpen && connectedAccounts.isNotEmpty()) {
            item(key = "connected-accounts-title") {
                Text(
                    text = stringResource(R.string.settings_identity_connections_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(connectedAccounts, key = { item -> item.id }) { item ->
                SourceConnectedAccountRow(
                    item = item,
                    deleting = item.id in connectedAccountActionIds,
                    onDelete = onDeleteConnectedAccount?.let { onDelete ->
                        { onDelete(item) }
                    },
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            BecalmButton(
                text = continueLabel,
                onClick = onContinue,
                enabled = continueEnabled && continueGateOpen,
                loading = continueLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source-connections-continue"),
            )
        }
    }
}

@Composable
private fun ProgressiveSourceSetupIcon(provider: OnboardingSourceProvider?) {
    Icon(
        imageVector = provider.progressiveSetupIcon(),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .testTag("source-connections-progressive-icon")
            .size(32.dp),
    )
}

private fun OnboardingSourceProvider?.progressiveSetupIcon(): ImageVector =
    when (this) {
        OnboardingSourceProvider.GOOGLE_CALENDAR,
        OnboardingSourceProvider.OUTLOOK_CALENDAR,
        -> Icons.Outlined.CalendarMonth
        OnboardingSourceProvider.GMAIL,
        OnboardingSourceProvider.OUTLOOK_MAIL,
        null,
        -> Icons.Outlined.Email
}

@Composable
private fun ProgressiveAddLaterNote() {
    Text(
        text = "• ${stringResource(R.string.onb_setup_add_later_body)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .testTag("setup-add-later-note"),
    )
}

@Composable
private fun SetupAddLaterNotice() {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.onb_setup_add_later_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.onb_setup_add_later_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImapLaterNotice() {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.onb_sources_imap_later_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.onb_sources_imap_later_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewSourceConnectionsContent() {
    val resources = LocalContext.current.resources
    BecalmTheme {
        SourceConnectionsContent(
            items = SourceConnectionProjector.sourceConnectionItems(
                stepStates = mapOf(
                    OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE,
                    OnboardingStep.LINK_OUTLOOK_MAIL to StepStatus.NOT_STARTED,
                    OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.SKIPPED,
                    OnboardingStep.LINK_OUTLOOK_CALENDAR to StepStatus.NOT_STARTED,
                ),
                transientStates = mapOf(
                    OnboardingSourceProvider.OUTLOOK_CALENDAR to SourceConnectionState.Failed,
                ),
                stringFor = resources::getString,
            ),
            continueLabel = stringResource(R.string.onb_sources_skip_remaining),
            onConnect = {},
            onSkip = {},
            onContinue = {},
        )
    }
}
