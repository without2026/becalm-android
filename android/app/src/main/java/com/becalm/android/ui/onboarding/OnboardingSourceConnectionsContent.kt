package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
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
    sourceOwnerships: List<OnboardingSourceOwnershipUi> = emptyList(),
    updatingSourceOwnershipId: String? = null,
    onSourceOwnership: (String, String) -> Unit = { _, _ -> },
    onConnectSetupItem: (OnboardingSetupItem) -> Unit = {},
    onSkipSetupItem: (OnboardingSetupItem) -> Unit = {},
) {
    val requiredSection = stringResource(R.string.onb_setup_required_section)
    val recommendedSection = stringResource(R.string.onb_setup_recommended_section)
    val optionalSection = stringResource(R.string.onb_setup_optional_section)
    val mailSection = stringResource(R.string.onb_sources_mail_section)
    val calendarSection = stringResource(R.string.onb_sources_calendar_section)
    val mailItems = items.filter { it.category == SourceConnectionCategory.Mail }
    val calendarItems = items.filter { it.category == SourceConnectionCategory.Calendar }
    val selfIdentityGateOpen = selfIdentity?.confirmed != false
    val sourceOwnershipGateOpen = sourceOwnerships.none { it.ownership == "unknown" }
    val showRequiredSetup = setupItems.isNotEmpty() || selfIdentity != null
    val showSetupRecommendedCalendar = setupItems.isNotEmpty() && calendarItems.isNotEmpty() && selfIdentityGateOpen
    LazyColumn(
        modifier = modifier.testTag("source-connections-list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
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
        if (showRequiredSetup) {
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
        if (setupItems.isNotEmpty()) {
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
        if (selfIdentityGateOpen && mailItems.isNotEmpty()) {
            sourceSection(
                title = mailSection,
                items = mailItems,
                onConnect = onConnect,
                onSkip = onSkip,
                skipLabel = skipLabel,
            )
        }
        if (selfIdentityGateOpen && !showSetupRecommendedCalendar && calendarItems.isNotEmpty()) {
            sourceSection(
                title = calendarSection,
                items = calendarItems,
                onConnect = onConnect,
                onSkip = onSkip,
                skipLabel = skipLabel,
            )
        }
        if (selfIdentityGateOpen && sourceOwnerships.isNotEmpty()) {
            item(key = "source-ownership-title") {
                Text(
                    text = stringResource(R.string.settings_identity_connections_section),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!sourceOwnershipGateOpen) {
                item(key = "source-ownership-required") {
                    Text(
                        text = stringResource(R.string.onb_setup_source_ownership_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sourceOwnerships, key = { item -> item.id }) { item ->
                SourceOwnershipSetupRow(
                    item = item,
                    updating = updatingSourceOwnershipId == item.id,
                    onOwnership = { ownership -> onSourceOwnership(item.id, ownership) },
                )
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            BecalmButton(
                text = continueLabel,
                onClick = onContinue,
                enabled = selfIdentityGateOpen && sourceOwnershipGateOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("source-connections-continue"),
            )
        }
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
