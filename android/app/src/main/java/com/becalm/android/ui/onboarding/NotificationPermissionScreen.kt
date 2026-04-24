package com.becalm.android.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Onboarding step (S6-E): POST_NOTIFICATIONS runtime permission.
 *
 * - API 33+ (Tiramisu): shows an inline rationale panel, then invokes
 *   [ActivityResultContracts.RequestPermission] for
 *   [Manifest.permission.POST_NOTIFICATIONS]. Grant / deny / skip all route forward
 *   to [BecalmRoute.OnboardingBattery]; refusing does not gate onboarding because
 *   the ONB-008 terminal gate accepts any of GRANTED / DENIED / SKIPPED / COMPLETE.
 *
 * - API 32 and below: the permission is implicitly granted at install time, so a
 *   [LaunchedEffect] marks the step [StepStatus.SKIPPED] and navigates forward in a
 *   single recomposition — the user sees at most a frame of the scaffold before
 *   leaving.
 *
 * spec: ONB-007 (observability surface) + internal `.spec/onboarding.spec.yml`
 * amendment (follow-up PR) that widens the canonical sequence to 13 steps.
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingNotificationPerm]
 * Navigation exit:  [BecalmRoute.OnboardingBattery]
 */
@Composable
public fun NotificationPermissionScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    sdkIntOverride: Int? = null,
    onAdvance: (() -> Unit)? = null,
    onMarkStepStatus: ((StepStatus) -> Unit)? = null,
    onGrantPermission: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    val onboardingViewModel = if (
        onMarkStepStatus == null ||
            onGrantPermission == null ||
            onSkip == null
    ) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val sdkInt = sdkIntOverride ?: Build.VERSION.SDK_INT
    val advance = onAdvance ?: { navController.navigate(BecalmRoute.OnboardingBattery.path) }

    // API 32 and below — permission is implicit; self-skip to keep the flow linear.
    if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
        LaunchedEffect(Unit) {
            (onMarkStepStatus
                ?: { status -> requireNotNull(onboardingViewModel).onMarkStepStatus(OnboardingStep.NOTIFICATION_PERM, status) }
                )(StepStatus.SKIPPED)
            advance()
        }
        return
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val status = if (granted) StepStatus.GRANTED else StepStatus.DENIED
        (onMarkStepStatus
            ?: { stepStatus -> requireNotNull(onboardingViewModel).onMarkStepStatus(OnboardingStep.NOTIFICATION_PERM, stepStatus) }
            )(status)
        advance()
    }
    val requestPermission = onGrantPermission ?: { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val skip = onSkip ?: {
        (onMarkStepStatus
            ?: { status -> requireNotNull(onboardingViewModel).onMarkStepStatus(OnboardingStep.NOTIFICATION_PERM, status) }
            )(StepStatus.SKIPPED)
        advance()
    }

    BecalmScaffold(title = stringResource(R.string.onb_notifications_title)) { padding ->
        NotificationPermissionContent(
            onGrant = requestPermission,
            onSkip = skip,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun NotificationPermissionContent(
    onGrant: () -> Unit,
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
            text = stringResource(R.string.onb_notifications_headline),
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
                text = stringResource(R.string.onb_notifications_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onb_notifications_rationale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.action_grant),
            onClick = onGrant,
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

@PreviewLightDark
@Composable
private fun PreviewNotificationPermissionScreen() {
    BecalmTheme {
        NotificationPermissionContent(
            onGrant = {},
            onSkip = {},
        )
    }
}
