package com.becalm.android.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmNavigationDefaults
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

@Composable
public fun OnboardingCompleteScreen(
    navController: NavHostController,
    personId: String,
) {
    val viewModel: OnboardingCompleteViewModel =
        androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
    val summary by viewModel.uiState.collectAsStateWithLifecycle()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val meetingAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onMeetingRecordingPermissionResult(granted)
    }

    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    fun navigateToPeople() {
        navController.navigate(BecalmNavigationDefaults.authenticatedHomeRoute) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun navigateToPerson() {
        if (personId == ONBOARDING_COMPLETE_GENERIC_PERSON_ID) {
            navigateToPeople()
            return
        }
        navController.navigate(BecalmRoute.PersonDetail(personId).path) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    BackHandler {
        // Stay on the completion surface until the user explicitly starts BeCalm.
    }

    OnboardingCompleteContent(
        summary = summary,
        onStart = ::navigateToPeople,
        onOpenPerson = ::navigateToPerson,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onNotificationPermissionResult(true)
            }
        },
        onRequestMeetingRecording = {
            meetingAudioPermissionLauncher.launch(audioPermission)
        },
    )
}

@Composable
public fun OnboardingCompleteContent(
    summary: OnboardingCompletionSummaryUi = OnboardingCompletionSummaryUi(),
    onStart: () -> Unit,
    onOpenPerson: (() -> Unit)? = null,
    onRequestNotifications: () -> Unit = {},
    onRequestMeetingRecording: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BecalmScaffold(
        modifier = modifier,
        title = stringResource(R.string.onb_setup_title),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .testTag("onboarding-complete"),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            OnboardingSetupProgress(
                currentStep = ONBOARDING_INTRO_PAGE_COUNT,
                totalSteps = ONBOARDING_INTRO_PAGE_COUNT,
            )
            CompletionHero(summary = summary)
            CompletionFeatureList(summary = summary)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f))
            CompletionPushPreview()
            CompletionPermissionActions(
                summary = summary,
                onRequestNotifications = onRequestNotifications,
                onRequestMeetingRecording = onRequestMeetingRecording,
            )
            if (summary.personId != ONBOARDING_COMPLETE_GENERIC_PERSON_ID && onOpenPerson != null) {
                BecalmButton(
                    text = stringResource(R.string.onb_complete_detail_action),
                    onClick = onOpenPerson,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding-complete-open-person"),
                )
            }
            BecalmButton(
                text = stringResource(R.string.onb_complete_start),
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding-complete-start"),
            )
        }
    }
}

@Composable
private fun CompletionHero(summary: OnboardingCompletionSummaryUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(
            text = stringResource(R.string.onb_complete_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = completionBody(summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun completionBody(summary: OnboardingCompletionSummaryUi): String =
    when {
        summary.personId != ONBOARDING_COMPLETE_GENERIC_PERSON_ID &&
            !summary.personName.isNullOrBlank() ->
            stringResource(R.string.onb_complete_body_person_fmt, summary.personName)
        summary.personId != ONBOARDING_COMPLETE_GENERIC_PERSON_ID ->
            stringResource(R.string.onb_complete_body_person_generic)
        summary.connectedSources.isNotEmpty() ->
            stringResource(R.string.onb_complete_body_connected)
        else ->
            stringResource(R.string.onb_complete_body_empty)
    }

@Composable
private fun CompletionFeatureList(summary: OnboardingCompletionSummaryUi) {
    val features = completionFeatureRows(summary)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        features.forEach { resId ->
            CompletionFeatureRow(
                tone = CompletionFeatureTone.Success,
                text = stringResource(resId),
            )
        }
    }
}

private fun completionFeatureRows(summary: OnboardingCompletionSummaryUi): List<Int> =
    buildList {
        if (summary.connectedSources.isEmpty()) {
            add(R.string.onb_complete_feature_empty_start)
            add(R.string.onb_complete_feature_sources_later)
            return@buildList
        }
        if (OnboardingCompletionConnectedSource.CONTACTS in summary.connectedSources) {
            add(R.string.onb_complete_feature_contacts)
        }
        if (OnboardingCompletionConnectedSource.CALENDAR in summary.connectedSources) {
            add(R.string.onb_complete_feature_calendar)
        }
        if (OnboardingCompletionConnectedSource.CALL_RECORDING in summary.connectedSources) {
            add(R.string.onb_complete_feature_call_recording)
        }
        if (OnboardingCompletionConnectedSource.GMAIL in summary.connectedSources) {
            add(R.string.onb_complete_feature_gmail)
        }
        add(R.string.onb_complete_feature_reminder)
        add(R.string.onb_complete_feature_meeting_later)
    }

@Composable
private fun CompletionFeatureRow(
    tone: CompletionFeatureTone,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FeatureIcon(tone = tone)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureIcon(tone: CompletionFeatureTone) {
    val fill = when (tone) {
        CompletionFeatureTone.Success -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    }
    val content = when (tone) {
        CompletionFeatureTone.Success -> MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun CompletionPushPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.onb_complete_push_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding-complete-push-preview"),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.onb_complete_push_app),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.onb_complete_push_time),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.62f),
                        )
                    }
                    Text(
                        text = stringResource(R.string.onb_complete_push_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.onb_complete_push_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.76f),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PushActionPreview(
                            text = stringResource(R.string.onb_complete_push_primary_action),
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                        PushActionPreview(
                            text = stringResource(R.string.onb_complete_push_secondary_action),
                            primary = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.onb_complete_push_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        CompletionNotificationExamples()
    }
}

@Composable
private fun CompletionNotificationExamples() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompletionNotificationExampleRow(
            icon = Icons.Outlined.CalendarMonth,
            title = stringResource(R.string.onb_complete_notification_example_schedule_title),
            body = stringResource(R.string.onb_complete_notification_example_schedule_body),
        )
        CompletionNotificationExampleRow(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.onb_complete_notification_example_commitment_title),
            body = stringResource(R.string.onb_complete_notification_example_commitment_body),
        )
        CompletionNotificationExampleRow(
            icon = Icons.Outlined.Mic,
            title = stringResource(R.string.onb_complete_notification_example_meeting_title),
            body = stringResource(R.string.onb_complete_notification_example_meeting_body),
        )
    }
}

@Composable
private fun CompletionNotificationExampleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier.size(28.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(6.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CompletionPermissionActions(
    summary: OnboardingCompletionSummaryUi,
    onRequestNotifications: () -> Unit,
    onRequestMeetingRecording: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BecalmButton(
            text = stringResource(
                if (summary.notificationsEnabled) {
                    R.string.onb_complete_notification_permission_granted
                } else {
                    R.string.onb_complete_notification_permission_action
                },
            ),
            onClick = onRequestNotifications,
            enabled = !summary.notificationsEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding-complete-enable-notifications"),
        )
        BecalmButton(
            text = stringResource(
                if (summary.meetingRecordingReady) {
                    R.string.onb_complete_meeting_recording_permission_granted
                } else {
                    R.string.onb_complete_meeting_recording_permission_action
                },
            ),
            onClick = onRequestMeetingRecording,
            enabled = !summary.meetingRecordingReady,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("onboarding-complete-enable-meeting-recording"),
        )
    }
}

@Composable
private fun PushActionPreview(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = if (primary) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.White.copy(alpha = 0.12f)
        },
        contentColor = if (primary) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.inverseOnSurface
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

private enum class CompletionFeatureTone {
    Success,
}

@PreviewLightDark
@Composable
private fun PreviewOnboardingCompleteContent() {
    BecalmTheme {
        OnboardingCompleteContent(onStart = {})
    }
}
