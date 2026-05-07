package com.becalm.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ColdSyncUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColdSyncCheckpoint3E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_024_initial_cold_sync_starts_after_onboarding_with_visible_progress() {
        composeTestRule.setContent {
            BecalmTheme {
                ColdSyncContent(
                    state = ColdSyncUiState(
                        overallProgress = 0.4f,
                        perSourceProgress = mapOf(
                            SourceType.GMAIL to 0f,
                            SourceType.GOOGLE_CALENDAR to 1f,
                        ),
                        done = false,
                        skipEnabled = false,
                    ),
                    onContinue = {},
                    onSkipForNow = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_gmail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_google_calendar)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_skip_cta)).assertIsNotEnabled()
    }

    @Test
    fun e2e_025_cold_sync_can_be_skipped_after_delay() {
        var skipCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ColdSyncContent(
                    state = ColdSyncUiState(
                        overallProgress = 0.55f,
                        perSourceProgress = mapOf(SourceType.NAVER_IMAP to 0.5f),
                        done = false,
                        skipEnabled = true,
                    ),
                    onContinue = {},
                    onSkipForNow = { skipCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_skip_cta)).assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_skip_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, skipCount)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
