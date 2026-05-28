package com.becalm.android.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.components.StatusPill
import com.becalm.android.ui.components.StatusTone
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.theme.BecalmTheme

@Composable
internal fun OnboardingIntroContent(
    pageIndex: Int,
    gmailConnectionState: SourceConnectionState,
    contactsConnectionState: SourceConnectionState,
    calendarConnectionState: SourceConnectionState,
    callRecordingConnectionState: SourceConnectionState,
    contactsPreview: OnboardingContactsPreviewUi = OnboardingContactsPreviewUi(),
    calendarPreview: OnboardingCalendarPreviewUi = OnboardingCalendarPreviewUi(),
    gmailActivationPreview: GmailActivationPreviewUiState = GmailActivationPreviewUiState(),
    selfIdentity: OnboardingSelfIdentityUi = OnboardingSelfIdentityUi(
        displayName = "",
        email = "",
        phone = "",
        alias = "",
        confirmed = false,
        saving = false,
    ),
    gmailActivationReturnAvailable: Boolean = false,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onConnectContacts: () -> Unit,
    onConnectGoogleCalendar: () -> Unit,
    onConnectCallRecording: () -> Unit,
    onSkipCallRecording: () -> Unit,
    onConnectGmail: () -> Unit,
    onReturnToGmailActivation: () -> Unit = {},
    onIdentityNext: () -> Unit = onNext,
    onSelfDisplayNameChange: (String) -> Unit = {},
    onSelfEmailChange: (String) -> Unit = {},
    onSelfPhoneChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val page = OnboardingIntroPage.entries[pageIndex.coerceIn(0, OnboardingIntroPage.entries.lastIndex)]
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("onboarding-intro"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingSetupProgress(
            currentStep = pageIndex + 1,
            totalSteps = OnboardingIntroPage.entries.size,
        )
        IntroHero(page = page)
        IntroSupportingPanel(
            page = page,
            selfIdentity = selfIdentity,
            contactsConnectionState = contactsConnectionState,
            callRecordingConnectionState = callRecordingConnectionState,
            calendarConnectionState = calendarConnectionState,
            contactsPreview = contactsPreview,
            calendarPreview = calendarPreview,
            gmailActivationPreview = gmailActivationPreview,
            onConnectContacts = onConnectContacts,
            onConnectCallRecording = onConnectCallRecording,
            onSelfDisplayNameChange = onSelfDisplayNameChange,
            onSelfEmailChange = onSelfEmailChange,
            onSelfPhoneChange = onSelfPhoneChange,
        )
        IntroActions(
            page = page,
            pageIndex = pageIndex,
            gmailConnectionState = gmailConnectionState,
            contactsConnectionState = contactsConnectionState,
            calendarConnectionState = calendarConnectionState,
            callRecordingConnectionState = callRecordingConnectionState,
            gmailActivationReturnAvailable = gmailActivationReturnAvailable,
            onNext = onNext,
            onBack = onBack,
            onConnectContacts = onConnectContacts,
            onConnectGoogleCalendar = onConnectGoogleCalendar,
            onConnectCallRecording = onConnectCallRecording,
            onSkipCallRecording = onSkipCallRecording,
            onConnectGmail = onConnectGmail,
            onReturnToGmailActivation = onReturnToGmailActivation,
            onIdentityNext = onIdentityNext,
        )
    }
}

