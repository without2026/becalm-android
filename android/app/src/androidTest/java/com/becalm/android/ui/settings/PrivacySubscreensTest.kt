package com.becalm.android.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacySubscreensTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun consent_withdraw_content_shows_all_toggles_and_withdraw_callback() {
        var withdrawn: WithdrawConsentTarget? = null

        composeTestRule.setContent {
            BecalmTheme {
                ConsentWithdrawContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        voiceConsentEnabled = true,
                        gmailConnected = true,
                        outlookConnected = true,
                        naverConnected = true,
                        daumConnected = true,
                        googleCalendarEnabled = true,
                        outlookCalendarEnabled = true,
                    ),
                    onWithdrawConsent = { withdrawn = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("privacy-withdraw-voice").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-withdraw-gmail").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-withdraw-outlook-mail").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-withdraw-naver").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-withdraw-daum").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-withdraw-gmail").performScrollTo().performClick()

        composeTestRule.runOnIdle {
            assertEquals(WithdrawConsentTarget.GMAIL, withdrawn)
        }
    }

    @Test
    fun processing_pause_content_shows_started_at_and_confirm_dialog() {
        var pausedValue: Boolean? = null
        var processingPaused by mutableStateOf(false)

        composeTestRule.setContent {
            BecalmTheme {
                ProcessingPauseContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        processingPaused = processingPaused,
                        pauseStartedAt = 1_744_000_000_000,
                    ),
                    onSetProcessingPaused = {
                        pausedValue = it
                        processingPaused = it
                    },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.privacy_pause_description)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.privacy_pause_started_fmt, "1744000000000"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-pause-switch").performClick()
        composeTestRule.onNodeWithText(string(R.string.privacy_pause_confirm_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_confirm)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(true, pausedValue)
        }
    }

    @Test
    fun account_deletion_content_shows_warning_fields_and_delete_callback() {
        var deletedEmail: String? = null
        var deletedKeyword: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                AccountDeletionContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        commitmentCount = 2,
                        enrichmentCount = 3,
                        emailCount = 4,
                    ),
                    onConfirmDeletion = { email, keyword ->
                        deletedEmail = email
                        deletedKeyword = keyword
                    },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.privacy_delete_warning_fmt, 2, 3, 4))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag("privacy-delete-email").performTextInput("user@example.com")
        composeTestRule.onNodeWithTag("privacy-delete-keyword").performTextInput("DELETE")
        composeTestRule.onNodeWithTag("privacy-delete-confirm").performClick()

        composeTestRule.runOnIdle {
            assertEquals("user@example.com", deletedEmail)
            assertEquals("DELETE", deletedKeyword)
        }
    }

    @Test
    // spec: PIPA-007
    fun activity_log_content_shows_empty_and_populated_rows() {
        var showLogs by mutableStateOf(false)

        composeTestRule.setContent {
            BecalmTheme {
                ActivityLogContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        activityLog = if (showLogs) {
                            listOf(
                                PipaActionLogEntry(
                                    action = "data_export",
                                    timestampIso = "2026-04-24T01:00:00Z",
                                    details = mapOf("status" to "ok"),
                                ),
                            )
                        } else {
                            emptyList()
                        },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.privacy_activity_log_empty)).assertIsDisplayed()

        composeTestRule.runOnIdle { showLogs = true }

        composeTestRule.onNodeWithText(string(R.string.privacy_activity_action_data_export)).assertIsDisplayed()
        composeTestRule.onNodeWithText("2026-04-24T01:00:00Z").assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
