package com.becalm.android.ui.auth

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.becalmColors

/**
 * Splash gate screen.
 *
 * Observes [AuthUiState] and routes the user to:
 * - [BecalmRoute.Terms] when signed out (first run or logged out).
 * - [BecalmRoute.Today] when a valid session is already present.
 * Back-stack is cleared via `popUpTo` so the user cannot navigate back to splash.
 *
 * spec: AUTH-003, AUTH-004
 *
 * Primary VM: [AuthViewModel]
 * Navigation entry: start destination (splash)
 * Navigation exit: [BecalmRoute.Terms] | [BecalmRoute.Today]
 */
@Composable
public fun SplashScreen(
    navController: NavHostController,
    viewModel: AuthViewModel? = null,
    stateOverride: AuthUiState? = null,
    onNavigate: ((String) -> Unit)? = null,
) {
    val state = if (stateOverride != null) {
        stateOverride
    } else {
        val authViewModel = viewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<AuthViewModel>()
        val collectedState by authViewModel.uiState.collectAsStateWithLifecycle()
        collectedState
    }
    var navigated by rememberSaveable { mutableStateOf(false) }
    val navigate = onNavigate ?: { destination: String ->
        navController.navigate(destination) {
            popUpTo(BecalmRoute.Splash.path) { inclusive = true }
        }
    }

    LaunchedEffect(state, navigated) {
        Log.d("SplashDebug", "state=$state navigated=$navigated")
        if (navigated) return@LaunchedEffect
        when (val destination = splashDestinationFor(state)) {
            null -> Log.d("SplashDebug", "destination=null")
            else -> {
                Log.d("SplashDebug", "navigate -> $destination")
                navigated = true
                navigate(destination)
            }
        }
    }

    SplashContent()
}

internal fun splashDestinationFor(state: AuthUiState): String? =
    when (state) {
        is AuthUiState.SignedIn ->
            if (state.onboardingCompleted) {
                BecalmRoute.Today.path
            } else {
                BecalmRoute.OnboardingPipaConsent.path
            }
        is AuthUiState.SignedOut ->
            if (state.termsAccepted) {
                BecalmRoute.Login.path
            } else {
                BecalmRoute.Terms.path
            }
        is AuthUiState.Error -> BecalmRoute.Terms.path
        is AuthUiState.Loading -> null
    }

@Composable
internal fun SplashContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.becalmColors.cosmicBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.splash_title),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewSplashScreen() {
    BecalmTheme {
        SplashContent()
    }
}
