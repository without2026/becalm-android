package com.becalm.android.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.becalm.android.R
import com.becalm.android.ui.components.BecalmButton
import com.becalm.android.ui.components.BecalmButtonVariant
import com.becalm.android.ui.components.BecalmScaffold
import com.becalm.android.ui.components.QuietPanel
import com.becalm.android.ui.theme.BecalmTheme

/**
 * Onboarding step 3 of 13: PIPA 제3자 제공 + 국외 이전 동의.
 *
 * Displays the 6 mandatory disclosure items required by PIPA Article 17 (제3자 제공) and
 * Article 28-8 (국외 이전) before BeCalm may upload audio bytes to Google LLC / Vertex AI.
 *
 * ## Navigation outcomes
 * - [동의]: writes pipa_third_party_consent=true → navigates to RecordingFolderScreen.
 * - [동의 안 함]: writes pipa_third_party_consent=false → skips RecordingFolderScreen,
 *   navigates directly to ContactsPermissionScreen (ONB-CONTACTS).
 *
 * ## Callback contract (used by [BecalmNavHost] and [PipaConsentScreenTest])
 * @param onConsented Called when user taps [동의]. The ViewModel write is performed before
 *   the callback fires. Callers are responsible for navigating to RecordingFolderScreen.
 * @param onDeclined  Called when user taps [동의 안 함]. The ViewModel write is performed
 *   before the callback fires. Callers navigate directly to ContactsPermissionScreen.
 *
 * Spec refs: ONB-PIPA, onboarding.spec.yml invariant §5.
 */
@Composable
public fun PipaThirdPartyConsentScreen(
    onConsented: () -> Unit,
    onDeclined: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate only after the DataStore write is confirmed (finding #2 fix).
    // Collecting pipaConsentEvents ensures onConsented/onDeclined are never called
    // before persistence is complete — PIPA compliance invariant.
    LaunchedEffect(Unit) {
        viewModel.pipaConsentEvents.collect { event ->
            when (event) {
                is PipaConsentEvent.PipaConsentSaved -> {
                    if (event.granted) onConsented() else onDeclined()
                }
                is PipaConsentEvent.PipaConsentSaveFailed -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    PipaThirdPartyConsentContent(
        onConsentedClick = { viewModel.onPipaConsentGranted() },
        onDeclinedClick = { viewModel.onPipaConsentDeclined() },
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Stateless content shell — extracted so [PipaConsentScreenTest] can invoke it
 * with fake callbacks without a Hilt graph, and so previews render cleanly.
 */
@Composable
internal fun PipaThirdPartyConsentContent(
    onConsentedClick: () -> Unit,
    onDeclinedClick: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    BecalmScaffold(
        title = stringResource(R.string.onb_pipa_title),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.onb_pipa_headline),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.onb_pipa_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── 6 disclosure bullets (ONB-PIPA mandatory) ──────────────────
            QuietPanel(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                val bullets = listOf(
                    R.string.onb_pipa_bullet_1_label to R.string.onb_pipa_bullet_1_value,
                    R.string.onb_pipa_bullet_2_label to R.string.onb_pipa_bullet_2_value,
                    R.string.onb_pipa_bullet_3_label to R.string.onb_pipa_bullet_3_value,
                    R.string.onb_pipa_bullet_4_label to R.string.onb_pipa_bullet_4_value,
                    R.string.onb_pipa_bullet_5_label to R.string.onb_pipa_bullet_5_value,
                    R.string.onb_pipa_bullet_6_label to R.string.onb_pipa_bullet_6_value,
                )
                bullets.forEachIndexed { index, (labelRes, valueRes) ->
                    if (index > 0) Spacer(modifier = Modifier.height(12.dp))
                    PipaDisclosureBullet(
                        label = stringResource(labelRes),
                        value = stringResource(valueRes),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── [동의] — primary visual weight ────────────────────────────
            BecalmButton(
                text = stringResource(R.string.onb_pipa_button_agree),
                onClick = onConsentedClick,
                variant = BecalmButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onb-pipa-agree"),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── [동의 안 함] — text button (lower visual weight) ───────────
            BecalmButton(
                text = stringResource(R.string.onb_pipa_button_decline),
                onClick = onDeclinedClick,
                variant = BecalmButtonVariant.Text,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onb-pipa-decline"),
            )
        }
    }
}

/** Single label + value row inside the disclosure glass panel. */
@Composable
private fun PipaDisclosureBullet(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@PreviewLightDark
@Composable
private fun PreviewPipaThirdPartyConsentScreen() {
    BecalmTheme {
        PipaThirdPartyConsentContent(
            onConsentedClick = {},
            onDeclinedClick = {},
        )
    }
}
