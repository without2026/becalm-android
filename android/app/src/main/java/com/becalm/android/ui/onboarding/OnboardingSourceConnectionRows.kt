package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.data.remote.supabase.SupabaseAuthProvider
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmTextField
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.StatusPill
import com.becalm.android.ui.components.StatusTone

internal data class OnboardingSelfIdentityUi(
    val displayName: String,
    val email: String,
    val phone: String,
    val alias: String,
    val confirmed: Boolean,
    val saving: Boolean,
    val authProvider: SupabaseAuthProvider = SupabaseAuthProvider.EMAIL,
    val displayNameReadOnly: Boolean = false,
    val emailReadOnly: Boolean = false,
    val phoneReadOnly: Boolean = false,
    val phoneVerified: Boolean = false,
)

@Composable
internal fun RequiredSetupSummary() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.onb_setup_required_terms),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onb_setup_required_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun SelfIdentitySetupPanel(
    state: OnboardingSelfIdentityUi,
    onDisplayNameChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onAliasChange: (String) -> Unit,
    onSave: () -> Unit,
    showSaveButton: Boolean = true,
) {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onb_setup_identity_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.onb_setup_identity_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.confirmed) {
                    StatusPill(
                        label = stringResource(R.string.onb_setup_identity_confirmed),
                        tone = com.becalm.android.ui.components.StatusTone.Success,
                    )
                }
            }
            BecalmTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = stringResource(R.string.onb_setup_identity_display_name_label),
                placeholder = stringResource(R.string.onb_setup_identity_display_name_placeholder),
                supportingText = stringResource(
                    if (state.displayNameReadOnly) {
                        R.string.onb_setup_identity_display_name_auth_help
                    } else {
                        R.string.onb_setup_identity_display_name_help
                    },
                ),
                enabled = !state.displayNameReadOnly,
                trailingIcon = {
                    if (state.displayNameReadOnly) {
                        StatusPill(
                            label = stringResource(R.string.onb_setup_identity_login_account),
                            tone = StatusTone.Muted,
                            compact = true,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-self-display-name"),
            )
            BecalmTextField(
                value = state.email,
                onValueChange = onEmailChange,
                label = stringResource(R.string.onb_setup_identity_email_label),
                placeholder = stringResource(R.string.onb_setup_identity_email_placeholder),
                supportingText = stringResource(
                    if (state.emailReadOnly) {
                        R.string.onb_setup_identity_email_login_account
                    } else {
                        R.string.onb_setup_identity_email_help
                    },
                ),
                keyboardType = KeyboardType.Email,
                enabled = !state.emailReadOnly,
                trailingIcon = {
                    if (state.emailReadOnly) {
                        StatusPill(
                            label = stringResource(R.string.onb_setup_identity_login_account),
                            tone = StatusTone.Muted,
                            compact = true,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-self-email"),
            )
            BecalmTextField(
                value = state.phone,
                onValueChange = onPhoneChange,
                label = stringResource(R.string.onb_setup_identity_phone_label),
                placeholder = "+82 10 0000 0000",
                supportingText = stringResource(
                    if (state.phoneVerified) {
                        R.string.onb_setup_identity_phone_verified_help
                    } else {
                        R.string.onb_setup_identity_phone_help
                    },
                ),
                keyboardType = KeyboardType.Phone,
                enabled = !state.phoneReadOnly,
                trailingIcon = {
                    if (state.phoneVerified) {
                        StatusPill(
                            label = stringResource(R.string.onb_setup_identity_verified),
                            tone = StatusTone.Success,
                            compact = true,
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-self-phone"),
            )
            if (showSaveButton) {
                BecalmButton(
                    text = stringResource(R.string.onb_setup_identity_save),
                    onClick = onSave,
                    loading = state.saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding-self-save"),
                )
            }
        }
    }
}

@Composable
internal fun SetupConnectionRow(
    item: OnboardingSetupItemUi,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
) {
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (item.detail != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SourceConnectionStatusPill(state = item.state)
            }
            if (!item.state.hidesActions) {
                SourceConnectionActions(
                    primaryLabel = connectLabel(item.state, requiresConsent = false),
                    onPrimary = onConnect,
                    onSkip = onSkip,
                    primaryEnabled = !item.state.isBusy,
                    primaryLoading = item.state.isBusy,
                    skipEnabled = !item.state.isBusy,
                    skipLabel = skipLabel,
                )
            }
        }
    }
}

internal fun LazyListScope.sourceSection(
    title: String,
    items: List<SourceConnectionItemUi>,
    onConnect: (OnboardingSourceProvider) -> Unit,
    onSkip: (OnboardingSourceProvider) -> Unit,
    skipLabel: String,
) {
    item(key = "$title-title") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    items(items = items, key = { item -> item.provider.name }) { item ->
        SourceConnectionRow(
            item = item,
            onConnect = { onConnect(item.provider) },
            onSkip = { onSkip(item.provider) },
            skipLabel = skipLabel,
        )
    }
}

@Composable
internal fun SourceConnectedAccountRow(item: OnboardingSourceOwnershipUi) {
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("source-connected-account-${item.id}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.accountLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SourceConnectionStatusPill(state = item.status.toSourceConnectionState())
        }
    }
}

@Composable
private fun SourceConnectionRow(
    item: SourceConnectionItemUi,
    onConnect: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
) {
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SourceConnectionStatusPill(state = item.state)
            }
            if (item.consentCopy != null && item.state == SourceConnectionState.ConsentRequired) {
                Text(
                    text = item.consentCopy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.state.hidesActions) {
                SourceConnectionActions(
                    primaryLabel = connectLabel(item),
                    onPrimary = onConnect,
                    primaryEnabled = !item.state.isBusy,
                    primaryLoading = item.state.isBusy,
                    onSkip = onSkip,
                    skipEnabled = !item.state.isBusy,
                    skipLabel = skipLabel,
                )
            }
        }
    }
}

