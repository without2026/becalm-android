package com.becalm.android.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingCheckpoint1E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_009_compact_onboarding_setup_can_complete_and_enter_main_screen() {
        var completeCount = 0
        var destination: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SourceConnectionsScreen(
                    navController = rememberNavController(),
                    entryPoint = SourceConnectionsEntryPoint.Setup,
                    emailEventsOverride = emptyFlow(),
                    calendarEventsOverride = emptyFlow(),
                    stateOverride = OnboardingUiState(
                        stepStates = OnboardingStep.entries.associateWith { StepStatus.SKIPPED } +
                            (OnboardingStep.LOGIN to StepStatus.GRANTED),
                    ),
                    onConnectSource = { _, _ -> },
                    onSkipSource = {},
                    onPersistEmailConsent = { true },
                    onRefreshSource = {},
                    onCompleteSetup = { completeCount += 1 },
                    onNavigateComplete = { destination = BecalmRoute.Today.path },
                    onLaunchPendingIntent = {},
                    setupItems = listOf(
                        setupItem(
                            item = OnboardingSetupItem.RecordingFolder,
                            title = string(R.string.onb_setup_recordings_title),
                        ),
                        setupItem(
                            item = OnboardingSetupItem.Contacts,
                            title = string(R.string.onb_setup_contacts_title),
                        ),
                        setupItem(
                            item = OnboardingSetupItem.Notifications,
                            title = string(R.string.onb_setup_notifications_title),
                        ),
                    ),
                    onConnectSetupItem = {},
                    onSkipSetupItem = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_setup_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_setup_required_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_setup_recommended_section)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-connections-list")
            .performScrollToNode(hasText(string(R.string.onb_setup_start)))
        composeTestRule.onNodeWithText(string(R.string.onb_setup_start)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, completeCount)
            assertEquals(BecalmRoute.Today.path, destination)
        }
    }

    @Test
    fun e2e_010_contacts_permission_grant_path_is_available() {
        var grantCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ContactsPermissionContent(
                    onGrant = { grantCount += 1 },
                    onSkip = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_contacts_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_contacts_pipa)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantCount)
        }
    }

    @Test
    fun e2e_011_contacts_permission_denial_continues_with_recoverable_skip() {
        var skipCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ContactsPermissionContent(
                    onGrant = {},
                    onSkip = { skipCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_contacts_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, skipCount)
        }
    }

    @Test
    fun e2e_012_notification_permission_grant_path_is_available() {
        var grantCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                NotificationPermissionContent(
                    onGrant = { grantCount += 1 },
                    onSkip = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_notifications_headline)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_notifications_rationale)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, grantCount)
        }
    }

    @Test
    fun e2e_013_notification_permission_denial_keeps_onboarding_recoverable() {
        var skipCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                NotificationPermissionContent(
                    onGrant = {},
                    onSkip = { skipCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_notifications_body)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_skip)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, skipCount)
        }
    }

    @Test
    fun e2e_014_user_can_skip_all_optional_sources_and_enter_app() {
        val skipped = mutableListOf<OnboardingSourceProvider>()
        var continued = 0

        composeTestRule.setContent {
            BecalmTheme {
                SourceConnectionsContent(
                    items = listOf(
                        sourceItem(
                            provider = OnboardingSourceProvider.GMAIL,
                            category = SourceConnectionCategory.Mail,
                            title = "Gmail",
                        ),
                        sourceItem(
                            provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                            category = SourceConnectionCategory.Calendar,
                            title = "Google Calendar",
                        ),
                    ),
                    continueLabel = string(R.string.onb_sources_skip_remaining),
                    onConnect = {},
                    onSkip = { skipped += it },
                    onContinue = { continued += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.onb_sources_mail_section)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.action_skip)).onFirst().performClick()
        composeTestRule.onNodeWithText(string(R.string.onb_sources_skip_remaining)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf(OnboardingSourceProvider.GMAIL), skipped)
            assertEquals(1, continued)
        }
    }

    private fun sourceItem(
        provider: OnboardingSourceProvider,
        category: SourceConnectionCategory,
        title: String,
    ): SourceConnectionItemUi =
        SourceConnectionItemUi(
            provider = provider,
            category = category,
            title = title,
            description = "$title source",
            consentCopy = null,
            state = SourceConnectionState.Idle,
        )

    private fun setupItem(
        item: OnboardingSetupItem,
        title: String,
    ): OnboardingSetupItemUi =
        OnboardingSetupItemUi(
            item = item,
            title = title,
            description = "$title setup",
            state = SourceConnectionState.Skipped,
        )

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
