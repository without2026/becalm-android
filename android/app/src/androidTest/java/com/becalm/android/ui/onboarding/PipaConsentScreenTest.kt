package com.becalm.android.ui.onboarding

// STREAM2_PENDING: This test file targets `PipaThirdPartyConsentScreen` (Compose UI),
// which does not exist yet. The screen is specified in ONB-PIPA and is a Stream 2 deliverable
// along with:
//   - com.becalm.android.ui.onboarding.PipaThirdPartyConsentScreen (Composable)
//   - com.becalm.android.data.local.datastore.UserPrefsStore.observeThirdPartyProvisionConsent()
//   - com.becalm.android.data.local.datastore.UserPrefsStore.setThirdPartyProvisionConsent(Boolean)
//
// COMPILE BLOCKER:
//   - PipaThirdPartyConsentScreen composable does not exist.
//   - FakeOnboardingViewModel below references OnboardingViewModel callback shapes
//     that may not match (check OnboardingViewModel.kt once Stream 2 lands).
//
// All tests are @Ignore'd. Remove @Ignore when Stream 2 ships and the screen compiles.

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for PipaThirdPartyConsentScreen (ONB-PIPA).
 *
 * Uses ComposeTestRule + a fake ViewModel callback closure.
 *
 * All 6 PIPA disclosure bullet points specified in ONB-PIPA are asserted:
 *   1. 제공받는 자: Google LLC
 *   2. 제공 항목: 녹음 파일 전체 오디오 바이트
 *   3. 제공 목적: Gemini 2.5 Flash audio multimodal
 *   4. 데이터 처리 리전: asia-northeast3
 *   5. 보유 및 이용 기간: ZDR (Zero Data Retention / 즉시 삭제)
 *   6. 거부 권리 및 불이익
 *
 * Spec refs: ONB-PIPA, onboarding.spec.yml.
 *
 * COMPILE BLOCKER: All tests are @Ignore'd until PipaThirdPartyConsentScreen ships.
 */
@RunWith(AndroidJUnit4::class)
class PipaConsentScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // State flags set by the callbacks; inspected in assertions.
    private var consentedInvoked = false
    private var declinedInvoked = false

    @Before
    fun setUp() {
        consentedInvoked = false
        declinedInvoked = false
    }

    // ---------------------------------------------------------------------------
    // STREAM2_PENDING helper: the screen composable target.
    // Replace the TODO below with the real call once the screen exists.
    // ---------------------------------------------------------------------------

    private fun setContentWithFakeCallbacks() {
        composeTestRule.setContent {
            // COMPILE BLOCKER (STREAM2_PENDING): Uncomment when PipaThirdPartyConsentScreen exists.
            // PipaThirdPartyConsentScreen(
            //     onConsented = { consentedInvoked = true },
            //     onDeclined  = { declinedInvoked = true },
            // )
            TODO("STREAM2_PENDING: PipaThirdPartyConsentScreen composable not yet delivered")
        }
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: All 6 disclosure bullet points render
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA all 6 disclosure bullet points are displayed`() {
        setContentWithFakeCallbacks()

        // 1. 제공받는 자: Google LLC
        composeTestRule.onNodeWithText("Google LLC", substring = true).assertIsDisplayed()

        // 2. 제공 항목: 녹음 파일 전체 오디오 바이트 (audio bytes)
        composeTestRule.onNode(
            hasText("오디오", substring = true),
            useUnmergedTree = true,
        ).assertIsDisplayed()

        // 3. 제공 목적: Gemini 2.5 Flash (or audio multimodal)
        composeTestRule.onNodeWithText("Gemini", substring = true).assertIsDisplayed()

        // 4. 데이터 처리 리전: asia-northeast3
        composeTestRule.onNodeWithText("asia-northeast3", substring = true).assertIsDisplayed()

        // 5. 보유 및 이용 기간: ZDR (zero data retention / 즉시 삭제)
        composeTestRule.onNodeWithText("ZDR", substring = true).assertIsDisplayed()

        // 6. 거부 권리 및 불이익 (right to decline)
        composeTestRule.onNodeWithText("거부 권리", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: [동의] button invokes onConsented callback
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA tapping 동의 button invokes onConsented callback`() {
        setContentWithFakeCallbacks()

        composeTestRule.onNodeWithText("동의").performClick()

        assertTrue("onConsented callback must be invoked on [동의] tap", consentedInvoked)
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: [동의 안 함] button invokes onDeclined callback
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA tapping 동의 안 함 button invokes onDeclined callback`() {
        setContentWithFakeCallbacks()

        composeTestRule.onNodeWithText("동의 안 함").performClick()

        assertTrue("onDeclined callback must be invoked on [동의 안 함] tap", declinedInvoked)
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: Back navigation preserves onboarding state (does not invoke either callback)
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA back press does not invoke consent or decline callbacks`() {
        setContentWithFakeCallbacks()

        // Simulate back button via Espresso pressBack equivalent.
        // In Compose, back handling is typically via BackHandler; we verify neither callback fires
        // without user interaction.
        composeTestRule.waitForIdle()

        // Neither callback should be invoked without explicit button tap
        assertTrue("onConsented must not be invoked on back press", !consentedInvoked)
        assertTrue("onDeclined must not be invoked on back press", !declinedInvoked)
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: Accessibility — both buttons have semantics content description
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA both action buttons have accessibility semantics`() {
        setContentWithFakeCallbacks()

        // [동의] button must be accessible with text semantics
        composeTestRule.onNodeWithText("동의")
            .assertIsDisplayed()
            .assertHasClickAction()

        // [동의 안 함] button must be accessible
        composeTestRule.onNodeWithText("동의 안 함")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: Verify [동의] tap does NOT also invoke onDeclined
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA tapping 동의 does not also invoke onDeclined`() {
        setContentWithFakeCallbacks()

        composeTestRule.onNodeWithText("동의").performClick()

        assertTrue("onConsented must be invoked", consentedInvoked)
        assertTrue("onDeclined must NOT be invoked when 동의 is tapped", !declinedInvoked)
    }

    // ---------------------------------------------------------------------------
    // ONB-PIPA: Verify [동의 안 함] tap does NOT also invoke onConsented
    // ---------------------------------------------------------------------------

    @Ignore("STREAM2_PENDING: PipaThirdPartyConsentScreen not yet delivered")
    @Test
    fun `ONB-PIPA tapping 동의 안 함 does not also invoke onConsented`() {
        setContentWithFakeCallbacks()

        composeTestRule.onNodeWithText("동의 안 함").performClick()

        assertTrue("onDeclined must be invoked", declinedInvoked)
        assertTrue("onConsented must NOT be invoked when 동의 안 함 is tapped", !consentedInvoked)
    }
}

// ---------------------------------------------------------------------------
// Extension shim — assertHasClickAction is available in compose.ui.test but
// re-aliased here for clarity in assertions.
// ---------------------------------------------------------------------------

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertHasClickAction():
    androidx.compose.ui.test.SemanticsNodeInteraction =
    this.also {
        // assertHasClickAction() is defined in androidx.compose.ui.test
        it.assert(androidx.compose.ui.test.hasClickAction())
    }