@Composable
private fun SourceConnectionActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
    primaryEnabled: Boolean = true,
    primaryLoading: Boolean = false,
    skipEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BecalmButton(
            text = primaryLabel,
            onClick = onPrimary,
            enabled = primaryEnabled,
            loading = primaryLoading,
            modifier = Modifier
                .weight(1f)
                .testTag("source-connection-primary"),
        )
        BecalmButton(
            text = skipLabel,
            onClick = onSkip,
            variant = BecalmButtonVariant.Secondary,
            enabled = skipEnabled,
            modifier = Modifier.testTag("source-connection-skip"),
        )
    }
}

@Composable
private fun SourceConnectionStatusPill(state: SourceConnectionState) {
    val presentation = sourceConnectionPresentationFor(state)
    StatusPill(
        label = stringResource(presentation.labelRes),
        tone = presentation.tone,
    )
}

private fun String.toSourceConnectionState(): SourceConnectionState =
    when (this) {
        "connected", "synced" -> SourceConnectionState.Connected
        "connecting" -> SourceConnectionState.Connecting
        "syncing" -> SourceConnectionState.Syncing
        "failed", "needs_reauth" -> SourceConnectionState.Failed
        else -> SourceConnectionState.Idle
    }

@Composable
private fun connectLabel(item: SourceConnectionItemUi): String {
    item.primaryActionLabel?.let { return it }
    return connectLabel(item.state, item.consentCopy != null)
}

@Composable
private fun connectLabel(state: SourceConnectionState, requiresConsent: Boolean): String {
    val resId = if (requiresConsent && state != SourceConnectionState.Failed) {
        R.string.onb_sources_connect_with_consent
    } else {
        sourceConnectionPresentationFor(state).recommendedCtaRes ?: R.string.action_connect
    }
    return stringResource(resId)
}

private val SourceConnectionState.hidesActions: Boolean
    get() = this == SourceConnectionState.Connected
