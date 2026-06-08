package com.becalm.android.ui.today

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.ui.components.OverallSyncIndicator
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceStatusStrip
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.actions.PersonActionEvidenceUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.PersonActionProviderWriteUi
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayTimelineScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    // spec: ERR-002
    // spec: ERR-003
    fun today_content_keeps_processing_banner_and_settings_action_without_source_management_header() {
        var openSettingsCount = 0
        var openProcessingCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        processingPaused = true,
                        overall = OverallSyncState.Syncing(count = 1, total = 7),
                        overallSyncing = true,
                        processingStatus = TodayProcessingStatusUi(
                            activeCount = 1,
                            activeItemCount = 3,
                            latestPhase = ProcessingPhase.GEMINI,
                            latestUpdatedAt = Instant.parse("2026-04-24T01:03:00Z"),
                        ),
                        sourceStatus = mapOf(
                            "voice" to SourceStatusUi(
                                status = SourceSyncStatus.Syncing,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                            "gmail" to SourceStatusUi(
                                status = SourceSyncStatus.Connected,
                                errorMessage = null,
                                lastSyncedAt = Instant.parse("2026-04-24T01:00:00Z"),
                            ),
                            "outlook_mail" to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "token expired",
                                lastSyncedAt = null,
                            ),
                            "naver_imap" to SourceStatusUi(
                                status = SourceSyncStatus.Disconnected,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                        ),
                    ),
                    onOpenSettings = { openSettingsCount += 1 },
                    onOpenProcessingStatus = { openProcessingCount += 1 },
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_processing_active_items_fmt, 3)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_processing_open)).performClick()
        composeTestRule.onAllNodesWithText(string(R.string.today_syncing_fmt, 1, 7)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.today_source_attention_mixed_fmt, 1, 1)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.today_source_attention_action)).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("source-chip-voice").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("source-chip-gmail").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Outlook Mail").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Naver Email").assertCountEquals(0)
        composeTestRule.onNodeWithContentDescription(string(R.string.label_settings)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, openSettingsCount)
            assertEquals(1, openProcessingCount)
        }
    }

    @Test
    fun today_content_shows_error_state() {
        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        error = UiMessage.resource(R.string.today_error_load_failed),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_error_load_failed)).assertIsDisplayed()
    }

    @Test
    fun today_auth_failure_opens_recovery_without_retry_loop() {
        var recoveryClicks = 0
        var retryClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        error = UiMessage.resource(R.string.today_error_sign_in_required),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = { retryClicks += 1 },
                    onRecoverAuth = { recoveryClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.auth_recovery_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_error_sign_in_required)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.error_state_retry)).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.auth_recovery_login_cta)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, recoveryClicks)
            assertEquals(0, retryClicks)
        }
    }

    @Test
    fun today_content_shows_empty_state() {
        var screenshotImports = 0

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        timeline = emptyList(),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onMessageScreenshotImport = { screenshotImports += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_empty_message)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, screenshotImports)
        }
    }

    @Test
    fun schedule_content_groups_next_7_days_sections_and_exposes_range_dropdown() {
        var selectedFilter: ScheduleRangeFilter? = null

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = LocalDate(2026, 4, 23),
                        scheduleRangeFilter = ScheduleRangeFilter.NEXT_7_DAYS,
                        timeline = listOf(
                            TimelineItem.Meeting(
                                id = "today",
                                sourceType = "meeting",
                                sourceRef = "meeting-today",
                                title = "오늘 미팅",
                                attendeesRaw = "team@example.com",
                                sortKey = Instant.parse("2026-04-23T02:00:00Z"),
                            ),
                            TimelineItem.CalendarEvent(
                                id = "week",
                                sourceType = "google_calendar",
                                sourceRef = "calendar-week",
                                title = "금요일 리뷰",
                                sortKey = Instant.parse("2026-04-24T02:00:00Z"),
                            ),
                            TimelineItem.CalendarEvent(
                                id = "past",
                                sourceType = "google_calendar",
                                sourceRef = "calendar-past",
                                title = "지난 미팅",
                                sortKey = Instant.parse("2026-04-01T02:00:00Z"),
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onScheduleRangeChange = { selectedFilter = it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.schedule_section_today)).assertIsDisplayed()
        composeTestRule.onNodeWithText("오늘 미팅").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.schedule_section_this_week)).assertIsDisplayed()
        composeTestRule.onNodeWithText("금요일 리뷰").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("지난 미팅").assertCountEquals(0)

        composeTestRule.onNodeWithTag("schedule-range-selector").performClick()
        composeTestRule.onNodeWithText(string(R.string.schedule_range_all)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(ScheduleRangeFilter.ALL, selectedFilter)
        }
    }

    @Test
    fun overall_sync_indicator_shows_synced_label() {
        composeTestRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(
                    state = OverallSyncState.Synced(
                        Instant.parse("2026-04-24T01:30:00Z"),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_synced_time_fmt, ""), substring = true)
            .assertIsDisplayed()
    }

    @Test
    // spec: ERR-007
    fun overall_sync_indicator_shows_partial_failure_label() {
        composeTestRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(state = OverallSyncState.PartialFailure)
            }
        }

        composeTestRule.onNodeWithText(string(R.string.today_partial_failure)).assertIsDisplayed()
    }

    @Test
    fun source_status_strip_renders_provided_active_sources_only() {
        composeTestRule.setContent {
            BecalmTheme {
                SourceStatusStrip(
                    sources = listOf(
                        SourceStatusChip("voice", SourceSyncStatus.Syncing),
                        SourceStatusChip("gmail", SourceSyncStatus.Connected),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithTag("source-chip-voice").assertIsDisplayed()
        composeTestRule.onNodeWithTag("source-chip-gmail").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Outlook Mail").assertCountEquals(0)
    }

    @Test
    fun schedule_action_evidence_button_forwards_backend_lookup_ids() {
        var openedActionId: String? = null
        var openedEvidenceKind: String? = null
        var openedEvidenceId: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = LocalDate(2026, 4, 23),
                        scheduleActions = listOf(scheduleAction()),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onOpenScheduleActionEvidence = { actionId, evidenceKind, evidenceId ->
                        openedActionId = actionId
                        openedEvidenceKind = evidenceKind
                        openedEvidenceId = evidenceId
                    },
                )
            }
        }

        composeTestRule.onAllNodesWithText(string(R.string.commitment_action_evidence))
            .onFirst()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals("schedule-pa-1", openedActionId)
            assertEquals("schedule_link", openedEvidenceKind)
            assertEquals("schedule-link-1", openedEvidenceId)
        }
    }

    @Test
    fun schedule_action_dismiss_button_forwards_backend_mutation_id() {
        var dismissedActionId: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = LocalDate(2026, 4, 23),
                        scheduleActions = listOf(scheduleAction()),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onDismissScheduleAction = { dismissedActionId = it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.schedule_action_dismiss)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("schedule-pa-1", dismissedActionId)
        }
    }

    @Test
    fun schedule_action_primary_button_opens_candidate_detail_without_claiming_calendar_write() {
        var openedCommitmentId: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = LocalDate(2026, 4, 23),
                        scheduleActions = listOf(scheduleAction()),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onOpenCommitmentDetail = { openedCommitmentId = it },
                )
            }
        }

        composeTestRule.onAllNodesWithText(string(R.string.schedule_action_add_to_calendar)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("일정 추가").assertCountEquals(0)
        composeTestRule.onNodeWithText("후보 확인").performClick()

        composeTestRule.runOnIdle {
            assertEquals("commitment-1", openedCommitmentId)
        }
    }

    @Test
    fun schedule_action_provider_write_ready_shows_add_to_calendar_button() {
        var completedActionId: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = LocalDate(2026, 4, 23),
                        scheduleActions = listOf(scheduleAction(providerWriteReady = true)),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onCompleteScheduleAction = { completedActionId = it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.schedule_action_add_to_calendar)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("schedule-pa-1", completedActionId)
        }
    }

    private fun scheduleAction(providerWriteReady: Boolean = false): PersonActionItemUi = PersonActionItemUi(
        id = "schedule-pa-1",
        personId = "person-1",
        personDisplayName = "김철수",
        actionKind = "add_to_calendar",
        title = "김철수 미팅 일정 잡기",
        primaryVerb = "후보 확인",
        shortReason = "메일에는 있는데 캘린더에 없습니다.",
        commitmentId = "commitment-1",
        calendarEventId = null,
        sourceEventId = "source-event-1",
        sourceType = "gmail",
        sourceRef = "mail-1",
        dueAt = null,
        dueHint = "내일 오후",
        urgencyScore = 0.91,
        confidence = 0.88,
        reasonCodes = listOf("schedule:missing"),
        evidence = PersonActionEvidenceUi(
            kind = "schedule_link",
            id = "schedule-link-1",
            sourceRef = "mail-1",
            occurredAt = null,
            label = "메일 일정 후보",
            quote = "내일 오후에 뵙겠습니다.",
        ),
        providerWrite = if (providerWriteReady) {
            PersonActionProviderWriteUi(
                kind = "add_to_calendar",
                state = "ready",
                provider = "google_calendar",
                sourceConnectionId = "conn-calendar-write",
                scheduleEventLinkId = "schedule-link-1",
            )
        } else {
            null
        },
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
