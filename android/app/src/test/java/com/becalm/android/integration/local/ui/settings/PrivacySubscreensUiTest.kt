package com.becalm.android.integration.local.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.local.datastore.PipaActionLogEntry
import com.becalm.android.ui.settings.AccountDeletionContent
import com.becalm.android.ui.settings.ActivityLogContent
import com.becalm.android.ui.settings.ConsentWithdrawContent
import com.becalm.android.ui.settings.PrivacyManagementUiState
import com.becalm.android.ui.settings.ProcessingPauseContent
import com.becalm.android.ui.settings.WithdrawConsentTarget
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PrivacySubscreensUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `consent withdraw content shows all toggles and withdraw callback`() {
        var withdrawn: WithdrawConsentTarget? = null

        composeRule.setContent {
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

        composeRule.onNodeWithText("Voice auto processing").assertIsDisplayed()
        composeRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeRule.onNodeWithText("Outlook Mail").assertIsDisplayed()
        composeRule.onNodeWithText("Naver Email").assertIsDisplayed()
        composeRule.onNodeWithText("Daum Email").assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-withdraw-gmail").performClick()

        composeRule.runOnIdle {
            assertEquals(WithdrawConsentTarget.GMAIL, withdrawn)
        }
    }

    @Test
    fun `processing pause content shows started at and confirm dialog`() {
        var pausedValue: Boolean? = null
        var processingPaused by mutableStateOf(false)

        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.privacy_pause_description)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_pause_started_fmt, "1744000000000")).assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-pause-switch").performClick()
        composeRule.onNodeWithText(string(R.string.privacy_pause_confirm_body)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_confirm)).performClick()

        composeRule.runOnIdle {
            assertEquals(true, pausedValue)
        }
    }

    @Test
    fun `account deletion content shows warning fields and delete callback`() {
        var deletedEmail: String? = null
        var deletedKeyword: String? = null

        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.privacy_delete_warning_fmt, 2, 3, 4)).assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-delete-email").performTextInput("user@example.com")
        composeRule.onNodeWithTag("privacy-delete-keyword").performTextInput("삭제")
        composeRule.onNodeWithTag("privacy-delete-confirm").performClick()

        composeRule.runOnIdle {
            assertEquals("user@example.com", deletedEmail)
            assertEquals("삭제", deletedKeyword)
        }
    }

    @Test
    fun `activity log content shows empty and populated rows`() {
        var showLogs by mutableStateOf(false)

        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.privacy_activity_log_empty)).assertIsDisplayed()

        composeRule.runOnIdle { showLogs = true }

        composeRule.onNodeWithText("data_export").assertIsDisplayed()
        composeRule.onNodeWithText("2026-04-24T01:00:00Z").assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
