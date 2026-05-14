package com.becalm.android.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Onboarding step: battery optimization guidance for reliable background capture.
 *
 * Opens the app settings page and displays Samsung-specific guidance to remove
 * BeCalm from Sleeping Apps. It intentionally avoids restricted battery
 * optimization exemption requests; foreground catch-up remains the reliable
 * sync baseline.
 * "Skip" advances without making any changes.
 *
 * spec: ONB-005
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingBattery]
 * Navigation exit: [BecalmRoute.OnboardingColdSync]
 */
@Composable
public fun BatteryOptimizationScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel? = null,
    onBatteryResult: (() -> Unit)? = null,
    onRequestBatteryExemption: (() -> Unit)? = null,
    onAdvance: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val onboardingViewModel = if (
        onBatteryResult == null &&
            onRequestBatteryExemption == null &&
            onSkip == null
    ) {
        viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<OnboardingViewModel>()
    } else {
        viewModel
    }
    val advance = onAdvance ?: { navController.navigate(BecalmRoute.OnboardingColdSync.path) }

    // Result callback: navigate regardless of whether the user granted or not
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        (onBatteryResult ?: {
            onboardingViewModel?.onMarkStepStatus(OnboardingStep.BATTERY_OPT, StepStatus.COMPLETE)
        })()
        advance()
    }
    val requestBatteryExemption = onRequestBatteryExemption ?: {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        launcher.launch(intent)
    }
    val skipBatteryOptimization = onSkip ?: {
        onboardingViewModel?.onSkipStep(OnboardingStep.BATTERY_OPT)
        advance()
    }

    BecalmScaffold(title = stringResource(R.string.onb_battery_title)) { padding ->
        BatteryOptimizationContent(
            onGrant = requestBatteryExemption,
            onSkip = skipBatteryOptimization,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun BatteryOptimizationContent(
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
            text = stringResource(R.string.onb_battery_headline),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        QuietPanel(
            modifier = Modifier
                .fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.onb_battery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.onb_battery_samsung_guide),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        BecalmButton(
            text = stringResource(R.string.onb_battery_cta),
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
private fun PreviewBatteryOptimizationScreen() {
    BecalmTheme {
        BatteryOptimizationContent(
            onGrant = {},
            onSkip = {},
        )
    }
}
