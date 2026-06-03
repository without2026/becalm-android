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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.domain.onboarding.FirstMemoryKind
import com.becalm.android.domain.onboarding.FirstMemoryOrigin
import com.becalm.android.ui.onboarding.BatteryOptimizationContent
import com.becalm.android.ui.onboarding.ColdSyncContent
import com.becalm.android.ui.onboarding.ContactsPermissionContent
import com.becalm.android.ui.onboarding.FirstMemoryActivationContent
import com.becalm.android.ui.onboarding.FirstMemoryActivationUiState
import com.becalm.android.ui.onboarding.GmailActivationPreviewContent
import com.becalm.android.ui.onboarding.GmailActivationPreviewStatus
import com.becalm.android.ui.onboarding.GmailActivationPreviewUi
import com.becalm.android.ui.onboarding.GmailActivationPreviewUiState
import com.becalm.android.ui.onboarding.GmailOAuthContent
import com.becalm.android.ui.onboarding.GoogleCalendarOAuthContent
import com.becalm.android.ui.onboarding.ImapForm
import com.becalm.android.ui.onboarding.NotificationPermissionContent
import com.becalm.android.ui.onboarding.OnboardingCompleteContent
import com.becalm.android.ui.onboarding.OnboardingCompletionSummaryUi
import com.becalm.android.ui.onboarding.OnboardingContactsPreviewUi
import com.becalm.android.ui.onboarding.OnboardingIntroContent
import com.becalm.android.ui.onboarding.OnboardingEmailPipaConsentContent
import com.becalm.android.ui.onboarding.OnboardingSelfIdentityUi
import com.becalm.android.ui.onboarding.OnboardingSetupScreen
import com.becalm.android.ui.onboarding.OnboardingSetupStage
import com.becalm.android.ui.onboarding.OnboardingSourceOwnershipUi
import com.becalm.android.ui.onboarding.OnboardingSetupItem
import com.becalm.android.ui.onboarding.OnboardingSetupItemUi
import com.becalm.android.ui.onboarding.OnboardingSourceProvider
import com.becalm.android.ui.onboarding.OnboardingStep
import com.becalm.android.ui.onboarding.OnboardingUiState
import com.becalm.android.ui.onboarding.ONBOARDING_INTRO_PAGE_COUNT
import com.becalm.android.ui.onboarding.OutlookCalendarOAuthContent
import com.becalm.android.ui.onboarding.OutlookMailOAuthContent
import com.becalm.android.ui.onboarding.RecordingFolderContent
import com.becalm.android.ui.onboarding.SettingsSourceConnectionsScreen
import com.becalm.android.ui.onboarding.SourceConnectionCategory
import com.becalm.android.ui.onboarding.SourceConnectionItemUi
import com.becalm.android.ui.onboarding.SourceConnectionState
import com.becalm.android.ui.onboarding.SourceConnectionsContent
import com.becalm.android.ui.onboarding.StepStatus
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ColdSyncUiState
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `first memory activation keeps typed promise while source changes`() {
        var skipped = 0

        composeRule.setContent {
            var state by androidx.compose.runtime.remember {
                mutableStateOf(FirstMemoryActivationUiState())
            }

            BecalmTheme {
                FirstMemoryActivationContent(
                    state = state,
                    onOriginChange = { state = state.copy(origin = it) },
                    onPersonNameChange = { state = state.copy(personName = it) },
                    onPromiseTextChange = { state = state.copy(promiseText = it) },
                    onKindChange = { state = state.copy(kind = it) },
                    onDueHintChange = { state = state.copy(dueHint = it) },
                    onSave = {},
                    onSkip = { skipped += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.first_memory_headline)).assertIsDisplayed()
        composeRule.onNodeWithTag("first-memory-source-email").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("first-memory-person").performTextInput("민지")
        composeRule.onNodeWithTag("first-memory-promise").performTextInput("금요일까지 제안서 초안 보내기")
        composeRule.onNodeWithTag("first-memory-kind-my_action").performClick()
        composeRule.onNodeWithTag("first-memory-source-call").performClick()
        composeRule.onNodeWithTag("first-memory-person", useUnmergedTree = true)
            .assertTextContains("민지")
        composeRule.onNodeWithTag("first-memory-promise", useUnmergedTree = true)
            .assertTextContains("금요일까지 제안서 초안 보내기")

        composeRule.runOnIdle {
            assertEquals(0, skipped)
        }
    }

    @Test
    fun `first memory activation shows due hint for shared schedule`() {
        composeRule.setContent {
            var state by androidx.compose.runtime.remember {
                mutableStateOf(
                    FirstMemoryActivationUiState(
                        origin = FirstMemoryOrigin.MEETING,
                        kind = FirstMemoryKind.SHARED_SCHEDULE,
                    ),
                )
            }

            BecalmTheme {
                FirstMemoryActivationContent(
                    state = state,
                    onOriginChange = { state = state.copy(origin = it) },
                    onPersonNameChange = { state = state.copy(personName = it) },
                    onPromiseTextChange = { state = state.copy(promiseText = it) },
                    onKindChange = { state = state.copy(kind = it) },
                    onDueHintChange = { state = state.copy(dueHint = it) },
                    onSave = {},
                    onSkip = {},
                )
            }
        }

        composeRule.onAllNodesWithTag("first-memory-due-hint", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `first memory activation explains missing action owner before save`() {
        composeRule.setContent {
            BecalmTheme {
                FirstMemoryActivationContent(
                    state = FirstMemoryActivationUiState(
                        origin = FirstMemoryOrigin.EMAIL,
                        personName = "민지",
                        promiseText = "금요일까지 제안서 초안 보내기",
                    ),
                    onOriginChange = {},
                    onPersonNameChange = {},
                    onPromiseTextChange = {},
                    onKindChange = {},
                    onDueHintChange = {},
                    onSave = {},
                    onSkip = {},
                )
            }
        }

        composeRule.onNodeWithTag("first-memory-guidance")
            .assertExists()
            .assertTextContains(string(R.string.first_memory_next_kind))
        composeRule.onNodeWithTag("first-memory-save")
            .assertIsNotEnabled()
    }

    @Test
    fun `compact setup exposes sign out escape before main screen`() {
        var signOutClicks = 0

        composeRule.setContent {
            BecalmTheme {
                OnboardingSetupScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(sourceOwnershipsLoaded = true),
                    onConnectSource = { _, _ -> },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onCompleteSetup = {},
                    onNavigateToday = {},
                    onChangeAccount = { signOutClicks += 1 },
                    onNavigateAfterSignOut = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.action_sign_out)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, signOutClicks)
        }
    }

    @Test
    fun `compact setup first memory save remains reachable on short screens`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingSetupScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(
                        setupStage = OnboardingSetupStage.FIRST_MEMORY,
                        sourceOwnershipsLoaded = true,
                        firstMemory = FirstMemoryActivationUiState(
                            origin = FirstMemoryOrigin.EMAIL,
                            personName = "민지",
                            promiseText = "금요일까지 제안서 초안 보내기",
                            kind = FirstMemoryKind.MY_ACTION,
                        ),
                    ),
                    onConnectSource = { _, _ -> },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onCompleteSetup = {},
                    onNavigateToday = {},
                    onChangeAccount = {},
                    onNavigateAfterSignOut = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithTag("first-memory-save")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun `compact setup intro exposes optional source actions and gmail skip fallback`() {
        val connectedSources = mutableListOf<OnboardingSourceProvider>()
        var contactsClicks = 0
        var state by mutableStateOf(
            OnboardingUiState(
                sourceOwnershipsLoaded = true,
                introPageIndex = 2,
            ),
        )

        composeRule.setContent {
            BecalmTheme {
                OnboardingSetupScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = state,
                    onConnectSource = { provider, _ -> connectedSources += provider },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onConnectContacts = { contactsClicks += 1 },
                    onIntroNext = {
                        state = if (state.introPageIndex + 1 >= ONBOARDING_INTRO_PAGE_COUNT) {
                            state.copy(
                                setupStage = OnboardingSetupStage.FIRST_MEMORY,
                                introPageIndex = ONBOARDING_INTRO_PAGE_COUNT,
                            )
                        } else {
                            state.copy(introPageIndex = state.introPageIndex + 1)
                        }
                    },
                    onConnectRecording = {
                        state = state.copy(callRecordingConnectionState = SourceConnectionState.Connected)
                    },
                    onCompleteSetup = {},
                    onNavigateToday = {},
                    onChangeAccount = {},
                    onNavigateAfterSignOut = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_intro_device_sources_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-intro-connect-contacts")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("onboarding-intro-connect-call-recording")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("onboarding-intro-next").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodes(
            hasText(string(R.string.onb_intro_calendar_title).substringBefore('\n'), substring = true),
        ).assertCountEquals(1)
        composeRule.onNodeWithTag("onboarding-intro-connect-google-calendar")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("onboarding-intro-next")
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule.runOnIdle {
            state = state.copy(
                introPageIndex = 4,
                stepStates = state.stepStates + (OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.COMPLETE),
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(
            hasText(string(R.string.onb_intro_email_title).substringBefore('\n'), substring = true),
        ).assertCountEquals(1)
        composeRule.onNodeWithTag("onboarding-intro-connect-gmail")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.onAllNodesWithTag("onboarding-intro-manual-first-memory").assertCountEquals(0)
        composeRule.onNodeWithTag("onboarding-intro-skip-gmail")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(OnboardingSourceProvider.GOOGLE_CALENDAR, OnboardingSourceProvider.GMAIL),
                connectedSources,
            )
            assertEquals(1, contactsClicks)
        }
    }

    @Test
    fun `compact setup intro shows connected calendar as completed action`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingSetupScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(
                        sourceOwnershipsLoaded = true,
                        introPageIndex = 3,
                        stepStates = mapOf(OnboardingStep.LINK_GOOGLE_CALENDAR to StepStatus.COMPLETE),
                    ),
                    onConnectSource = { _, _ -> },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onCompleteSetup = {},
                    onNavigateToday = {},
                    onChangeAccount = {},
                    onNavigateAfterSignOut = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_intro_calendar_connected))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-intro-connect-google-calendar")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `compact setup intro shows connected gmail as completed action`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingSetupScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(
                        sourceOwnershipsLoaded = true,
                        introPageIndex = ONBOARDING_INTRO_PAGE_COUNT - 1,
                        stepStates = mapOf(OnboardingStep.LINK_GMAIL to StepStatus.COMPLETE),
                    ),
                    onConnectSource = { _, _ -> },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onCompleteSetup = {},
                    onNavigateToday = {},
                    onChangeAccount = {},
                    onNavigateAfterSignOut = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_intro_email_connected))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-intro-connect-gmail")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `compact setup intro shows gmail projection inline`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingIntroContent(
                    pageIndex = ONBOARDING_INTRO_PAGE_COUNT - 1,
                    gmailConnectionState = SourceConnectionState.Connected,
                    contactsConnectionState = SourceConnectionState.Idle,
                    calendarConnectionState = SourceConnectionState.Idle,
                    callRecordingConnectionState = SourceConnectionState.Idle,
                    gmailActivationPreview = GmailActivationPreviewUiState(
                        previews = listOf(
                            GmailActivationPreviewUi(
                                commitmentId = "commitment-1",
                                personId = null,
                                personName = "민지",
                                participantId = "participant-1",
                                participantName = "민지",
                                participantEmail = "minji@example.com",
                                participantPhone = null,
                                contactMatched = true,
                                title = "금요일까지 제안서 초안 보내기",
                                itemType = "action",
                                direction = "give",
                                scheduleStatus = null,
                                decisionStatus = null,
                                dueHint = "금요일",
                                sourceType = "gmail",
                                sourceTitle = "Re: BeCalm 사업 소개서 공유",
                            ),
                        ),
                        status = GmailActivationPreviewStatus.Ready,
                    ),
                    onNext = {},
                    onBack = {},
                    onConnectContacts = {},
                    onConnectGoogleCalendar = {},
                    onConnectCallRecording = {},
                    onSkipCallRecording = {},
                    onConnectGmail = {},
                )
            }
        }

        composeRule.onNodeWithTag("onboarding-gmail-inline-preview").assertIsDisplayed()
        composeRule.onNodeWithText("금요일까지 제안서 초안 보내기").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_intro_email_contact_match)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-intro-connect-gmail")
            .assertIsNotEnabled()
    }

    @Test
    fun `compact setup intro shows auth verified phone on identity page`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingIntroContent(
                    pageIndex = 1,
                    gmailConnectionState = SourceConnectionState.Idle,
                    contactsConnectionState = SourceConnectionState.Connected,
                    calendarConnectionState = SourceConnectionState.Idle,
                    callRecordingConnectionState = SourceConnectionState.Idle,
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = "민홍",
                        email = "",
                        phone = "+821012345678",
                        alias = "",
                        confirmed = true,
                        saving = false,
                        phoneReadOnly = true,
                        phoneVerified = true,
                    ),
                    onNext = {},
                    onBack = {},
                    onConnectContacts = {},
                    onConnectGoogleCalendar = {},
                    onConnectCallRecording = {},
                    onSkipCallRecording = {},
                    onConnectGmail = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_intro_identity_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-self-display-name").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.onb_setup_identity_phone_verified_help)).assertCountEquals(1)
        composeRule.onAllNodesWithText(string(R.string.onb_intro_next)).assertCountEquals(1)
    }

    @Test
    fun `compact setup intro groups contacts and call recording on device sources page`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingIntroContent(
                    pageIndex = 2,
                    gmailConnectionState = SourceConnectionState.Idle,
                    contactsConnectionState = SourceConnectionState.Connected,
                    calendarConnectionState = SourceConnectionState.Idle,
                    callRecordingConnectionState = SourceConnectionState.Idle,
                    contactsPreview = OnboardingContactsPreviewUi(
                        totalCount = 4,
                        names = listOf("김민지", "박준호", "이서연"),
                    ),
                    onNext = {},
                    onBack = {},
                    onConnectContacts = {},
                    onConnectGoogleCalendar = {},
                    onConnectCallRecording = {},
                    onSkipCallRecording = {},
                    onConnectGmail = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_intro_device_sources_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_intro_contacts_preview_count, 4)).assertIsDisplayed()
        composeRule.onNodeWithText("김민지").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.onb_intro_contacts_preview_more, 1)).assertCountEquals(1)
        composeRule.onAllNodesWithTag("onboarding-intro-connect-call-recording").assertCountEquals(1)
        composeRule.onAllNodesWithTag("onboarding-intro-next").assertCountEquals(1)
    }

    @Test
    fun `compact setup intro refreshes visible oauth provider for recovery`() {
        val refreshedProviders = mutableListOf<OnboardingSourceProvider>()

        composeRule.setContent {
            BecalmTheme {
                OnboardingSetupScreen(
                    navController = rememberNavController(),
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(
                        sourceOwnershipsLoaded = true,
                        introPageIndex = ONBOARDING_INTRO_PAGE_COUNT - 1,
                    ),
                    onConnectSource = { _, _ -> },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = { refreshedProviders += it },
                    onCompleteSetup = {},
                    onNavigateToday = {},
                    onChangeAccount = {},
                    onNavigateAfterSignOut = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 1_000) {
            refreshedProviders.contains(OnboardingSourceProvider.GMAIL)
        }
    }

    @Test
    fun `gmail activation preview shows extracted commitment without manual shortcut`() {
        var usedPreview = 0

        composeRule.setContent {
            BecalmTheme {
                GmailActivationPreviewContent(
                    state = GmailActivationPreviewUiState(
                        preview = GmailActivationPreviewUi(
                            commitmentId = "commitment-1",
                            personId = "person-minji",
                            personName = "민지",
                            participantId = "participant-minji",
                            participantName = "민지",
                            participantEmail = "minji@example.com",
                            participantPhone = null,
                            contactMatched = true,
                            title = "금요일까지 제안서 초안 보내기",
                            itemType = "action",
                            direction = "give",
                            scheduleStatus = null,
                            decisionStatus = null,
                            dueHint = "금요일",
                            sourceType = "gmail",
                            sourceTitle = "Re: BeCalm 사업 소개서 공유",
                        ),
                    ),
                    onUsePreview = { usedPreview += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_activation_preview_ready_title)).assertIsDisplayed()
        composeRule.onNodeWithText("민지").assertIsDisplayed()
        composeRule.onNodeWithText("금요일까지 제안서 초안 보내기").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_setup_start)).assertIsDisplayed()
        composeRule.onNodeWithTag("gmail-activation-use-preview").performClick()
        composeRule.onAllNodesWithTag("gmail-activation-manual").assertCountEquals(0)

        composeRule.runOnIdle {
            assertEquals(1, usedPreview)
        }
    }

    @Test
    fun `gmail activation pending keeps user on gmail surface with wait and start actions`() {
        var waitClicks = 0
        var startClicks = 0

        composeRule.setContent {
            BecalmTheme {
                GmailActivationPreviewContent(
                    state = GmailActivationPreviewUiState(
                        status = GmailActivationPreviewStatus.StillProcessing,
                        progress = 0.55f,
                        progressMessage = "메일 속 약속 후보를 확인하고 있습니다",
                    ),
                    onUsePreview = {},
                    onRetry = { waitClicks += 1 },
                    onStartWithoutPreview = { startClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_activation_preview_processing_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_intro_email_connected)).assertIsDisplayed()
        composeRule.onNodeWithTag("gmail-activation-progress").assertIsDisplayed()
        composeRule.onNodeWithTag("gmail-activation-wait").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("gmail-activation-start-without-preview").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithTag("gmail-activation-manual").assertCountEquals(0)

        composeRule.runOnIdle {
            assertEquals(1, waitClicks)
            assertEquals(1, startClicks)
        }
    }

    @Test
    fun `onboarding completion shows ready copy and starts BeCalm`() {
        var started = 0

        composeRule.setContent {
            BecalmTheme {
                OnboardingCompleteContent(
                    onStart = { started += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_complete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_complete_body_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_push_title)))
        composeRule.onNodeWithText(string(R.string.onb_complete_push_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_complete_push_primary_action)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_start)))
        composeRule.onNodeWithTag("onboarding-complete-start")
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, started)
        }
    }

    @Test
    fun `onboarding completion does not require first memory preview`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingCompleteContent(
                    onStart = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_complete_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_complete_feature_sources_later)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_start)))
        composeRule.onNodeWithText(string(R.string.onb_complete_start)).assertIsDisplayed()
    }

    @Test
    fun `onboarding completion exposes notification and meeting recording permission actions`() {
        var notificationClicks = 0
        var meetingRecordingClicks = 0

        composeRule.setContent {
            BecalmTheme {
                OnboardingCompleteContent(
                    onStart = {},
                    onRequestNotifications = { notificationClicks += 1 },
                    onRequestMeetingRecording = { meetingRecordingClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_notification_permission_action)))
        composeRule.onNodeWithTag("onboarding-complete-enable-notifications")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_meeting_recording_permission_action)))
        composeRule.onNodeWithTag("onboarding-complete-enable-meeting-recording")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, notificationClicks)
            assertEquals(1, meetingRecordingClicks)
        }
    }

    @Test
    fun `onboarding completion reflects granted notification and meeting recording states`() {
        composeRule.setContent {
            BecalmTheme {
                OnboardingCompleteContent(
                    summary = OnboardingCompletionSummaryUi(
                        notificationsEnabled = true,
                        meetingRecordingReady = true,
                    ),
                    onStart = {},
                )
            }
        }

        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_notification_permission_granted)))
        composeRule.onNodeWithTag("onboarding-complete-enable-notifications")
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_meeting_recording_permission_granted)))
        composeRule.onNodeWithTag("onboarding-complete-enable-meeting-recording")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun `onboarding completion uses person route when available`() {
        var openedPerson = 0

        composeRule.setContent {
            BecalmTheme {
                OnboardingCompleteContent(
                    summary = OnboardingCompletionSummaryUi(
                        personId = "person-minji",
                        personName = "민지",
                    ),
                    onStart = {},
                    onOpenPerson = { openedPerson += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_complete_body_person_fmt, "민지")).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-complete")
            .performScrollToNode(hasText(string(R.string.onb_complete_detail_action)))
        composeRule.onNodeWithTag("onboarding-complete-open-person")
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(1, openedPerson)
        }
    }

    @Test
    fun `compact setup content shows identity one starter source and later add notice`() {
        var connectedSetupItem: OnboardingSetupItem? = null
        var skippedSetupItem: OnboardingSetupItem? = null
        var connectedSource: OnboardingSourceProvider? = null

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
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.OUTLOOK_MAIL,
                            category = SourceConnectionCategory.Mail,
                            title = "Outlook Mail",
                            description = "Outlook copy",
                            consentCopy = null,
                            state = SourceConnectionState.Idle,
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
                    headline = string(R.string.onb_setup_headline),
                    body = string(R.string.onb_setup_body),
                    continueLabel = string(R.string.onb_setup_start),
                    onConnect = { connectedSource = it },
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
                        displayName = "민수",
                        email = "me@example.com",
                        phone = "",
                        alias = "",
                        confirmed = true,
                        saving = false,
                    ),
                    onConnectSetupItem = { connectedSetupItem = it },
                    onSkipSetupItem = { skippedSetupItem = it },
                    onContinue = {},
                    progressiveSetup = true,
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_setup_identity_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.onb_setup_recommended_section)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.onb_setup_optional_section)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.onb_setup_contacts_title)).assertCountEquals(0)
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasText(string(R.string.onb_setup_first_source_section)))
        composeRule.onNodeWithText(string(R.string.onb_setup_first_source_section)).assertIsDisplayed()
        composeRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeRule.onAllNodesWithText("Outlook Mail").assertCountEquals(0)
        composeRule.onAllNodesWithText("Google Calendar").assertCountEquals(0)
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasText(string(R.string.onb_setup_add_later_title)))
        composeRule.onNodeWithText(string(R.string.onb_setup_add_later_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeRule.onNodeWithTag("source-connections-continue").assertIsEnabled()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connection-primary"))
        composeRule.onNodeWithText(string(R.string.action_connect)).performClick()

        composeRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.GMAIL, connectedSource)
            assertEquals(null, connectedSetupItem)
            assertEquals(null, skippedSetupItem)
        }
    }

    @Test
    fun `compact setup does not show permissions or source rows before identity is confirmed`() {
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
                        email = "",
                        phone = "",
                        alias = "",
                        confirmed = false,
                        saving = false,
                    ),
                    onContinue = {},
                    progressiveSetup = true,
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_setup_identity_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText("Gmail").assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.onb_setup_contacts_title)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.onb_setup_first_source_section)).assertCountEquals(0)
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeRule.onNodeWithTag("source-connections-continue").assertIsNotEnabled()
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
                        email = "",
                        phone = "",
                        alias = "",
                        confirmed = false,
                        saving = false,
                    ),
                    onSelfDisplayNameChange = {},
                    onSelfEmailChange = {},
                    onSelfPhoneChange = {},
                    onSelfAliasChange = {},
                    onSaveSelfIdentity = { saveClicks += 1 },
                    onContinue = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Gmail").assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.onb_setup_identity_body)).assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-self-display-name").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_setup_identity_display_name_help)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("onboarding-self-email"))
        composeRule.onNodeWithTag("onboarding-self-email").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_setup_identity_email_help)).assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("onboarding-self-save"))
        composeRule.onNodeWithTag("onboarding-self-save").performScrollTo().performClick()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeRule.onNodeWithTag("source-connections-continue").assertIsNotEnabled()

        composeRule.runOnIdle {
            assertEquals(1, saveClicks)
        }
    }

    @Test
    fun `compact setup shows connected accounts without ownership gate after self identity is confirmed`() {
        var continueClicks = 0

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
                        email = "me@example.com",
                        phone = "",
                        alias = "MH",
                        confirmed = true,
                        saving = false,
                    ),
                    connectedAccounts = listOf(
                        OnboardingSourceOwnershipUi(
                            id = "conn-gmail",
                            title = "Gmail",
                            accountLabel = "work@example.com",
                            status = "connected",
                        ),
                    ),
                    onContinue = { continueClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connected-account-conn-gmail"))
        composeRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeRule.onNodeWithText("work@example.com").assertIsDisplayed()
        composeRule.onAllNodesWithTag("source-ownership-conn-gmail-self").assertCountEquals(0)
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connections-continue"))
        composeRule.onNodeWithTag("source-connections-continue").assertIsEnabled()
        composeRule.onNodeWithTag("source-connections-continue").performClick()

        composeRule.runOnIdle {
            assertEquals(1, continueClicks)
        }
    }

    @Test
    fun `settings gmail connection screen shows existing gmail accounts and add another action`() {
        composeRule.setContent {
            BecalmTheme {
                SettingsSourceConnectionsScreen(
                    navController = rememberNavController(),
                    targetProviderSlug = "gmail",
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(
                        sourceOwnerships = listOf(
                            OnboardingSourceOwnershipUi(
                                id = "conn-gmail-1",
                                title = "Gmail",
                                accountLabel = "work@example.com",
                                status = "connected",
                                provider = "google",
                                capability = "mail",
                            ),
                            OnboardingSourceOwnershipUi(
                                id = "conn-google-calendar-1",
                                title = "Google Calendar",
                                accountLabel = "calendar@example.com",
                                status = "connected",
                                provider = "google",
                                capability = "calendar",
                            ),
                        ),
                        sourceOwnershipsLoaded = true,
                    ),
                    onConnectSource = { _, _ -> },
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onNavigateDone = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.settings_source_connections_add_another_account))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connected-account-conn-gmail-1"))
        composeRule.onNodeWithText("work@example.com").assertIsDisplayed()
        composeRule.onAllNodesWithText("Google Calendar").assertCountEquals(0)
        composeRule.onAllNodesWithTag("source-ownership-conn-gmail-1-self").assertCountEquals(0)
    }

    @Test
    fun `settings scoped google calendar screen exposes one connect action`() {
        var connectedProvider: OnboardingSourceProvider? = null

        composeRule.setContent {
            BecalmTheme {
                SettingsSourceConnectionsScreen(
                    navController = rememberNavController(),
                    targetProviderSlug = "google_calendar",
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(sourceOwnershipsLoaded = true),
                    onConnectSource = { provider, _ -> connectedProvider = provider },
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onNavigateDone = {},
                    onLaunchPendingIntent = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_gcal_title)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.onb_intro_calendar_body)).assertCountEquals(2)
        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connection-primary"))
        composeRule.onNodeWithTag("source-connection-primary").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.GOOGLE_CALENDAR, connectedProvider)
        }
    }

    @Test
    fun `source connection row disables skip while external auth is pending`() {
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
                            state = SourceConnectionState.PendingExternalAuth,
                        ),
                    ),
                    headline = string(R.string.onb_sources_headline),
                    body = string(R.string.onb_sources_body),
                    continueLabel = string(R.string.onb_sources_skip_remaining),
                    onConnect = {},
                    onSkip = {},
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithTag("source-connection-primary").assertIsNotEnabled()
        composeRule.onNodeWithTag("source-connection-skip").assertIsNotEnabled()
    }

    @Test
    fun `source connections content tells naver and daum email can be connected later`() {
        composeRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.GMAIL,
                            category = SourceConnectionCategory.Mail,
                            title = "Gmail",
                            description = "Mail copy",
                            consentCopy = string(R.string.onb_sources_mail_consent_body),
                            state = SourceConnectionState.ConsentRequired,
                        ),
                    ),
                    headline = string(R.string.onb_sources_headline),
                    body = string(R.string.onb_sources_body),
                    continueLabel = string(R.string.onb_sources_skip_remaining),
                    onConnect = {},
                    onSkip = {},
                    onContinue = {},
                    showImapLaterNotice = true,
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_sources_imap_later_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_sources_imap_later_body)).assertIsDisplayed()
    }

    @Test
    fun `skipped setup item remains retryable in compact setup`() {
        var connectClicks = 0

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
                            state = SourceConnectionState.Skipped,
                        ),
                    ),
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = "민홍",
                        email = "me@example.com",
                        phone = "",
                        alias = "",
                        confirmed = true,
                        saving = false,
                    ),
                    onConnectSetupItem = { connectClicks += 1 },
                    onSkipSetupItem = {},
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connection-primary"))
        composeRule.onNodeWithTag("source-connection-primary").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(1, connectClicks)
        }
    }

    @Test
    fun `skipped optional source remains retryable in compact setup`() {
        var connectedProvider: OnboardingSourceProvider? = null

        composeRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        SourceConnectionItemUi(
                            provider = OnboardingSourceProvider.GMAIL,
                            category = SourceConnectionCategory.Mail,
                            title = "Gmail",
                            description = "Mail copy",
                            consentCopy = string(R.string.onb_sources_mail_consent_body),
                            state = SourceConnectionState.Skipped,
                        ),
                    ),
                    headline = string(R.string.onb_setup_headline),
                    body = string(R.string.onb_setup_body),
                    continueLabel = string(R.string.onb_setup_start),
                    onConnect = { connectedProvider = it },
                    onSkip = {},
                    selfIdentity = OnboardingSelfIdentityUi(
                        displayName = "민홍",
                        email = "me@example.com",
                        phone = "",
                        alias = "",
                        confirmed = true,
                        saving = false,
                    ),
                    onContinue = {},
                )
            }
        }

        composeRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasTestTag("source-connection-primary"))
        composeRule.onNodeWithTag("source-connection-primary").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals(OnboardingSourceProvider.GMAIL, connectedProvider)
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
                    targetPath = string(R.string.onb_recording_folder_target_common),
                    sourceSpecific = false,
                    voiceFolderDetected = true,
                    callFolderDetected = false,
                    meetingFolderDetected = false,
                    onGrant = { grantClicks += 1 },
                    onSkip = { skipClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.onb_recording_folder_detected_path_fmt, "/Recordings")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_recording_folder_picker_instruction)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.onb_recording_folder_picker_target)).assertIsDisplayed()
        composeRule.onNodeWithText(
            string(
                R.string.onb_recording_folder_voice_status_fmt,
                string(R.string.onb_recording_folder_voice_path),
                string(R.string.onb_recording_folder_status_detected),
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            string(
                R.string.onb_recording_folder_call_status_fmt,
                string(R.string.onb_recording_folder_call_path),
                string(R.string.onb_recording_folder_status_missing),
            ),
        ).assertExists()
        composeRule.onNodeWithText(
            string(
                R.string.onb_recording_folder_meeting_status_fmt,
                string(R.string.onb_recording_folder_meeting_path),
                string(R.string.onb_recording_folder_status_created_later),
            ),
        ).assertExists()
        composeRule.onNodeWithText(string(R.string.onb_recording_folder_use_selected)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.action_skip)).performScrollTo().performClick()

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
        composeRule.onNodeWithText(string(R.string.onb_pipa_email_cta_agree)).performScrollTo().performClick()
        composeRule.onNodeWithText(string(R.string.onb_pipa_email_cta_deny)).performScrollTo().performClick()

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
        composeRule.onNodeWithText(string(R.string.onb_imap_cta)).performScrollTo().assertIsEnabled()
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

    @Test
    fun `compact setup recording copy matches media permission and app-owned path contract`() {
        val title = string(R.string.onb_setup_recordings_title)
        val consent = string(R.string.onb_setup_recordings_consent)

        assertEquals("녹음 연결", title)
        assertFalse(consent.contains("선택할 폴더"))
        assertFalse(consent.contains("상위 폴더"))
        assertFalse(consent.contains("허용해 주세요"))
        assertTrue(consent.contains("음악 및 오디오 권한"))
        assertTrue(consent.contains("앱 안에서 선택한 녹음 경로"))
        assertTrue(consent.contains("일반 녹음"))
        assertTrue(consent.contains("통화 녹음"))
        assertTrue(consent.contains("회의 녹음"))
        assertTrue(consent.contains("NAVER Cloud CLOVA Speech"))
        assertTrue(consent.contains("Google Vertex AI"))
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
