package com.becalm.android.ui.onboarding

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PipaConsentScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun onb_pipa_renders_all_required_disclosure_bullets_and_actions() {
        setScreen()

        composeTestRule.onNodeWithText(string(R.string.onb_pipa_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_1_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_1_value)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_2_value)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_3_value)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_4_value)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_5_value)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_bullet_6_value)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_button_agree))
            .assertIsDisplayed()
            .assertHasClickAction()
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_button_decline))
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun onb_pipa_agree_button_invokes_consent_callback_only() {
        var consented = 0
        var declined = 0

        setScreen(
            onConsentedClick = { consented++ },
            onDeclinedClick = { declined++ },
        )

        composeTestRule.onNodeWithText(string(R.string.onb_pipa_button_agree)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, consented)
            assertEquals(0, declined)
        }
    }

    @Test
    fun onb_pipa_decline_button_invokes_decline_callback_only() {
        var consented = 0
        var declined = 0

        setScreen(
            onConsentedClick = { consented++ },
            onDeclinedClick = { declined++ },
        )

        composeTestRule.onNodeWithText(string(R.string.onb_pipa_button_decline)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(0, consented)
            assertEquals(1, declined)
        }
    }

    private fun setScreen(
        onConsentedClick: () -> Unit = {},
        onDeclinedClick: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                PipaThirdPartyConsentContent(
                    onConsentedClick = onConsentedClick,
                    onDeclinedClick = onDeclinedClick,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId)
}
