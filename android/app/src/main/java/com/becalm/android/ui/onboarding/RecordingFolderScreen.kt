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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Onboarding step: recording folder / READ_MEDIA_AUDIO permission.
 *
 * Shows a PIPA-compliant rationale card before launching the permission request.
 * "Grant" triggers the OS permission dialog; "Skip" advances without permission.
 * On grant or skip the VM step is marked and navigation moves to contacts.
 *
 * spec: ONB-001, ONB-003
 *
 * Primary VM: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingRecordingFolder]
 * Navigation exit: [BecalmRoute.OnboardingContacts]
 */
@Composable
public fun RecordingFolderScreen(
    navController: NavHostController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val status = if (granted) StepStatus.GRANTED else StepStatus.DENIED
        viewModel.onMarkStepStatus(OnboardingStep.RECORDING_FOLDER, status)
        navController.navigate(BecalmRoute.OnboardingContacts.path)
    }

    // READ_MEDIA_AUDIO was introduced in Android 13 (API 33). On API 28–32 the
    // equivalent capability is covered by READ_EXTERNAL_STORAGE.
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    BecalmScaffold(title = stringResource(R.string.onb_recording_folder_title)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.onb_recording_folder_headline),
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
                    text = stringResource(R.string.onb_recording_folder_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
            BecalmButton(
                text = stringResource(R.string.action_grant),
                onClick = { launcher.launch(audioPermission) },
                variant = BecalmButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.action_skip),
                onClick = {
                    viewModel.onSkipStep(OnboardingStep.RECORDING_FOLDER)
                    navController.navigate(BecalmRoute.OnboardingContacts.path)
                },
                variant = BecalmButtonVariant.Text,
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewRecordingFolderScreen() {
    BecalmTheme {
        RecordingFolderScreen(navController = rememberNavController())
    }
}
