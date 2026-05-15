package com.becalm.android.integration.local.ui.onboarding

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.onboarding.PipaThirdPartyConsentContent
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PipaConsentUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pipa consent content shows disclosure bullets and ctas`() {
        // spec: ONB-PIPA
        composeRule.setContent {
            BecalmTheme {
                PipaThirdPartyConsentContent(
                    onConsentedClick = {},
                    onDeclinedClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_pipa_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_pipa_headline)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_pipa_bullet_1_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_pipa_bullet_1_value)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_pipa_bullet_2_value)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_pipa_bullet_3_value)).assertIsDisplayed()
        assertTrue(string(R.string.onb_pipa_bullet_1_value).contains("네이버 클라우드"))
        assertTrue(string(R.string.onb_pipa_bullet_1_value).contains("Google LLC"))
        assertTrue(string(R.string.onb_pipa_bullet_3_value).contains("CLOVA Speech"))
        assertTrue(!string(R.string.onb_pipa_bullet_3_value).contains("audio multimodal"))
        composeRule.onNodeWithText(string(R.string.onb_pipa_bullet_6_value)).assertExists()
        composeRule.onNodeWithText(string(R.string.onb_pipa_button_agree)).assertHasClickAction()
        composeRule.onNodeWithText(string(R.string.onb_pipa_button_decline)).assertHasClickAction()
    }

    @Test
    fun `pipa consent agree button invokes callback`() {
        var consented = 0

        composeRule.setContent {
            BecalmTheme {
                PipaThirdPartyConsentContent(
                    onConsentedClick = { consented += 1 },
                    onDeclinedClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("onb-pipa-agree")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertTrue(consented >= 1)
        }
    }

    @Test
    fun `pipa consent decline button invokes callback`() {
        var declined = 0

        composeRule.setContent {
            BecalmTheme {
                PipaThirdPartyConsentContent(
                    onConsentedClick = {},
                    onDeclinedClick = { declined += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("onb-pipa-decline")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertTrue(declined >= 1)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