@Composable
internal fun OnboardingSetupProgress(
    currentStep: Int,
    modifier: Modifier = Modifier,
    totalSteps: Int = 5,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("onboarding-setup-progress"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        color = if (index < currentStep) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
internal fun GmailActivationPreviewContent(
    state: GmailActivationPreviewUiState,
    onUsePreview: () -> Unit,
    onRetry: () -> Unit = {},
    onStartWithoutPreview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("gmail-activation-preview"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OnboardingSetupProgress(
            currentStep = ONBOARDING_INTRO_PAGE_COUNT,
            totalSteps = ONBOARDING_INTRO_PAGE_COUNT,
        )
        if (state.loading && state.preview == null) {
            GmailActivationLoading(state)
            return@Column
        }

        val preview = state.primaryPreview()
        if (preview == null) {
            GmailActivationFallback(
                status = state.status,
                onRetry = onRetry,
                onStartWithoutPreview = onStartWithoutPreview,
                loading = state.loading,
                progress = state.progress,
                progressMessage = state.progressMessage,
            )
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = Icons.Outlined.Email,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = stringResource(R.string.onb_activation_preview_ready_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.onb_activation_preview_ready_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GmailPreviewCard(preview = preview)
        BecalmButton(
            text = stringResource(R.string.onb_setup_start),
            onClick = onUsePreview,
            loading = state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gmail-activation-use-preview"),
        )
    }
}

@Composable
private fun GmailActivationLoading(state: GmailActivationPreviewUiState) {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            SourceChip(
                icon = Icons.Outlined.Email,
                label = stringResource(R.string.onb_intro_email_connected),
            )
            LinearProgressIndicator(
                progress = { (state.progress ?: 0.12f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gmail-activation-progress"),
            )
            Text(
                text = stringResource(R.string.onb_activation_preview_loading_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = state.progressMessage ?: stringResource(R.string.onb_activation_preview_loading_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GmailActivationFallback(
    status: GmailActivationPreviewStatus,
    onRetry: () -> Unit,
    onStartWithoutPreview: () -> Unit,
    loading: Boolean,
    progress: Float?,
    progressMessage: String?,
) {
    val copy = gmailActivationFallbackCopy(status)
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            SourceChip(
                icon = Icons.Outlined.Email,
                label = stringResource(R.string.onb_intro_email_connected),
            )
            if (status == GmailActivationPreviewStatus.StillProcessing) {
                LinearProgressIndicator(
                    progress = { (progress ?: 0.72f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gmail-activation-progress"),
                )
                progressMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(copy.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(copy.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (copy.showRetry) {
        BecalmButton(
            text = stringResource(R.string.onb_activation_preview_wait_action),
            onClick = onRetry,
            loading = loading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gmail-activation-wait"),
        )
    }
    BecalmButton(
        text = stringResource(R.string.onb_setup_start),
        onClick = onStartWithoutPreview,
        variant = if (copy.showRetry) BecalmButtonVariant.Secondary else BecalmButtonVariant.Primary,
        loading = loading && !copy.showRetry,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gmail-activation-start-without-preview"),
    )
}

private data class GmailActivationFallbackCopy(
    val titleRes: Int,
    val bodyRes: Int,
    val showRetry: Boolean,
)

private fun gmailActivationFallbackCopy(status: GmailActivationPreviewStatus): GmailActivationFallbackCopy =
    when (status) {
        GmailActivationPreviewStatus.StillProcessing,
        GmailActivationPreviewStatus.Idle,
        GmailActivationPreviewStatus.Loading,
        -> GmailActivationFallbackCopy(
            titleRes = R.string.onb_activation_preview_processing_title,
            bodyRes = R.string.onb_activation_preview_processing_body,
            showRetry = true,
        )
        GmailActivationPreviewStatus.Empty -> GmailActivationFallbackCopy(
            titleRes = R.string.onb_activation_preview_empty_title,
            bodyRes = R.string.onb_activation_preview_empty_body,
            showRetry = false,
        )
        GmailActivationPreviewStatus.FailedRetryable -> GmailActivationFallbackCopy(
            titleRes = R.string.onb_activation_preview_failed_title,
            bodyRes = R.string.onb_activation_preview_failed_retryable_body,
            showRetry = true,
        )
        GmailActivationPreviewStatus.FailedTerminal -> GmailActivationFallbackCopy(
            titleRes = R.string.onb_activation_preview_failed_title,
            bodyRes = R.string.onb_activation_preview_failed_terminal_body,
            showRetry = false,
        )
        GmailActivationPreviewStatus.Ready -> GmailActivationFallbackCopy(
            titleRes = R.string.onb_activation_preview_ready_title,
            bodyRes = R.string.onb_activation_preview_ready_body,
            showRetry = false,
        )
    }

@Composable
private fun GmailPreviewCard(preview: GmailActivationPreviewUi) {
    val source = sourcePresentationFor(preview.sourceType)
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("gmail-activation-preview-card"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = preview.personName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.onb_activation_preview_person_fallback),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceChip(
                    icon = source.icon,
                    label = stringResource(source.labelRes),
                )
                preview.dueHint?.takeIf { it.isNotBlank() }?.let { dueHint ->
                    Text(
                        text = dueHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            preview.sourceTitle?.takeIf { it.isNotBlank() }?.let { sourceTitle ->
                Text(
                    text = sourceTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SourceChip(icon: ImageVector, label: String) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun IntroHero(page: OnboardingIntroPage) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IntroIcon(page = page)
        page.eyebrowRes?.let { eyebrow ->
            Text(
                text = stringResource(eyebrow),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(page.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IntroIcon(page: OnboardingIntroPage) {
    val containerColor = introIconContainerColor(page.iconTone)
    val contentColor = introIconContentColor(page.iconTone)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)),
        modifier = Modifier.size(72.dp),
    ) {
        if (page == OnboardingIntroPage.Welcome) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "B",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                )
            }
        } else {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                modifier = Modifier.padding(19.dp),
            )
        }
    }
}

@Composable
private fun introIconContainerColor(tone: IntroIconTone): Color =
    when (tone) {
        IntroIconTone.Brand -> Color(0xFF090909)
        IntroIconTone.People -> Color(0xFFE1F4EE)
        IntroIconTone.Calendar -> Color(0xFFE7F3FF)
        IntroIconTone.Email -> Color(0xFFFFF0D6)
        IntroIconTone.Neutral -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
    }

@Composable
private fun introIconContentColor(tone: IntroIconTone): Color =
    when (tone) {
        IntroIconTone.Brand -> Color.White
        IntroIconTone.People -> Color(0xFF087565)
        IntroIconTone.Calendar -> Color(0xFF156AAE)
        IntroIconTone.Email -> Color(0xFF8C5A0B)
        IntroIconTone.Neutral -> MaterialTheme.colorScheme.primary
    }

@Composable
private fun IntroSupportingPanel(
    page: OnboardingIntroPage,
    selfIdentity: OnboardingSelfIdentityUi,
    contactsConnectionState: SourceConnectionState,
    callRecordingConnectionState: SourceConnectionState,
    calendarConnectionState: SourceConnectionState,
    contactsPreview: OnboardingContactsPreviewUi,
    calendarPreview: OnboardingCalendarPreviewUi,
    gmailActivationPreview: GmailActivationPreviewUiState,
    onConnectContacts: () -> Unit,
    onConnectCallRecording: () -> Unit,
    onSelfDisplayNameChange: (String) -> Unit,
    onSelfEmailChange: (String) -> Unit,
    onSelfPhoneChange: (String) -> Unit,
) {
    when (page) {
        OnboardingIntroPage.Welcome -> QuietPanel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.onb_intro_welcome_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OnboardingIntroPage.Identity -> SelfIdentitySetupPanel(
            state = selfIdentity,
            onDisplayNameChange = onSelfDisplayNameChange,
            onEmailChange = onSelfEmailChange,
            onPhoneChange = onSelfPhoneChange,
            onAliasChange = {},
            onSave = {},
            showSaveButton = false,
        )
        OnboardingIntroPage.DeviceSources -> DeviceSourcesPanel(
            contactsConnectionState = contactsConnectionState,
            callRecordingConnectionState = callRecordingConnectionState,
            contactsPreview = contactsPreview,
            onConnectContacts = onConnectContacts,
            onConnectCallRecording = onConnectCallRecording,
        )
        OnboardingIntroPage.Calendar -> IntroReferencePanel(
            preview = {
                IntroCalendarPreview(
                    calendarConnectionState = calendarConnectionState,
                    calendarPreview = calendarPreview,
                )
            },
            rows = listOf(
                IntroPanelRow(
                    icon = Icons.Outlined.CalendarMonth,
                    title = stringResource(R.string.onb_intro_calendar_panel_title),
                    description = stringResource(R.string.onb_intro_calendar_panel_body),
                    tag = stringResource(R.string.onb_intro_recommended_tag),
                ),
            ),
            info = stringResource(R.string.onb_intro_calendar_info),
        )
        OnboardingIntroPage.Email -> IntroReferencePanel(
            preview = {
                IntroEmailPreview(gmailActivationPreview = gmailActivationPreview)
            },
            rows = listOf(
                IntroPanelRow(
                    icon = Icons.Outlined.Email,
                    title = stringResource(R.string.onb_intro_email_panel_gmail_title),
                    description = stringResource(R.string.onb_intro_email_panel_body),
                    tag = stringResource(R.string.onb_intro_recommended_tag),
                ),
            ),
            info = stringResource(R.string.onb_intro_email_info),
        )
    }
}

@Composable
private fun IntroReferencePanel(
    rows: List<IntroPanelRow>,
    info: String?,
    preview: @Composable (() -> Unit)? = null,
) {
    QuietPanel(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            preview?.invoke()
            rows.forEach { row -> IntroPanelRow(row = row) }
            info?.let { IntroInfoLine(text = it) }
        }
    }
}

private data class IntroPanelRow(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val tag: String?,
)

@Composable
private fun IntroPanelRow(row: IntroPanelRow) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        ) {
            Icon(
                imageVector = row.icon,
                contentDescription = null,
                modifier = Modifier.padding(8.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        row.tag?.let { tag ->
            StatusPill(
                label = tag,
                tone = StatusTone.Attention,
            )
        }
    }
}

@Composable
private fun IntroInfoLine(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceSourcesPanel(
    contactsConnectionState: SourceConnectionState,
    callRecordingConnectionState: SourceConnectionState,
    contactsPreview: OnboardingContactsPreviewUi,
    onConnectContacts: () -> Unit,
    onConnectCallRecording: () -> Unit,
) {
    QuietPanel(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding-device-sources-panel"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DeviceSourceRow(
                icon = Icons.Outlined.Groups,
                title = stringResource(R.string.onb_intro_contacts_panel_title),
                body = stringResource(R.string.onb_intro_contacts_panel_body),
                state = contactsConnectionState,
                connectedLabelRes = R.string.onb_intro_contacts_connected,
                idleActionLabelRes = R.string.onb_intro_contacts_connect,
                onConnect = onConnectContacts,
                testTag = "onboarding-intro-connect-contacts",
            ) {
                ContactsPreview(contactsPreview)
            }
            DeviceSourceRow(
                icon = Icons.Outlined.Phone,
                title = stringResource(R.string.onb_intro_recordings_panel_title),
                body = stringResource(R.string.onb_intro_recordings_panel_body),
                state = callRecordingConnectionState,
                connectedLabelRes = R.string.onb_intro_recordings_connected,
                idleActionLabelRes = R.string.onb_intro_recordings_connect,
                onConnect = onConnectCallRecording,
                testTag = "onboarding-intro-connect-call-recording",
            ) {
                if (callRecordingConnectionState == SourceConnectionState.Connected) {
                    Text(
                        text = stringResource(R.string.onb_intro_recordings_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSourceRow(
    icon: ImageVector,
    title: String,
    body: String,
    state: SourceConnectionState,
    connectedLabelRes: Int,
    idleActionLabelRes: Int,
    onConnect: () -> Unit,
    testTag: String,
    connectedContent: @Composable () -> Unit = {},
) {
    val busy = state.isBusy
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    IntroStatusPill(state = state)
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state == SourceConnectionState.Connected) {
            connectedContent()
            Text(
                text = stringResource(connectedLabelRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            BecalmButton(
                text = stringResource(idleActionLabelRes),
                onClick = onConnect,
                enabled = !busy,
                loading = busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(testTag),
            )
        }
    }
}

@Composable
private fun IntroStatusPill(state: SourceConnectionState) {
    if (state == SourceConnectionState.Idle) {
        StatusPill(
            label = stringResource(R.string.onb_intro_recommended_tag),
            tone = StatusTone.Attention,
        )
        return
    }
    val presentation = sourceConnectionPresentationFor(state)
    StatusPill(
        label = stringResource(presentation.labelRes),
        tone = presentation.tone,
    )
}

@Composable
private fun ContactsPreview(preview: OnboardingContactsPreviewUi) {
    if (preview.totalCount <= 0) return
    Column(
        modifier = Modifier.testTag("onboarding-contacts-preview"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.onb_intro_contacts_preview_count, preview.totalCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        preview.names.forEach { name ->
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val remaining = preview.totalCount - preview.names.size
        if (remaining > 0) {
            Text(
                text = stringResource(R.string.onb_intro_contacts_preview_more, remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntroCalendarPreview(
    calendarConnectionState: SourceConnectionState,
    calendarPreview: OnboardingCalendarPreviewUi,
) {
    if (
        calendarConnectionState == SourceConnectionState.Connected ||
        calendarConnectionState == SourceConnectionState.Syncing
    ) {
        CalendarPreviewList(calendarPreview = calendarPreview)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.onb_intro_calendar_preview_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.onb_intro_calendar_preview_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.onb_intro_calendar_preview_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CalendarPreviewList(calendarPreview: OnboardingCalendarPreviewUi) {
    Column(
        modifier = Modifier.testTag("onboarding-calendar-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.onb_intro_calendar_preview_connected_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            calendarPreview.loading -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.onb_intro_calendar_preview_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            calendarPreview.failed -> Text(
                text = stringResource(R.string.onb_intro_calendar_preview_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            calendarPreview.events.isEmpty() -> Text(
                text = stringResource(R.string.onb_intro_calendar_preview_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> calendarPreview.events.forEach { item ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .padding(top = 6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${item.dayLabel} ${item.timeLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntroEmailPreview(gmailActivationPreview: GmailActivationPreviewUiState) {
    val previews = gmailActivationPreview.previews.ifEmpty {
        gmailActivationPreview.preview?.let { listOf(it) }.orEmpty()
    }
    Column(
        modifier = Modifier.testTag("onboarding-gmail-inline-preview"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (previews.isNotEmpty()) {
                    R.string.onb_intro_email_preview_connected_label
                } else {
                    R.string.onb_intro_email_preview_label
                },
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            gmailActivationPreview.loading ||
                gmailActivationPreview.status == GmailActivationPreviewStatus.Loading ||
                gmailActivationPreview.status == GmailActivationPreviewStatus.StillProcessing -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(
                        progress = { (gmailActivationPreview.progress ?: 0.12f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding-gmail-inline-progress"),
                    )
                    Text(
                        text = gmailActivationPreview.progressMessage
                            ?: stringResource(R.string.onb_intro_email_preview_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            previews.isNotEmpty() -> {
                previews.take(2).forEach { preview ->
                    GmailInlinePreviewRow(preview = preview)
                }
            }
            gmailActivationPreview.status == GmailActivationPreviewStatus.Empty -> Text(
                text = stringResource(R.string.onb_intro_email_preview_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            gmailActivationPreview.status == GmailActivationPreviewStatus.FailedRetryable ||
                gmailActivationPreview.status == GmailActivationPreviewStatus.FailedTerminal -> Text(
                text = stringResource(R.string.onb_intro_email_preview_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> StaticEmailPreviewExample()
        }
    }
}

@Composable
private fun GmailInlinePreviewRow(preview: GmailActivationPreviewUi) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Email,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    label = stringResource(gmailPreviewKindLabelRes(preview.itemType)),
                    tone = StatusTone.Neutral,
                )
                StatusPill(
                    label = stringResource(
                        if (preview.contactMatched) {
                            R.string.onb_intro_email_contact_match
                        } else {
                            R.string.onb_intro_email_person_review
                        },
                    ),
                    tone = if (preview.contactMatched) StatusTone.Success else StatusTone.Attention,
                )
            }
            Text(
                text = preview.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = preview.personName?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.onb_activation_preview_person_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StaticEmailPreviewExample() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.onb_intro_email_preview_subject),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.onb_intro_email_preview_result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun gmailPreviewKindLabelRes(itemType: String): Int =
    when (itemType) {
        "schedule" -> R.string.onb_intro_email_kind_schedule
        "decision" -> R.string.onb_intro_email_kind_decision
        else -> R.string.onb_intro_email_kind_action
    }

@Composable
private fun IntroActions(
    page: OnboardingIntroPage,
    pageIndex: Int,
    gmailConnectionState: SourceConnectionState,
    contactsConnectionState: SourceConnectionState,
    calendarConnectionState: SourceConnectionState,
    callRecordingConnectionState: SourceConnectionState,
    gmailActivationReturnAvailable: Boolean,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onConnectContacts: () -> Unit,
    onConnectGoogleCalendar: () -> Unit,
    onConnectCallRecording: () -> Unit,
    onSkipCallRecording: () -> Unit,
    onConnectGmail: () -> Unit,
    onReturnToGmailActivation: () -> Unit,
    onIdentityNext: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (page) {
            OnboardingIntroPage.Welcome -> IntroPrimaryNext(onNext = onNext)
            OnboardingIntroPage.Identity -> {
                BecalmButton(
                    text = stringResource(R.string.onb_intro_next),
                    onClick = onIdentityNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding-intro-identity-next"),
                )
            }
            OnboardingIntroPage.DeviceSources -> {
                IntroSecondaryNext(onNext = onNext)
            }
            OnboardingIntroPage.Calendar -> {
                val calendarBusy = calendarConnectionState.isBusy
                IntroSourceAction(
                    labelRes = when {
                        calendarConnectionState == SourceConnectionState.Connected -> R.string.onb_intro_calendar_connected
                        calendarConnectionState == SourceConnectionState.Syncing -> R.string.onb_intro_calendar_syncing
                        else -> R.string.onb_intro_calendar_connect_google
                    },
                    state = calendarConnectionState,
                    onClick = onConnectGoogleCalendar,
                    testTag = "onboarding-intro-connect-google-calendar",
                )
                IntroSecondaryNext(onNext = onNext, enabled = !calendarBusy)
            }
            OnboardingIntroPage.Email -> {
                val hasReturnPath = gmailActivationReturnAvailable
                IntroSourceAction(
                    labelRes = when {
                        hasReturnPath -> R.string.onb_intro_email_return_to_preview
                        gmailConnectionState == SourceConnectionState.Connected -> R.string.onb_intro_email_connected
                        gmailConnectionState == SourceConnectionState.Syncing -> R.string.onb_intro_email_syncing
                        else -> R.string.onb_intro_email_connect_gmail
                    },
                    state = if (hasReturnPath) SourceConnectionState.Idle else gmailConnectionState,
                    onClick = if (hasReturnPath) onReturnToGmailActivation else onConnectGmail,
                    testTag = "onboarding-intro-connect-gmail",
                )
                BecalmButton(
                    text = stringResource(R.string.onb_intro_email_skip),
                    onClick = onNext,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding-intro-skip-gmail"),
                )
            }
        }
        if (pageIndex > 0) {
            BecalmButton(
                text = stringResource(R.string.onb_intro_back),
                onClick = onBack,
                variant = BecalmButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-intro-back"),
            )
        }
    }
}

@Composable
private fun IntroPrimaryNext(onNext: () -> Unit) {
    BecalmButton(
        text = stringResource(R.string.onb_intro_start),
        onClick = onNext,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding-intro-next"),
    )
}

@Composable
private fun IntroSecondaryNext(onNext: () -> Unit, enabled: Boolean = true) {
    BecalmButton(
        text = stringResource(R.string.onb_intro_next),
        onClick = onNext,
        enabled = enabled,
        variant = BecalmButtonVariant.Secondary,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding-intro-next"),
    )
}

@Composable
private fun IntroSourceAction(
    labelRes: Int,
    state: SourceConnectionState,
    onClick: () -> Unit,
    testTag: String,
) {
    val busy = state.isBusy
    BecalmButton(
        text = stringResource(labelRes),
        onClick = onClick,
        enabled = state != SourceConnectionState.Connected && !busy,
        loading = busy,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

private enum class OnboardingIntroPage(
    val icon: ImageVector,
    val iconTone: IntroIconTone,
    val eyebrowRes: Int?,
    val titleRes: Int,
    val bodyRes: Int,
) {
    Welcome(
        icon = Icons.Outlined.Groups,
        iconTone = IntroIconTone.Brand,
        eyebrowRes = R.string.onb_intro_welcome_eyebrow,
        titleRes = R.string.onb_intro_welcome_title,
        bodyRes = R.string.onb_intro_welcome_body,
    ),
    Identity(
        icon = Icons.Outlined.Groups,
        iconTone = IntroIconTone.Neutral,
        eyebrowRes = null,
        titleRes = R.string.onb_intro_identity_title,
        bodyRes = R.string.onb_intro_identity_body,
    ),
    DeviceSources(
        icon = Icons.Outlined.Phone,
        iconTone = IntroIconTone.People,
        eyebrowRes = null,
        titleRes = R.string.onb_intro_device_sources_title,
        bodyRes = R.string.onb_intro_device_sources_body,
    ),
    Calendar(
        icon = Icons.Outlined.CalendarMonth,
        iconTone = IntroIconTone.Calendar,
        eyebrowRes = null,
        titleRes = R.string.onb_intro_calendar_title,
        bodyRes = R.string.onb_intro_calendar_body,
    ),
    Email(
        icon = Icons.Outlined.Email,
        iconTone = IntroIconTone.Email,
        eyebrowRes = null,
        titleRes = R.string.onb_intro_email_title,
        bodyRes = R.string.onb_intro_email_body,
    ),
}

private enum class IntroIconTone {
    Brand,
    People,
    Calendar,
    Email,
    Neutral,
}

@PreviewLightDark
@Composable
private fun PreviewOnboardingIntroContent() {
    BecalmTheme {
        OnboardingIntroContent(
            pageIndex = 4,
            gmailConnectionState = SourceConnectionState.Idle,
            contactsConnectionState = SourceConnectionState.Idle,
            calendarConnectionState = SourceConnectionState.Idle,
            callRecordingConnectionState = SourceConnectionState.Idle,
            onNext = {},
            onBack = {},
            onConnectContacts = {},
            onConnectGoogleCalendar = {},
            onConnectCallRecording = {},
            onSkipCallRecording = {},
            onConnectGmail = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
