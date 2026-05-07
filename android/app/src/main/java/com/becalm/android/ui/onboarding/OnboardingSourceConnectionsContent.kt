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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.theme.BecalmTheme

@Composable
internal fun SourceConnectionsContent(
    items: List<SourceConnectionItemUi>,
    headline: String = stringResource(R.string.onb_sources_headline),
    body: String = stringResource(R.string.onb_sources_body),
    continueLabel: String,
    skipLabel: String = stringResource(R.string.action_skip),
    onConnect: (OnboardingSourceProvider) -> Unit,
    onSkip: (OnboardingSourceProvider) -> Unit,
    setupItems: List<OnboardingSetupItemUi> = emptyList(),
    onConnectSetupItem: (OnboardingSetupItem) -> Unit = {},
    onSkipSetupItem: (OnboardingSetupItem) -> Unit = {},
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requiredSection = stringResource(R.string.onb_setup_required_section)
    val recommendedSection = stringResource(R.string.onb_setup_recommended_section)
    val optionalSection = stringResource(R.string.onb_setup_optional_section)
    val mailSection = stringResource(R.string.onb_sources_mail_section)
    val calendarSection = stringResource(R.string.onb_sources_calendar_section)
    LazyColumn(
        modifier = modifier,
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
        if (setupItems.isNotEmpty()) {
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
            item(key = "optional-setup-title") {
                Text(
                    text = optionalSection,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        sourceSection(
            title = mailSection,
            items = items.filter { it.category == SourceConnectionCategory.Mail },
            onConnect = onConnect,
            onSkip = onSkip,
            skipLabel = skipLabel,
        )
        sourceSection(
            title = calendarSection,
            items = items.filter { it.category == SourceConnectionCategory.Calendar },
            onConnect = onConnect,
            onSkip = onSkip,
            skipLabel = skipLabel,
        )
        item {
            Spacer(modifier = Modifier.height(8.dp))
            BecalmButton(
                text = continueLabel,
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
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
