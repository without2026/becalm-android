package com.becalm.android.ui.journey

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.auth.LoginForm
import com.becalm.android.ui.auth.TermsContent
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.navigation.BecalmNavArgs
import com.becalm.android.ui.navigation.BecalmNavHost
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.ColdSyncContent
import com.becalm.android.ui.onboarding.OnboardingSourceProvider
import com.becalm.android.ui.onboarding.SourceConnectionCategory
import com.becalm.android.ui.onboarding.SourceConnectionItemUi
import com.becalm.android.ui.onboarding.SourceConnectionState
import com.becalm.android.ui.onboarding.SourceConnectionsContent
import com.becalm.android.ui.persons.ArchivedOriginalUi
import com.becalm.android.ui.persons.EmailBodyUi
import com.becalm.android.ui.persons.PersonDetailCommitmentSummary
import com.becalm.android.ui.persons.PersonDetailScreenContent
import com.becalm.android.ui.persons.PersonDetailUiState
import com.becalm.android.ui.persons.PersonRow
import com.becalm.android.ui.persons.PersonsScreenContent
import com.becalm.android.ui.persons.PersonsUiState
import com.becalm.android.ui.persons.RawEventDetailContent
import com.becalm.android.ui.persons.RawEventDetailUiState
import com.becalm.android.ui.persons.SourceEventCardProjection
import com.becalm.android.ui.persons.buildPersonSections
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ColdSyncUiState
import com.becalm.android.ui.today.TimelineItem
import com.becalm.android.ui.today.TodayCommitmentRowTreatment
import com.becalm.android.ui.today.TodayTimelineContent
import com.becalm.android.ui.today.TodayUiState
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HappyPathFullJourneyE2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun first_run_terms_login_onboarding_cold_sync_reaches_today_with_synced_work() {
        var step by mutableStateOf(FirstRunStep.Terms)
        var accepted by mutableStateOf(false)
        var gmailState by mutableStateOf(SourceConnectionState.ConsentRequired)
        var googleCalendarState by mutableStateOf(SourceConnectionState.Idle)
        val connectedSources = mutableListOf<OnboardingSourceProvider>()

        composeTestRule.setContent {
            BecalmTheme {
                when (step) {
                    FirstRunStep.Terms -> TermsContent(
                        accepted = accepted,
                        onAcceptedChange = { accepted = it },
                        onContinue = { step = FirstRunStep.Login },
                        onDecline = {},
                    )
                    FirstRunStep.Login -> LoginForm(
                        isLoading = false,
                        googleSignInEnabled = true,
                        onSignIn = { _, _ -> },
                        onSignUp = { _, _ -> },
                        onGoogleSignIn = { step = FirstRunStep.Sources },
                    )
                    FirstRunStep.Sources -> SourceConnectionsContent(
                        items = listOf(
                            sourceItem(
                                provider = OnboardingSourceProvider.GMAIL,
                                category = SourceConnectionCategory.Mail,
                                title = string(R.string.raw_event_source_badge_gmail),
                                state = gmailState,
                                consentCopy = string(R.string.onb_sources_mail_consent_body),
                            ),
                            sourceItem(
                                provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                                category = SourceConnectionCategory.Calendar,
                                title = string(R.string.raw_event_source_badge_google_calendar),
                                state = googleCalendarState,
                            ),
                        ),
                        continueLabel = string(R.string.onb_sources_continue),
                        onConnect = { provider ->
                            connectedSources += provider
                            when (provider) {
                                OnboardingSourceProvider.GMAIL -> gmailState = SourceConnectionState.Connected
                                OnboardingSourceProvider.GOOGLE_CALENDAR -> googleCalendarState = SourceConnectionState.Connected
                                else -> Unit
                            }
                        },
                        onSkip = {},
                        onContinue = { step = FirstRunStep.ColdSync },
                    )
                    FirstRunStep.ColdSync -> ColdSyncContent(
                        state = ColdSyncUiState(
                            overallProgress = 1f,
                            perSourceProgress = mapOf(
                                SourceType.GMAIL to 1f,
                                SourceType.GOOGLE_CALENDAR to 1f,
                            ),
                            done = true,
                        ),
                        onContinue = { step = FirstRunStep.Today },
                        onSkipForNow = {},
                    )
                    FirstRunStep.Today -> TodayTimelineContent(
                        state = TodayUiState(
                            loading = false,
                            timeline = listOf(todayCommitment()),
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(SourceSyncStatus.Connected, null, NOW),
                                SourceType.GOOGLE_CALENDAR to SourceStatusUi(SourceSyncStatus.Connected, null, NOW),
                            ),
                            overall = OverallSyncState.Synced(NOW),
                        ),
                        onOpenSettings = {},
                        onPullRefresh = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("terms-checkbox").performClick()
        composeTestRule.onNodeWithText(string(R.string.terms_cta)).performClick()
        composeTestRule.onNodeWithTag("google-sign-in-button").performClick()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_gmail)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_sources_connect_with_consent)).performClick()
        composeTestRule.onNodeWithText(string(R.string.raw_event_source_badge_google_calendar)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_connect)).performClick()
        composeTestRule.onNodeWithText(string(R.string.onb_sources_continue)).performClick()
        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_done)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.onb_cold_sync_cta)).performClick()

        composeTestRule.onNodeWithText("견적서 보내기").assertIsDisplayed()
        composeTestRule.onNodeWithText("Gmail").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(
                listOf(OnboardingSourceProvider.GMAIL, OnboardingSourceProvider.GOOGLE_CALENDAR),
                connectedSources,
            )
        }
    }

    @Test
    fun today_empty_state_blocks_manual_text_bypass_from_evidence_sheet() {
        val timeline = emptyList<TimelineItem>()

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        timeline = timeline,
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onAllNodesWithTag("evidence-import-manual-text").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("evidence-import-meeting-transcript").assertCountEquals(0)
    }

    @Test
    fun people_to_person_detail_to_raw_original_journey_uses_real_navigation_routes() {
        composeTestRule.setContent {
            BecalmTheme {
                val navController = rememberNavController()
                BecalmNavHost(
                    navController = navController,
                    startDestination = BecalmRoute.Persons.path,
                    routeOverrides = mapOf(
                        BecalmRoute.Persons.path to {
                            PersonsScreenContent(
                                state = PersonsUiState(
                                    people = listOf(personRow()),
                                    personSections = buildPersonSections(listOf(personRow())),
                                    loading = false,
                                ),
                                snackbarHostState = SnackbarHostState(),
                                onQueryChange = {},
                                onPersonClick = { personId ->
                                    navController.navigate(BecalmRoute.PersonDetail(personId).path)
                                },
                            )
                        },
                        BecalmRoute.PersonDetail.PATH to { entry ->
                            val personId = entry.arguments?.getString(BecalmNavArgs.PERSON_ID).orEmpty()
                            PersonDetailScreenContent(
                                state = PersonDetailUiState(
                                    personId = personId,
                                    displayName = "Jane Kim",
                                    eventCount = 1,
                                    sourceEventCards = listOf(
                                        SourceEventCardProjection(
                                            sourceEventKey = "gmail-1",
                                            sourceType = SourceType.GMAIL,
                                            rawEventId = "raw-gmail-1",
                                            occurredAt = NOW,
                                            title = "제안서 메일",
                                            snippet = "금요일까지 제안서 초안을 보내기로 함",
                                            myActions = listOf(
                                                PersonDetailCommitmentSummary(
                                                    title = "제안서 초안 보내기",
                                                    itemType = CommitmentItemType.ACTION,
                                                    direction = "give",
                                                ),
                                            ),
                                        ),
                                    ),
                                    loading = false,
                                ),
                                title = "Jane Kim",
                                snackbarHostState = SnackbarHostState(),
                                onBack = { navController.popBackStack() },
                                onEventTap = { rawEventId ->
                                    navController.navigate(BecalmRoute.RawEventDetail(personId, rawEventId).path)
                                },
                            )
                        },
                        BecalmRoute.RawEventDetail.PATH to {
                            RawEventDetailContent(
                                state = RawEventDetailUiState(
                                    eventId = "raw-gmail-1",
                                    sourceType = SourceType.GMAIL,
                                    eventTitle = "제안서 메일",
                                    timestamp = NOW,
                                    snippet = "원문 미리보기",
                                    emailBody = EmailBodyUi(
                                        bodyPlain = "금요일까지 제안서 초안을 보내겠습니다.",
                                        bodyHtml = null,
                                    ),
                                    archivedOriginal = ArchivedOriginalUi(
                                        bodyText = "금요일까지 제안서 초안을 보내겠습니다.",
                                        deletedFromDevice = false,
                                        truncated = false,
                                    ),
                                    commitmentsExtractedCount = 1,
                                    loading = false,
                                ),
                            )
                        },
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Jane Kim").performClick()
        composeTestRule.onNodeWithText("제안서 메일").assertIsDisplayed()
        composeTestRule.onNodeWithText("제안서 초안 보내기").assertIsDisplayed()
        composeTestRule.onNodeWithTag("person-detail-source-card-gmail-1").performClick()
        composeTestRule.onNodeWithText("금요일까지 제안서 초안을 보내겠습니다.").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 1)).assertIsDisplayed()
    }

    private fun sourceItem(
        provider: OnboardingSourceProvider,
        category: SourceConnectionCategory,
        title: String,
        state: SourceConnectionState,
        consentCopy: String? = null,
    ): SourceConnectionItemUi =
        SourceConnectionItemUi(
            provider = provider,
            category = category,
            title = title,
            description = "$title source",
            consentCopy = consentCopy,
            state = state,
        )

    private fun personRow(): PersonRow =
        PersonRow(
            personId = "person-jane",
            displayName = "Jane Kim",
            lastInteractionAt = NOW,
            interactionCount = 3,
            pendingCommitmentCount = 1,
        )

    private fun todayCommitment(
        id: String = "commitment-1",
        title: String = "견적서 보내기",
        counterparty: String = "Jane Kim",
    ): TimelineItem.Commitment =
        TimelineItem.Commitment(
            id = id,
            itemType = CommitmentItemType.ACTION,
            title = title,
            direction = "give",
            scheduleStatus = null,
            rowTreatment = TodayCommitmentRowTreatment.ACTION,
            counterpartyDisplayName = counterparty,
            dueAt = NOW,
            dueIsApproximate = false,
            dueHint = "오늘",
            sortKey = NOW,
            timelineAt = NOW,
            isTimed = true,
        )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    private enum class FirstRunStep {
        Terms,
        Login,
        Sources,
        ColdSync,
        Today,
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-05-08T01:00:00Z")
    }
}
