package com.becalm.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceConnectionsCheckpoint2E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_015_user_connects_gmail_source_from_shared_connection_row() {
        var connected: OnboardingSourceProvider? = null

        renderSingleSource(
            item = sourceItem(
                provider = OnboardingSourceProvider.GMAIL,
                category = SourceConnectionCategory.Mail,
                title = string(R.string.raw_event_source_badge_gmail),
                state = SourceConnectionState.ConsentRequired,
                consentCopy = string(R.string.onb_sources_mail_consent_body),
            ),
            onConnect = { connected = it },
        )

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_gmail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_sources_connect_with_consent)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.GMAIL, connected)
        }
    }

    @Test
    fun e2e_016_user_connects_outlook_mail_source_from_same_connection_row() {
        var connected: OnboardingSourceProvider? = null

        renderSingleSource(
            item = sourceItem(
                provider = OnboardingSourceProvider.OUTLOOK_MAIL,
                category = SourceConnectionCategory.Mail,
                title = string(R.string.raw_event_source_badge_outlook_mail),
                state = SourceConnectionState.ConsentRequired,
                consentCopy = string(R.string.onb_sources_mail_consent_body),
            ),
            onConnect = { connected = it },
        )

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_outlook_mail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_sources_connect_with_consent)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.OUTLOOK_MAIL, connected)
        }
    }

    @Test
    fun e2e_017_user_connects_naver_imap_with_app_password() {
        var savedProvider: ImapProvider? = null
        var savedUsername: String? = null
        var savedPassword: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                ImapForm(
                    onSave = { provider, username, appPassword ->
                        savedProvider = provider
                        savedUsername = username
                        savedPassword = appPassword
                    },
                    onSkip = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("imap-provider-naver").performClick()
        composeTestRule.onNodeWithTag("imap-username").performTextInput("user@naver.com")
        composeTestRule.onNodeWithTag("imap-password").performTextInput("app-password")
        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(ImapProvider.Naver, savedProvider)
            assertEquals("user@naver.com", savedUsername)
            assertEquals("app-password", savedPassword)
        }
    }

    @Test
    fun e2e_018_imap_credential_validation_failure_stays_recoverable() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        var saveAttempts = 0

        composeTestRule.setContent {
            BecalmTheme {
                ImapSetupScreen(
                    navController = rememberNavController(),
                    emailConnectEventsOverride = events,
                    onSaveCredentials = { _, _, _ ->
                        saveAttempts += 1
                        events.tryEmit(EmailConnectEvent.Failed(EmailPipaProvider.NAVER_IMAP, "network"))
                    },
                    onSkipStep = {},
                    onNavigateToGoogleCalendar = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).assertIsNotEnabled()
        composeTestRule.onNodeWithTag("imap-username").performTextInput("user@naver.com")
        composeTestRule.onNodeWithTag("imap-password").performTextInput("bad-password")
        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).performClick()

        composeTestRule.onNodeWithText(string(R.string.onb_imap_error_network)).assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(1, saveAttempts)
        }
    }

    @Test
    fun e2e_019_user_connects_google_calendar_source() {
        var connected: OnboardingSourceProvider? = null

        renderSingleSource(
            item = sourceItem(
                provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                category = SourceConnectionCategory.Calendar,
                title = string(R.string.raw_event_source_badge_google_calendar),
            ),
            onConnect = { connected = it },
        )

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_google_calendar)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.GOOGLE_CALENDAR, connected)
        }
    }

    @Test
    fun e2e_020_user_connects_outlook_calendar_source() {
        var connected: OnboardingSourceProvider? = null

        renderSingleSource(
            item = sourceItem(
                provider = OnboardingSourceProvider.OUTLOOK_CALENDAR,
                category = SourceConnectionCategory.Calendar,
                title = string(R.string.raw_event_source_badge_outlook_calendar),
            ),
            onConnect = { connected = it },
        )

        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_outlook_calendar)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.OUTLOOK_CALENDAR, connected)
        }
    }

    @Test
    fun e2e_021_user_enables_voice_and_call_recording_ingestion_permission_shell() {
        var grants = 0
        var skips = 0

        composeTestRule.setContent {
            BecalmTheme {
                RecordingFolderContent(
                    displayPath = "/storage/emulated/0/Recordings",
                    voiceFolderDetected = true,
                    callFolderDetected = true,
                    requiresManualPicker = false,
                    onGrant = { grants += 1 },
                    onSkip = { skips += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(
            string(R.string.onb_recording_folder_detected_path_fmt, "/storage/emulated/0/Recordings"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grants)
            assertEquals(1, skips)
        }
    }

    private fun renderSingleSource(
        item: SourceConnectionItemUi,
        onConnect: (OnboardingSourceProvider) -> Unit,
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(item),
                    continueLabel = string(R.string.onb_sources_continue),
                    onConnect = onConnect,
                    onSkip = {},
                    onContinue = {},
                )
            }
        }
    }

    private fun sourceItem(
        provider: OnboardingSourceProvider,
        category: SourceConnectionCategory,
        title: String,
        state: SourceConnectionState = SourceConnectionState.Idle,
        consentCopy: String? = null,
    ): SourceConnectionItemUi =
        SourceConnectionItemUi(
            provider = provider,
            category = category,
            title = title,
            description = "${provider.step.name} source",
            consentCopy = consentCopy,
            state = state,
        )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    private fun string(resId: Int, value: String): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, value)
}
