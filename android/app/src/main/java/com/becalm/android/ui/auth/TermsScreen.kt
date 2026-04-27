package com.becalm.android.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.CollectFlowEffect
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.theme.glassPanel

/**
 * Terms and Privacy Policy acceptance screen.
 *
 * Displays the PIPA-required disclosure before the user proceeds to login.
 * User must explicitly tap "Continue" — the Accept label is shown as
 * informational copy, not a checkbox (checkbox-based consent is a future
 * legal-review item). Navigation goes to [BecalmRoute.Login] on acceptance.
 *
 * spec: AUTH-001 (precondition), PIPA Article 15 (consent disclosure)
 *
 * Navigation entry: [BecalmRoute.Terms]
 * Navigation exit: [BecalmRoute.Login]
 */
@Composable
public fun TermsScreen(
    navController: NavHostController,
    authViewModel: AuthViewModel? = null,
    authEffects: kotlinx.coroutines.flow.Flow<AuthEffect>? = null,
    onContinue: (() -> Unit)? = null,
    onDecline: (() -> Unit)? = null,
    onNavigateToLogin: (() -> Unit)? = null,
    onFinishApp: (() -> Unit)? = null,
) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val resolvedAuthViewModel = if (authEffects == null || onContinue == null || onDecline == null) {
        authViewModel ?: androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel<AuthViewModel>()
    } else {
        authViewModel
    }
    val resolvedOnContinue = onContinue ?: requireNotNull(resolvedAuthViewModel)::onAcceptTermsAndContinue
    val resolvedOnDecline = onDecline ?: requireNotNull(resolvedAuthViewModel)::onDeclineTerms
    val resolvedNavigateToLogin = onNavigateToLogin ?: {
        navController.navigate(BecalmRoute.Login.path)
    }
    val resolvedFinishApp = onFinishApp ?: {
        (context as? android.app.Activity)?.finish()
    }

    CollectFlowEffect(authEffects ?: requireNotNull(resolvedAuthViewModel).effects) { effect ->
        when (effect) {
            AuthEffect.NavigateToLogin -> resolvedNavigateToLogin()
            AuthEffect.FinishApp -> resolvedFinishApp()
        }
    }

    BecalmScaffold(title = stringResource(R.string.terms_title)) { padding ->
        TermsContent(
            accepted = accepted,
            onAcceptedChange = { accepted = it },
            onContinue = resolvedOnContinue,
            onDecline = resolvedOnDecline,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun TermsContent(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.terms_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(MaterialTheme.shapes.medium)
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.terms_pipa_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = accepted,
                onCheckedChange = onAcceptedChange,
                modifier = Modifier.testTag("terms-checkbox"),
            )
            Text(
                text = stringResource(R.string.terms_accept_checkbox),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        BecalmButton(
            text = stringResource(R.string.terms_cta),
            onClick = onContinue,
            enabled = accepted,
            variant = BecalmButtonVariant.Primary,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        BecalmButton(
            text = stringResource(R.string.terms_decline_cta),
            onClick = onDecline,
            variant = BecalmButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ─── Previews ─────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewTermsScreen() {
    BecalmTheme {
        TermsContent(
            accepted = false,
            onAcceptedChange = {},
            onContinue = {},
            onDecline = {},
        )
    }
}
