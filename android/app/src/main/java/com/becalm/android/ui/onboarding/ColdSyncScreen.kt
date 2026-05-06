package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.components.sourcePresentationFor
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.today.ColdSyncEffect
import com.becalm.android.ui.today.DefaultColdSyncRuntimeCoordinator
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ColdSyncUiState
import com.becalm.android.ui.today.ColdSyncViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Cold sync loading screen shown on first run when Room is entirely empty.
 *
 * Observes [ColdSyncUiState.overallProgress] and shows a [CircularProgressIndicator].
 * Auto-navigates to [BecalmRoute.Today] when [ColdSyncUiState.done] is true.
 * The [OnboardingViewModel.onCompleteOnboarding] writes `onboarding_completed = true`
 * to DataStore when the user continues.
 *
 * spec: TDY-010, ONB-008
 *
 * Primary VM: [ColdSyncViewModel], secondary: [OnboardingViewModel]
 * Navigation entry: [BecalmRoute.OnboardingColdSync]
 * Navigation exit: [BecalmRoute.Today]
 */
@Composable
public fun ColdSyncScreen(
    navController: NavHostController,
    coldSyncViewModel: ColdSyncViewModel? = null,
    stateOverride: ColdSyncUiState? = null,
    effectsOverride: Flow<ColdSyncEffect>? = null,
    onScreenVisible: (() -> Unit)? = null,
    onNavigateToToday: (() -> Unit)? = null,
    onComplete: (() -> Unit)? = null,
    onSkipForNow: (() -> Unit)? = null,
) {
    val viewModel = if (stateOverride == null || effectsOverride == null || onScreenVisible == null || onComplete == null || onSkipForNow == null) {
        coldSyncViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<ColdSyncViewModel>()
    } else {
        coldSyncViewModel
    }
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val collectedState by requireNotNull(viewModel).state.collectAsStateWithLifecycle()
        collectedState
    }
    val navigateToToday = onNavigateToToday ?: {
        navController.navigate(BecalmRoute.Today.path) {
            popUpTo(BecalmRoute.OnboardingRecordingFolder.path) { inclusive = true }
        }
    }

    LaunchedEffect(viewModel, onScreenVisible) {
        onScreenVisible?.invoke() ?: requireNotNull(viewModel).onScreenVisible()
    }
    CollectFlowEffect(effectsOverride ?: requireNotNull(viewModel).effects) { effect ->
        when (effect) {
            ColdSyncEffect.NavigateToToday -> navigateToToday()
        }
    }

    // Auto-trigger completion when sync finishes
    LaunchedEffect(state.done) {
        if (state.done) {
            onComplete?.invoke() ?: requireNotNull(viewModel).onStage1Completed()
        }
    }

    BecalmScaffold(title = stringResource(R.string.onb_cold_sync_title)) { padding ->
        ColdSyncContent(
            modifier = Modifier.padding(padding),
            state = state,
            onContinue = { onComplete?.invoke() ?: requireNotNull(viewModel).onStage1Completed() },
            onSkipForNow = onSkipForNow ?: { requireNotNull(viewModel).onSkipForNow() },
        )
    }
}

@Composable
internal fun ColdSyncContent(
    state: ColdSyncUiState,
    onContinue: () -> Unit,
    onSkipForNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            if (!state.done) {
                CircularProgressIndicator(
                    progress = { state.overallProgress },
                    modifier = Modifier.size(72.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.onb_cold_sync_headline),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.onb_cold_sync_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(20.dp))
                ColdSyncSourceList(
                    perSourceProgress = state.perSourceProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.transitionError) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.onb_cold_sync_transition_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!state.skipEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.onb_cold_sync_skip_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                BecalmButton(
                    text = stringResource(R.string.onb_cold_sync_skip_cta),
                    onClick = onSkipForNow,
                    enabled = state.skipEnabled,
                    loading = state.transitioning,
                    variant = BecalmButtonVariant.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.onb_cold_sync_done),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(24.dp))
                BecalmButton(
                    text = stringResource(R.string.onb_cold_sync_cta),
                    onClick = onContinue,
                    loading = state.transitioning,
                    variant = BecalmButtonVariant.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ColdSyncSourceList(
    perSourceProgress: Map<String, Float>,
    modifier: Modifier = Modifier,
) {
    if (perSourceProgress.isEmpty()) return
    Column(modifier = modifier) {
        perSourceProgress.forEach { (sourceType, progress) ->
            ColdSyncSourceRow(sourceType = sourceType, progress = progress)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ColdSyncSourceRow(
    sourceType: String,
    progress: Float,
) {
    val label = if (sourceType == DefaultColdSyncRuntimeCoordinator.USER_PROFILE_SOURCE_ID) {
        stringResource(R.string.onb_cold_sync_profile)
    } else {
        stringResource(sourcePresentationFor(sourceType).labelRes)
    }
    val status = if (progress >= 1f) {
        stringResource(R.string.onb_cold_sync_source_done)
    } else {
        stringResource(R.string.onb_cold_sync_source_syncing)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (progress >= 1f) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@PreviewLightDark
@Composable
private fun PreviewColdSyncInProgress() {
    BecalmTheme {
        BecalmScaffold(title = "Initial Sync") { padding ->
            ColdSyncContent(
                modifier = Modifier.padding(padding),
                state = ColdSyncUiState(overallProgress = 0.45f, done = false),
                onContinue = {},
                onSkipForNow = {},
            )
        }
    }
}

@PreviewLightDark
@Composable
private fun PreviewColdSyncDone() {
    BecalmTheme {
        BecalmScaffold(title = "Initial Sync") { padding ->
            ColdSyncContent(
                modifier = Modifier.padding(padding),
                state = ColdSyncUiState(overallProgress = 1f, done = true),
                onContinue = {},
                onSkipForNow = {},
            )
        }
    }
}
