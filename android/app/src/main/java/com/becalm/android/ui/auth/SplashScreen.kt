package com.becalm.android.ui.auth

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
import androidx.hilt.navigation.compose.hiltViewModel
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
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var navigated by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state, navigated) {
        if (navigated) return@LaunchedEffect
        when (state) {
            is AuthUiState.SignedIn -> {
                navigated = true
                val signedIn = state as AuthUiState.SignedIn
                val destination = if (signedIn.onboardingCompleted) {
                    BecalmRoute.Today.path
                } else {
                    BecalmRoute.OnboardingPipaConsent.path
                }
                navController.navigate(destination) {
                    popUpTo(BecalmRoute.Splash.path) { inclusive = true }
                }
            }
            is AuthUiState.SignedOut -> {
                navigated = true
                val destination = if ((state as AuthUiState.SignedOut).termsAccepted) {
                    BecalmRoute.Login.path
                } else {
                    BecalmRoute.Terms.path
                }
                navController.navigate(destination) {
                    popUpTo(BecalmRoute.Splash.path) { inclusive = true }
                }
            }
            is AuthUiState.Error -> {
                navigated = true
                navController.navigate(BecalmRoute.Terms.path) {
                    popUpTo(BecalmRoute.Splash.path) { inclusive = true }
                }
            }
            is AuthUiState.Loading -> Unit // wait
        }
    }

    SplashContent()
}

@Composable
private fun SplashContent() {
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
