package com.becalm.android.integration.local.ui.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.onboarding.BatteryOptimizationContent
import com.becalm.android.ui.onboarding.ColdSyncContent
import com.becalm.android.ui.onboarding.ContactsPermissionContent
import com.becalm.android.ui.onboarding.GmailOAuthContent
import com.becalm.android.ui.onboarding.GoogleCalendarOAuthContent
import com.becalm.android.ui.onboarding.ImapForm
import com.becalm.android.ui.onboarding.NotificationPermissionContent
import com.becalm.android.ui.onboarding.OnboardingEmailPipaConsentContent
import com.becalm.android.ui.onboarding.OutlookCalendarOAuthContent
import com.becalm.android.ui.onboarding.OutlookMailOAuthContent
import com.becalm.android.ui.onboarding.RecordingFolderContent
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ColdSyncUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OnboardingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `recording folder content shows detection summary fallback and ctas`() {
        var grantClicks = 0
        var skipClicks = 0

        composeRule.setContent {
            BecalmTheme {
                RecordingFolderContent(
                    displayPath = "/Recordings",
                    voiceFolderDetected = true,
                    callFolderDetected = false,
                    requiresManualPicker = true,
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_recording_folder_detected_path_fmt, "/Recordings")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_recording_folder_voice_status_fmt, string(R.string.onb_recording_folder_status_detected))).assertExists()
        composeRule.onNodeWithText(string(R.string.onb_recording_folder_call_status_fmt, string(R.string.onb_recording_folder_status_missing))).assertExists()
        composeRule.onNodeWithText(string(R.string.onb_recording_folder_manual_picker_fallback)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun `contacts permission content shows rationale pipa note and ctas`() {
        composeRule.setContent {
            BecalmTheme {
                ContactsPermissionContent(
                    onGrant = {},
                    onSkip = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_contacts_headline)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_contacts_pipa)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_grant)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_skip)).assertIsDisplayed()
    }

    @Test
    fun `email pipa content shows provider specific disclosure and callbacks`() {
        var agreeClicks = 0
        var denyClicks = 0

        composeRule.setContent {
            BecalmTheme {
                OnboardingEmailPipaConsentContent(
                    providerSlug = "gmail",
                    onAgree = { agreeClicks += 1 },
                    onDeny = { denyClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_pipa_email_headline_gmail)).assertIsDisplayed()
        composeRule.onAllNodesWithText(
            "${string(R.string.onb_pipa_email_recipient)}: ${string(R.string.onb_pipa_email_recipient_gmail)}",
        ).assertCountEquals(1)
        composeRule.onNodeWithText(string(R.string.onb_pipa_email_cta_agree)).performClick()
        composeRule.onNodeWithText(string(R.string.onb_pipa_email_cta_deny)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, agreeClicks)
            assertEquals(1, denyClicks)
        }
    }

    @Test
    fun `oauth placeholder contents show provider copy and ctas`() {
        composeRule.setContent {
            BecalmTheme {
                androidx.compose.foundation.layout.Column {
                    GmailOAuthContent(onConnect = {}, onSkip = {})
                    OutlookMailOAuthContent(onConnect = {}, onSkip = {})
                    GoogleCalendarOAuthContent(onConnect = {}, onSkip = {})
                    OutlookCalendarOAuthContent(onConnect = {}, onSkip = {})
                }
            }
        }

        composeRule.onAllNodesWithText(string(R.string.onb_gmail_headline)).assertCountEquals(1)
        composeRule.onAllNodesWithText(string(R.string.onb_outlook_mail_headline)).assertCountEquals(1)
        composeRule.onAllNodesWithText(string(R.string.onb_gcal_headline)).assertCountEquals(1)
        composeRule.onAllNodesWithText(string(R.string.onb_outlook_cal_headline)).assertCountEquals(1)
        composeRule.onAllNodesWithText(string(R.string.action_connect)).assertCountEquals(4)
        composeRule.onAllNodesWithText(string(R.string.action_skip)).assertCountEquals(4)
    }

    @Test
    fun `imap form shows provider selector validation and save callback`() {
        var savedProvider: String? = null
        var savedUsername: String? = null
        var savedPassword: String? = null
        var skipClicks = 0

        composeRule.setContent {
            BecalmTheme {
                ImapForm(
                    onSave = { provider, username, appPassword ->
                        savedProvider = provider.sourceType
                        savedUsername = username
                        savedPassword = appPassword
                    },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_imap_cta)).assertIsNotEnabled()
        composeRule.onNodeWithTag("imap-provider-daum").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("imap-username").performTextInput("user@daum.net")
        composeRule.onNodeWithTag("imap-password").performTextInput("app-password")
        composeRule.onNodeWithText(string(R.string.onb_imap_cta)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.onb_imap_cta)).performClick()
        composeRule.onAllNodesWithText(string(R.string.action_skip)).assertCountEquals(1)

        composeRule.runOnIdle {
            assertEquals("daum_imap", savedProvider)
            assertEquals("user@daum.net", savedUsername)
            assertEquals("app-password", savedPassword)
            assertEquals(0, skipClicks)
        }
    }

    @Test
    fun `notification permission content shows rationale and callbacks`() {
        var grantClicks = 0
        var skipClicks = 0

        composeRule.setContent {
            BecalmTheme {
                NotificationPermissionContent(
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_notifications_rationale)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun `battery optimization content shows guidance and callbacks`() {
        var grantClicks = 0
        var skipClicks = 0

        composeRule.setContent {
            BecalmTheme {
                BatteryOptimizationContent(
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_battery_samsung_guide)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_battery_cta)).performClick()
        composeRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun `cold sync content shows in progress and done states`() {
        var continueClicks = 0
        var skipClicks = 0
        var done by mutableStateOf(false)

        composeRule.setContent {
            BecalmTheme {
                ColdSyncContent(
                    state = ColdSyncUiState(
                        overallProgress = if (done) 1f else 0.45f,
                        done = done,
                        skipEnabled = true,
                    ),
                    onContinue = { continueClicks += 1 },
                    onSkipForNow = { skipClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_cold_sync_headline)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_cold_sync_skip_cta)).performClick()

        composeRule.runOnIdle { done = true }

        composeRule.onNodeWithText(string(R.string.onb_cold_sync_done)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_cold_sync_cta)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, skipClicks)
            assertEquals(1, continueClicks)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
