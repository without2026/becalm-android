package com.becalm.android.ui.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

@Composable
public fun AuthRecoveryScreen(
    navController: NavHostController,
    termsAccepted: Boolean,
) {
    AuthRecoveryScreen(
        termsAccepted = termsAccepted,
        onReturnToAuthShell = {
            val target = if (termsAccepted) BecalmRoute.Login.path else BecalmRoute.Terms.path
            navController.navigate(target) {
                popUpTo(BecalmRoute.AuthRecovery(termsAccepted).path) { inclusive = true }
                launchSingleTop = true
            }
        },
        onRetry = {
            navController.navigate(BecalmRoute.Splash.path) {
                popUpTo(BecalmRoute.AuthRecovery(termsAccepted).path) { inclusive = true }
                launchSingleTop = true
            }
        },
    )
}

@Composable
internal fun AuthRecoveryScreen(
    termsAccepted: Boolean,
    onReturnToAuthShell: () -> Unit,
    onRetry: () -> Unit,
) {
    BecalmScaffold(title = stringResource(R.string.auth_recovery_title)) { padding ->
        AuthRecoveryContent(
            termsAccepted = termsAccepted,
            onReturnToAuthShell = onReturnToAuthShell,
            onRetry = onRetry,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun AuthRecoveryContent(
    termsAccepted: Boolean,
    onReturnToAuthShell: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = AuthRecoveryMaxContentWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.auth_recovery_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.auth_recovery_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(20.dp))
            BecalmButton(
                text = stringResource(
                    if (termsAccepted) {
                        R.string.auth_recovery_login_cta
                    } else {
                        R.string.auth_recovery_terms_cta
                    },
                ),
                onClick = onReturnToAuthShell,
                variant = BecalmButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth-recovery-return"),
            )
            Spacer(modifier = Modifier.height(12.dp))
            BecalmButton(
                text = stringResource(R.string.auth_recovery_retry_cta),
                onClick = onRetry,
                variant = BecalmButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("auth-recovery-retry"),
            )
        }
    }
}

private val AuthRecoveryMaxContentWidth = 480.dp

@PreviewLightDark
@Composable
private fun PreviewAuthRecoveryContent() {
    BecalmTheme {
        AuthRecoveryContent(
            termsAccepted = true,
            onReturnToAuthShell = {},
            onRetry = {},
        )
    }
}
