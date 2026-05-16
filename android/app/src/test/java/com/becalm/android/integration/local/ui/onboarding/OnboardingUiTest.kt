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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import com.becalm.android.ui.onboarding.OnboardingSelfIdentityUi
import com.becalm.android.ui.onboarding.OnboardingSourceOwnershipUi
import com.becalm.android.ui.onboarding.OnboardingSetupItem
import com.becalm.android.ui.onboarding.OnboardingSetupItemUi
import com.becalm.android.ui.onboarding.OnboardingSourceProvider
import com.becalm.android.ui.onboarding.OutlookCalendarOAuthContent
import com.becalm.android.ui.onboarding.OutlookMailOAuthContent
import com.becalm.android.ui.onboarding.RecordingFolderContent
import com.becalm.android.ui.onboarding.SourceConnectionCategory
import com.becalm.android.ui.onboarding.SourceConnectionItemUi
import com.becalm.android.ui.onboarding.SourceConnectionState
import com.becalm.android.ui.onboarding.SourceConnectionsContent
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
    fun `compact setup content shows required recommended and optional sections`() {
        var connectedSetupItem: OnboardingSetupItem? = null
        var skippedSetupItem: OnboardingSetupItem? = null

        composeRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = emptyList(),
                    headline = string(R.string.onb_setup_headline),
                    body = string(R.string.onb_setup_body),
                    continueLabel = string(R.string.onb_setup_start),
                    onConnect = {},
                    onSkip = {},
                    setupItems = listOf(
                        OnboardingSetupItemUi(
                            item = OnboardingSetupItem.Contacts,
                            title = string(R.string.onb_setup_contacts_title),
                            description = string(R.string.onb_setup_contacts_body),
                            state = SourceConnectionState.Idle,
                        ),
                    ),
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = "",
                        phone = "",
                        confirmed = false,
                        saving = false,
                    ),
                    onConnectSetupItem = { connectedSetupItem = it },
                    onSkipSetupItem = { skippedSetupItem = it },
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_setup_required_section)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_setup_required_privacy)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_setup_identity_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasText(string(R.string.onb_setup_recommended_section)))
        composeRule.onNodeWithText(string(R.string.onb_setup_recommended_section)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeRule.onNodeWithTag("source-connections-continue").assertIsNotEnabled()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connection-primary"))
        composeRule.onNodeWithText(string(R.string.onb_setup_contacts_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_connect)).performClick()
        composeRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeRule.runOnIdle {
            assertEquals(OnboardingSetupItem.Contacts, connectedSetupItem)
            assertEquals(OnboardingSetupItem.Contacts, skippedSetupItem)
        }
    }

    @Test
    fun `compact setup hides source rows until self identity is confirmed`() {
        var saveClicks = 0

        composeRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.GMAIL,
                            category = SourceConnectionCategory.Mail,
                            title = "Gmail",
                            description = "Mail copy",
                            consentCopy = null,
                            state = SourceConnectionState.Idle,
                        ),
                    ),
                    headline = string(R.string.onb_setup_headline),
                    body = string(R.string.onb_setup_body),
                    continueLabel = string(R.string.onb_setup_start),
                    onConnect = {},
                    onSkip = {},
                    setupItems = emptyList(),
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = "",
                        phone = "",
                        confirmed = false,
                        saving = false,
                    ),
                    onSelfDisplayNameChange = {},
                    onSelfPhoneChange = {},
                    onSaveSelfIdentity = { saveClicks += 1 },
                    onContinue = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Gmail").assertCountEquals(0)
        composeRule.onNodeWithTag("onboarding-self-display-name").assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("onboarding-self-save"))
        composeRule.onNodeWithTag("onboarding-self-save").performClick()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeRule.onNodeWithTag("source-connections-continue").assertIsNotEnabled()

        composeRule.runOnIdle {
            assertEquals(1, saveClicks)
        }
    }

    @Test
    fun `compact setup shows source ownership controls after self identity is confirmed`() {
        var ownershipUpdate: Pair<String, String>? = null

        composeRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = emptyList(),
                    headline = string(R.string.onb_setup_headline),
                    body = string(R.string.onb_setup_body),
                    continueLabel = string(R.string.onb_setup_start),
                    onConnect = {},
                    onSkip = {},
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = "민홍",
                        phone = "",
                        confirmed = true,
                        saving = false,
                    ),
                    sourceOwnerships = listOf(
                        OnboardingSourceOwnershipUi(
                            id = "conn-gmail",
                            title = "Gmail",
                            accountLabel = "work@example.com",
                            ownership = "unknown",
                            status = "connected",
                        ),
                    ),
                    onSourceOwnership = { id, ownership -> ownershipUpdate = id to ownership },
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-ownership-conn-gmail-self"))
        composeRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeRule.onNodeWithText("work@example.com").assertIsDisplayed()
        composeRule.onNodeWithTag("source-ownership-conn-gmail-self").performClick()

        composeRule.runOnIdle {
            assertEquals("conn-gmail" to "self", ownershipUpdate)
        }
    }

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

    @Test
    fun `cold sync content explains disabled skip and transition failure`() {
        composeRule.setContent {
            BecalmTheme {
                ColdSyncContent(
                    state = ColdSyncUiState(
                        overallProgress = 0.2f,
                        done = false,
                        skipEnabled = false,
                        transitionError = true,
                    ),
                    onContinue = {},
                    onSkipForNow = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_cold_sync_skip_disabled)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_cold_sync_transition_error)).assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
