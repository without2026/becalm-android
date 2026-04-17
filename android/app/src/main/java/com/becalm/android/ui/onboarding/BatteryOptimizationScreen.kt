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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Onboarding step: battery optimization exemption for reliable background capture.
 *
 * Triggers [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] for the app package.
 * Additionally displays Samsung-specific guidance to remove app from Sleeping Apps list.
 * "Skip" advances without making any changes — the user can revisit in Settings.
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
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    // Result callback: navigate regardless of whether the user granted or not
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.onMarkStepStatus(OnboardingStep.SAMSUNG_DOZE, StepStatus.COMPLETE)
        navController.navigate(BecalmRoute.OnboardingColdSync.path)
    }

    BecalmScaffold(title = stringResource(R.string.onb_battery_title)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(MaterialTheme.shapes.medium)
                    .padding(16.dp),
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
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    launcher.launch(intent)
                },
                variant = BecalmButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.action_skip),
                onClick = {
                    viewModel.onSkipStep()
                    navController.navigate(BecalmRoute.OnboardingColdSync.path)
                },
                variant = BecalmButtonVariant.Text,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewBatteryOptimizationScreen() {
    BecalmTheme {
        BatteryOptimizationScreen(navController = rememberNavController())
    }
}
