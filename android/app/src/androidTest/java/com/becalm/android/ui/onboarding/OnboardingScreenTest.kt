package com.becalm.android.ui.onboarding

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.datastore.EmailPipaProvider
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ColdSyncUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.navigation.compose.rememberNavController

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    // spec: ONB-SETUP

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun recording_folder_content_shows_detection_summary_fallback_and_ctas() {
        var grantClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
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

        composeTestRule.onNodeWithText(
            string(R.string.onb_recording_folder_detected_path_fmt, "/Recordings"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            string(
                R.string.onb_recording_folder_voice_status_fmt,
                string(R.string.onb_recording_folder_status_detected),
            ),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            string(
                R.string.onb_recording_folder_call_status_fmt,
                string(R.string.onb_recording_folder_status_missing),
            ),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_recording_folder_manual_picker_fallback))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun recording_folder_screen_uses_detection_override_and_dispatches_callbacks() {
        var grantClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                RecordingFolderScreen(
                    navController = rememberNavController(),
                    detectionOverride = RecordingFolderDetection(
                        displayPath = "/storage/emulated/0/Recordings",
                        preferredDocumentId = "primary:Recordings",
                        voiceFolderDetected = true,
                        callFolderDetected = false,
                        usedFallbackPath = false,
                        requiresManualPicker = true,
                    ),
                    audioPermissionOverride = "android.permission.READ_MEDIA_AUDIO",
                    onGrantFlow = { grantClicks += 1 },
                    onSkipFlow = { skipClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(
            string(R.string.onb_recording_folder_detected_path_fmt, "/storage/emulated/0/Recordings"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun contacts_permission_content_shows_rationale_pipa_note_and_ctas() {
        composeTestRule.setContent {
            BecalmTheme {
                ContactsPermissionContent(
                    onGrant = {},
                    onSkip = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_contacts_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_contacts_pipa)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).assertIsDisplayed()
    }

    @Test
    fun email_pipa_content_shows_provider_specific_disclosure_and_callbacks() {
        var agreeClicks = 0
        var denyClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                OnboardingEmailPipaConsentContent(
                    providerSlug = "gmail",
                    onAgree = { agreeClicks += 1 },
                    onDeny = { denyClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_pipa_email_headline_gmail)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(
            "${string(R.string.onb_pipa_email_recipient)}: ${string(R.string.onb_pipa_email_recipient_gmail)}",
        ).assertCountEquals(1)
        composeTestRule.onNodeWithTag("email-pipa-agree").performClick()
        composeTestRule.onNodeWithTag("email-pipa-deny").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, agreeClicks)
            assertEquals(1, denyClicks)
        }
    }

    @Test
    fun email_pipa_screen_launches_oauth_after_agree_persist_success() {
        val persisted = mutableListOf<Pair<List<EmailPipaProvider>, Boolean>>()
        var connectedProvider: EmailPipaProvider? = null

        composeTestRule.setContent {
            BecalmTheme {
                OnboardingEmailPipaConsentScreen(
                    providerSlug = "outlook_mail",
                    navController = rememberNavController(),
                    onPersistConsent = { recipients, granted ->
                        persisted += recipients to granted
                        true
                    },
                    onConnectEmailProvider = { provider, _ -> connectedProvider = provider },
                    onNavigate = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("email-pipa-agree").performClick()

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(
                listOf(listOf(EmailPipaProvider.OUTLOOK_MAIL) to true),
                persisted,
            )
            assertEquals(EmailPipaProvider.OUTLOOK_MAIL, connectedProvider)
        }
    }

    @Test
    fun email_pipa_screen_shows_snackbar_when_persist_fails() {
        composeTestRule.setContent {
            BecalmTheme {
                OnboardingEmailPipaConsentScreen(
                    providerSlug = "gmail",
                    navController = rememberNavController(),
                    onPersistConsent = { _, _ -> false },
                    onNavigate = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("email-pipa-agree").performClick()

        composeTestRule.waitForText(string(R.string.onb_pipa_email_error_write_failed))
        composeTestRule.onNodeWithText(string(R.string.onb_pipa_email_error_write_failed)).assertIsDisplayed()
    }

    @Test
    fun email_pipa_screen_routes_unknown_slug_to_gmail_and_reports_error() {
        var reported: Pair<OnboardingStep, String>? = null
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                OnboardingEmailPipaConsentScreen(
                    providerSlug = "unknown-provider",
                    navController = rememberNavController(),
                    onPersistConsent = { _, _ -> true },
                    onReportUnknownProvider = { step, code -> reported = step to code },
                    onNavigate = { destination = it },
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(
                OnboardingStep.LINK_GMAIL to "pipa_email_unknown_provider",
                reported,
            )
            assertEquals(
                BecalmRoute.OnboardingEmailPipa(EmailPipaProvider.GMAIL.storageKey).path,
                destination,
            )
        }
    }

    @Test
    fun oauth_placeholder_contents_show_provider_copy_and_ctas() {
        composeTestRule.setContent {
            BecalmTheme {
                Column {
                    GmailOAuthContent(onConnect = {}, onSkip = {})
                    OutlookMailOAuthContent(onConnect = {}, onSkip = {})
                    GoogleCalendarOAuthContent(onConnect = {}, onSkip = {})
                    OutlookCalendarOAuthContent(onConnect = {}, onSkip = {})
                }
            }
        }

        composeTestRule.onAllNodesWithText(string(R.string.onb_gmail_headline)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.onb_outlook_mail_headline)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.onb_gcal_headline)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.onb_outlook_cal_headline)).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(string(R.string.action_connect)).assertCountEquals(4)
        composeTestRule.onAllNodesWithText(string(R.string.action_skip)).assertCountEquals(4)
    }

    @Test
    fun imap_form_shows_provider_selector_validation_and_save_callback() {
        var savedProvider: String? = null
        var savedUsername: String? = null
        var savedPassword: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                ImapForm(
                    onSave = { provider, username, appPassword ->
                        savedProvider = provider.sourceType
                        savedUsername = username
                        savedPassword = appPassword
                    },
                    onSkip = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).assertIsNotEnabled()
        composeTestRule.onNodeWithTag("imap-provider-daum")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("imap-username").performTextInput("user@daum.net")
        composeTestRule.onNodeWithTag("imap-password").performTextInput("app-password")
        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).assertIsEnabled()
        composeTestRule.onNodeWithText(string(R.string.onb_imap_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("daum_imap", savedProvider)
            assertEquals("user@daum.net", savedUsername)
            assertEquals("app-password", savedPassword)
        }
    }

    @Test
    fun imap_setup_screen_navigates_when_connected_event_arrives() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ImapSetupScreen(
                    navController = rememberNavController(),
                    emailConnectEventsOverride = events,
                    onSaveCredentials = { _, _, _ -> },
                    onSkipStep = {},
                    onNavigateToGoogleCalendar = { navigateCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Connected(EmailPipaProvider.NAVER_IMAP))
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, navigateCount)
        }
    }

    @Test
    fun imap_setup_screen_shows_snackbar_for_failed_event() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)

        composeTestRule.setContent {
            BecalmTheme {
                ImapSetupScreen(
                    navController = rememberNavController(),
                    emailConnectEventsOverride = events,
                    onSaveCredentials = { _, _, _ -> },
                    onSkipStep = {},
                    onNavigateToGoogleCalendar = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Failed(EmailPipaProvider.DAUM_IMAP, "network"))
        }

        composeTestRule.onNodeWithText(string(R.string.onb_imap_error_network)).assertIsDisplayed()
    }

    @Test
    fun imap_setup_screen_dispatches_skip_and_navigation_callbacks() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        var skipCount = 0
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ImapSetupScreen(
                    navController = rememberNavController(),
                    emailConnectEventsOverride = events,
                    onSaveCredentials = { _, _, _ -> },
                    onSkipStep = { skipCount += 1 },
                    onNavigateToGoogleCalendar = { navigateCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("imap-skip").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, skipCount)
            assertEquals(1, navigateCount)
        }
    }

    @Test
    fun notification_permission_content_shows_rationale_and_callbacks() {
        var grantClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                NotificationPermissionContent(
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_notifications_rationale)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun notification_permission_screen_dispatches_runtime_callbacks_on_api_33_plus() {
        var grantClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                NotificationPermissionScreen(
                    navController = rememberNavController(),
                    sdkIntOverride = 33,
                    onAdvance = {},
                    onGrantPermission = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun notification_permission_screen_auto_skips_and_advances_below_api_33() {
        var markedStatus: StepStatus? = null
        var advanceCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                NotificationPermissionScreen(
                    navController = rememberNavController(),
                    sdkIntOverride = 32,
                    onAdvance = { advanceCount += 1 },
                    onMarkStepStatus = { markedStatus = it },
                    onGrantPermission = {},
                    onSkip = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            assertEquals(StepStatus.SKIPPED, markedStatus)
            assertEquals(1, advanceCount)
        }
    }

    @Test
    fun battery_optimization_content_shows_guidance_and_callbacks() {
        var grantClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                BatteryOptimizationContent(
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_battery_samsung_guide)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_battery_cta)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun battery_optimization_screen_dispatches_callbacks() {
        var requestClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                BatteryOptimizationScreen(
                    navController = rememberNavController(),
                    onRequestBatteryExemption = { requestClicks += 1 },
                    onAdvance = {},
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_battery_cta)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, requestClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun cold_sync_content_shows_in_progress_and_done_states() {
        var continueClicks = 0
        var skipClicks = 0
        var done by mutableStateOf(false)

        composeTestRule.setContent {
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

        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_skip_cta)).performClick()

        composeTestRule.runOnIdle { done = true }

        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_done)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, skipClicks)
            assertEquals(1, continueClicks)
        }
    }

    @Test
    fun contacts_permission_screen_consumes_permission_effects() {
        val effects = MutableSharedFlow<ContactsPermissionEffect>(extraBufferCapacity = 2)
        var permissionRequests = 0
        var navigateSourcesCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ContactsPermissionScreen(
                    navController = rememberNavController(),
                    effectsOverride = effects,
                    onGrant = {},
                    onSkip = {},
                    onLaunchSystemPermission = { permissionRequests += 1 },
                    onNavigateToSources = { navigateSourcesCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            effects.tryEmit(ContactsPermissionEffect.RequestSystemPermission)
            effects.tryEmit(ContactsPermissionEffect.NavigateToSources)
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(1, navigateSourcesCount)
        }
    }

    @Test
    fun contacts_permission_screen_dispatches_grant_and_skip_callbacks() {
        val effects = MutableSharedFlow<ContactsPermissionEffect>(extraBufferCapacity = 1)
        var grantClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                ContactsPermissionScreen(
                    navController = rememberNavController(),
                    effectsOverride = effects,
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                    onLaunchSystemPermission = {},
                    onNavigateToSources = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun source_connections_content_groups_sources_and_continues() {
        var continueClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.GMAIL,
                            category = SourceConnectionCategory.Mail,
                            title = "Gmail",
                            description = "Mail copy",
                            consentCopy = "Consent copy",
                            state = SourceConnectionState.ConsentRequired,
                        ),
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                            category = SourceConnectionCategory.Calendar,
                            title = "Google Calendar",
                            description = "Calendar copy",
                            consentCopy = null,
                            state = SourceConnectionState.Idle,
                        ),
                    ),
                    continueLabel = string(R.string.onb_sources_skip_remaining),
                    onConnect = {},
                    onSkip = {},
                    onContinue = { continueClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_sources_mail_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_sources_calendar_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Google Calendar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Consent copy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeTestRule.onNodeWithTag("source-connections-continue").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, continueClicks)
        }
    }

    @Test
    fun source_connections_content_dispatches_connect_and_skip_for_source_row() {
        var connectedProvider: OnboardingSourceProvider? = null
        var skippedProvider: OnboardingSourceProvider? = null

        composeTestRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                            category = SourceConnectionCategory.Calendar,
                            title = "Google Calendar",
                            description = "Calendar copy",
                            consentCopy = null,
                            state = SourceConnectionState.Idle,
                        ),
                    ),
                    continueLabel = string(R.string.onb_sources_continue),
                    onConnect = { connectedProvider = it },
                    onSkip = { skippedProvider = it },
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("source-connection-primary").performClick()
        composeTestRule.onNodeWithTag("source-connection-skip").performClick()

        composeTestRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.GOOGLE_CALENDAR, connectedProvider)
            assertEquals(OnboardingSourceProvider.GOOGLE_CALENDAR, skippedProvider)
        }
    }

    @Test
    fun source_connections_content_disables_terminal_source_actions() {
        composeTestRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.OUTLOOK_CALENDAR,
                            category = SourceConnectionCategory.Calendar,
                            title = "Outlook Calendar",
                            description = "Calendar copy",
                            consentCopy = null,
                            state = SourceConnectionState.Connected,
                        ),
                    ),
                    continueLabel = string(R.string.onb_sources_continue),
                    onConnect = {},
                    onSkip = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onAllNodesWithTag("source-connection-primary").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("source-connection-skip").assertCountEquals(0)
    }

    @Test
    fun settings_source_connections_screen_uses_settings_copy_and_done_action() {
        val emailEvents = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        val calendarEvents = MutableSharedFlow<CalendarConnectEvent>(extraBufferCapacity = 1)
        var doneClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsSourceConnectionsScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emailEvents,
                    calendarEventsOverride = calendarEvents,
                    stateOverride = OnboardingUiState(
                        stepStates = mapOf(OnboardingStep.LINK_GMAIL to StepStatus.SKIPPED),
                    ),
                    onConnectSource = { _, _ -> },
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onNavigateDone = { doneClicks += 1 },
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.settings_source_connections_headline)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.onb_sources_connect_with_consent)).onFirst()
            .assertIsEnabled()
        composeTestRule.onAllNodesWithText(string(R.string.onb_sources_skip_remaining)).assertCountEquals(0)
        composeTestRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeTestRule.onNodeWithTag("source-connections-continue").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, doneClicks)
        }
    }

    @Test
    fun settings_source_connections_returns_when_source_connects() {
        val emailEvents = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        val calendarEvents = MutableSharedFlow<CalendarConnectEvent>(extraBufferCapacity = 1)
        var doneClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                SettingsSourceConnectionsScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emailEvents,
                    calendarEventsOverride = calendarEvents,
                    stateOverride = OnboardingUiState(),
                    onConnectSource = { _, _ -> },
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onNavigateDone = { doneClicks += 1 },
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            emailEvents.tryEmit(EmailConnectEvent.Connected(EmailPipaProvider.GMAIL))
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, doneClicks)
            calendarEvents.tryEmit(CalendarConnectEvent.Connected(CalendarOAuthProvider.GOOGLE_CALENDAR))
        }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(2, doneClicks)
        }
    }

    @Test
    fun cold_sync_screen_triggers_visible_complete_and_navigate_callbacks() {
        val effects = MutableSharedFlow<com.becalm.android.ui.today.ColdSyncEffect>(extraBufferCapacity = 1)
        var visibleCount = 0
        var navigateCount = 0
        var completeCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ColdSyncScreen(
                    navController = rememberNavController(),
                    stateOverride = ColdSyncUiState(
                        overallProgress = 1f,
                        done = true,
                    ),
                    effectsOverride = effects,
                    onScreenVisible = { visibleCount += 1 },
                    onNavigateToToday = { navigateCount += 1 },
                    onComplete = { completeCount += 1 },
                    onSkipForNow = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            effects.tryEmit(com.becalm.android.ui.today.ColdSyncEffect.NavigateToToday)
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, visibleCount)
            assertEquals(1, completeCount)
            assertEquals(1, navigateCount)
        }
    }

    @Test
    fun cold_sync_screen_dispatches_skip_callback_from_button() {
        val effects = MutableSharedFlow<com.becalm.android.ui.today.ColdSyncEffect>(extraBufferCapacity = 1)
        var skipCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ColdSyncScreen(
                    navController = rememberNavController(),
                    stateOverride = ColdSyncUiState(
                        overallProgress = 0.2f,
                        done = false,
                        skipEnabled = true,
                    ),
                    effectsOverride = effects,
                    onScreenVisible = {},
                    onNavigateToToday = {},
                    onComplete = {},
                    onSkipForNow = { skipCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_skip_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, skipCount)
        }
    }

    @Test
    fun cold_sync_screen_dispatches_continue_callback_from_done_button() {
        val effects = MutableSharedFlow<com.becalm.android.ui.today.ColdSyncEffect>(extraBufferCapacity = 1)
        var completeCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ColdSyncScreen(
                    navController = rememberNavController(),
                    stateOverride = ColdSyncUiState(
                        overallProgress = 1f,
                        done = true,
                    ),
                    effectsOverride = effects,
                    onScreenVisible = {},
                    onNavigateToToday = {},
                    onComplete = { completeCount += 1 },
                    onSkipForNow = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(2, completeCount)
        }
    }

    @Test
    fun gmail_oauth_screen_consumes_connected_and_error_events() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 2)
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                GmailOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = {},
                    onSkip = {},
                    onNavigateDownstream = { navigateCount += 1 },
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Failed(EmailPipaProvider.GMAIL, "network"))
        }
        composeTestRule.waitForText(string(R.string.onb_gmail_error_network))
        composeTestRule.onNodeWithText(string(R.string.onb_gmail_error_network)).assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Connected(EmailPipaProvider.GMAIL))
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { navigateCount == 1 }
    }

    @Test
    fun gmail_oauth_screen_launches_pending_intent_when_required() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        var launchCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                GmailOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = {},
                    onSkip = {},
                    onNavigateDownstream = {},
                    onLaunchPendingIntent = { launchCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(
                EmailConnectEvent.PendingIntentRequired(
                    provider = EmailPipaProvider.GMAIL,
                    pendingIntent = testPendingIntent(),
                ),
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertEquals(1, launchCount)
        }
    }

    @Test
    fun gmail_oauth_screen_dispatches_connect_and_skip_callbacks() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        var connectClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                GmailOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = { connectClicks += 1 },
                    onSkip = { skipClicks += 1 },
                    onNavigateDownstream = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, connectClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun gmail_oauth_screen_ignores_user_cancelled_error() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)

        composeTestRule.setContent {
            BecalmTheme {
                GmailOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = {},
                    onSkip = {},
                    onNavigateDownstream = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Failed(EmailPipaProvider.GMAIL, "user_cancelled"))
        }

        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText(string(R.string.onb_gmail_error_unknown)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.onb_gmail_error_network)).assertCountEquals(0)
    }

    @Test
    fun outlook_mail_oauth_screen_consumes_pending_intent_error_and_connected_events() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 3)
        var launchCount = 0
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                OutlookMailOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = {},
                    onSkip = {},
                    onNavigateDownstream = { navigateCount += 1 },
                    onLaunchPendingIntent = { launchCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(
                EmailConnectEvent.PendingIntentRequired(
                    provider = EmailPipaProvider.OUTLOOK_MAIL,
                    pendingIntent = testPendingIntent(),
                ),
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { launchCount == 1 }
        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Failed(EmailPipaProvider.OUTLOOK_MAIL, "network"))
        }
        composeTestRule.waitForText(string(R.string.onb_outlook_error_network))
        composeTestRule.onNodeWithText(string(R.string.onb_outlook_error_network)).assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            events.tryEmit(EmailConnectEvent.Connected(EmailPipaProvider.OUTLOOK_MAIL))
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { navigateCount == 1 }
        composeTestRule.runOnIdle { assertEquals(1, launchCount) }
    }

    @Test
    fun outlook_mail_oauth_screen_dispatches_connect_and_skip_callbacks() {
        val events = MutableSharedFlow<EmailConnectEvent>(extraBufferCapacity = 1)
        var connectClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                OutlookMailOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = { connectClicks += 1 },
                    onSkip = { skipClicks += 1 },
                    onNavigateDownstream = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, connectClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun google_calendar_oauth_screen_consumes_connected_and_error_events() {
        val events = MutableSharedFlow<CalendarConnectEvent>(extraBufferCapacity = 2)
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                GoogleCalendarOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = {},
                    onSkip = {},
                    onNavigateDownstream = { navigateCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(
                CalendarConnectEvent.Failed(
                    CalendarOAuthProvider.GOOGLE_CALENDAR,
                    "not_implemented",
                ),
            )
        }
        composeTestRule.waitForText(string(R.string.onb_gcal_error_unavailable))
        composeTestRule.onNodeWithText(string(R.string.onb_gcal_error_unavailable)).assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            events.tryEmit(CalendarConnectEvent.Connected(CalendarOAuthProvider.GOOGLE_CALENDAR))
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { navigateCount == 1 }
    }

    @Test
    fun google_calendar_oauth_screen_dispatches_connect_and_skip_callbacks() {
        val events = MutableSharedFlow<CalendarConnectEvent>(extraBufferCapacity = 1)
        var connectClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                GoogleCalendarOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = { connectClicks += 1 },
                    onSkip = { skipClicks += 1 },
                    onNavigateDownstream = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, connectClicks)
            assertEquals(1, skipClicks)
        }
    }

    @Test
    fun outlook_calendar_oauth_screen_consumes_connected_and_error_events() {
        val events = MutableSharedFlow<CalendarConnectEvent>(extraBufferCapacity = 2)
        var navigateCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                OutlookCalendarOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = {},
                    onSkip = {},
                    onNavigateDownstream = { navigateCount += 1 },
                )
            }
        }

        composeTestRule.runOnIdle {
            events.tryEmit(
                CalendarConnectEvent.Failed(
                    CalendarOAuthProvider.OUTLOOK_CALENDAR,
                    "not_implemented",
                ),
            )
        }
        composeTestRule.waitForText(string(R.string.onb_outlook_cal_error_unavailable))
        composeTestRule.onNodeWithText(string(R.string.onb_outlook_cal_error_unavailable)).assertIsDisplayed()
        composeTestRule.mainClock.advanceTimeBy(5_000)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            events.tryEmit(CalendarConnectEvent.Connected(CalendarOAuthProvider.OUTLOOK_CALENDAR))
        }
        composeTestRule.waitUntil(timeoutMillis = 3_000) { navigateCount == 1 }
    }

    @Test
    fun outlook_calendar_oauth_screen_dispatches_connect_and_skip_callbacks() {
        val events = MutableSharedFlow<CalendarConnectEvent>(extraBufferCapacity = 1)
        var connectClicks = 0
        var skipClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                OutlookCalendarOAuthScreen(
                    navController = rememberNavController(),
                    eventsOverride = events,
                    onConnect = { connectClicks += 1 },
                    onSkip = { skipClicks += 1 },
                    onNavigateDownstream = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, connectClicks)
            assertEquals(1, skipClicks)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForText(text: String) {
        waitUntil(timeoutMillis = 3_000) {
            runCatching {
                onNodeWithText(text).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun testPendingIntent(): PendingIntent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PendingIntent.getActivity(
            context,
            0,
            Intent(context, androidx.activity.ComponentActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
