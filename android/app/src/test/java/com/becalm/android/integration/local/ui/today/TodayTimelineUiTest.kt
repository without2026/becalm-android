package com.becalm.android.integration.local.ui.today

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.actions.PersonActionFeedStatusKind
import com.becalm.android.ui.actions.PersonActionFeedStatusUi
import com.becalm.android.ui.components.OverallSyncIndicator
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceStatusStrip
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.CalendarWriteJobStatusKind
import com.becalm.android.ui.today.CalendarWriteJobUi
import com.becalm.android.ui.today.ScheduleConflictReviewItem
import com.becalm.android.ui.today.TodayTimelineContent
import com.becalm.android.ui.today.TodayProcessingStatusUi
import com.becalm.android.ui.today.TodayUiState
import com.becalm.android.ui.today.TimelineItem
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.ui.actions.PersonActionEvidenceUi
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TodayTimelineUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `today content keeps processing banner and settings action without source management header`() {
        var openSettingsCount = 0

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        processingPaused = true,
                        overall = OverallSyncState.Syncing(count = 1, total = 7),
                        overallSyncing = true,
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
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_syncing_fmt, 1, 7)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.today_source_attention_mixed_fmt, 1, 1)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.today_source_attention_action)).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(string(R.string.label_settings)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, openSettingsCount)
        }
    }

    @Test
    fun `today does not surface disconnected source attention on the schedule surface`() {
        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Disconnected,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.today_source_attention_disconnected_fmt, 1)).assertDoesNotExist()
        composeRule.onAllNodesWithText("자동 정리에 추가할 출처 1개가 남아 있습니다").assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.today_source_attention_action)).assertDoesNotExist()
    }

    @Test
    fun `today shows schedule action feed quota delay as persistent status`() {
        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        scheduleActionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.QUOTA_DELAY,
                            backlogLagSeconds = 240,
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onOpenProcessingStatus = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-action-feed-statusline").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_quota), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `P1-GAP-004 today keeps cached schedule actions visible with degraded feed status`() {
        var processingStatusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        scheduleActionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.QUOTA_DELAY,
                            backlogLagSeconds = 240,
                        ),
                        scheduleActions = listOf(
                            scheduleAction(
                                id = "pa-cached",
                                title = "캘린더에 없는 미팅 후보",
                                reason = "Gmail에서 일정 후보가 보였지만 캘린더에는 아직 없습니다.",
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onOpenProcessingStatus = { processingStatusClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("schedule-action-feed-statusline").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_quota), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-diffbar").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.schedule_diff_missing_count_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-action-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-action-pa-cached").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.schedule_action_missing_section)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.schedule_action_missing_tag)).assertIsDisplayed()
        composeRule.onNodeWithText("캘린더에 없는 미팅 후보").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-action-feed-statusline-action").performClick()

        composeRule.runOnIdle {
            assertEquals(1, processingStatusClicks)
        }
    }

    @Test
    fun `schedule action panel renders every missing calendar action`() {
        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        scheduleActions = (1..4).map { index ->
                            scheduleAction(
                                id = "pa-missing-$index",
                                title = "캘린더에 빠진 일정 $index",
                                reason = "메일에는 약속이 있지만 캘린더에는 없습니다.",
                            )
                        },
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-action-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-timeline-list")
            .performScrollToNode(hasText("캘린더에 빠진 일정 4"))
        composeRule.onNodeWithText("캘린더에 빠진 일정 4").assertExists()
    }

    @Test
    fun `P1-GAP-005 schedule candidates keep confirmed calendar context visible`() {
        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = kotlinx.datetime.LocalDate(2026, 6, 4),
                        scheduleActions = listOf(
                            scheduleAction(
                                id = "pa-missing-calendar",
                                title = "김도현 대표 계약서 회신 마감",
                                reason = "메일에는 약속이 있지만 캘린더에는 확정 일정이 없습니다.",
                            ),
                        ),
                        timeline = listOf(
                            TimelineItem.Meeting(
                                id = "calendar-investor-meeting",
                                sourceType = SourceType.GOOGLE_CALENDAR,
                                sourceRef = "cal-investor-meeting",
                                title = "투자자 미팅",
                                attendeesRaw = "최신 IR 자료 확인",
                                status = "confirmed",
                                sortKey = Instant.parse("2026-06-04T06:00:00Z"),
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-action-panel").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-diffbar").assertIsDisplayed()
        composeRule.onNodeWithText("김도현 대표 계약서 회신 마감").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.schedule_action_open_detail)).assertCountEquals(0)
        composeRule.onNodeWithTag("schedule-timeline-list").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-timeline-list").performScrollToNode(hasText("투자자 미팅"))
        composeRule.onNodeWithText("투자자 미팅").assertExists()
    }

    @Test
    fun `schedule row exposes reminder icon toggle for eligible confirmed commitments`() {
        var toggleRequest: Pair<String, Boolean>? = null

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = kotlinx.datetime.LocalDate(2026, 6, 9),
                        timeline = listOf(
                            TimelineItem.Commitment(
                                id = "schedule-reminder-1",
                                itemType = CommitmentItemType.SCHEDULE,
                                title = "오후 미팅",
                                direction = null,
                                scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                                rowTreatment = com.becalm.android.ui.today.TodayCommitmentRowTreatment.SCHEDULE,
                                counterpartyDisplayName = null,
                                sourceType = SourceType.GMAIL,
                                sourceTitle = "미팅 조율",
                                quote = null,
                                dueAt = Instant.parse("2026-06-09T06:00:00Z"),
                                dueIsApproximate = false,
                                dueHint = null,
                                sortKey = Instant.parse("2026-06-09T06:00:00Z"),
                                timelineAt = Instant.parse("2026-06-09T06:00:00Z"),
                                isTimed = true,
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onToggleScheduleReminder = { id, enabled ->
                        toggleRequest = id to enabled
                    },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription(string(R.string.schedule_row_reminder_on_action))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("schedule-reminder-1" to false, toggleRequest)
        }
    }

    @Test
    fun `date only schedule row opens reminder time sheet and saves selected notification time`() {
        var reminderRequest: Pair<String, Instant>? = null

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        today = kotlinx.datetime.LocalDate(2026, 6, 9),
                        timeline = listOf(
                            TimelineItem.Commitment(
                                id = "schedule-date-only-1",
                                itemType = CommitmentItemType.SCHEDULE,
                                title = "금요일 미팅",
                                direction = null,
                                scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                                rowTreatment = com.becalm.android.ui.today.TodayCommitmentRowTreatment.SCHEDULE,
                                counterpartyDisplayName = null,
                                sourceType = SourceType.GMAIL,
                                sourceTitle = "미팅 조율",
                                quote = null,
                                dueAt = Instant.parse("2026-06-11T15:00:00Z"),
                                dueIsApproximate = true,
                                dueHint = "6/12",
                                sortKey = Instant.parse("2026-06-11T15:00:00Z"),
                                timelineAt = null,
                                isTimed = false,
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onSetScheduleReminderAt = { id, triggerAt ->
                        reminderRequest = id to triggerAt
                    },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule
            .onNodeWithContentDescription(string(R.string.schedule_row_reminder_time_action))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("schedule-reminder-time-sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-reminder-time-chip-09-00").performClick()
        composeRule.onNodeWithTag("schedule-reminder-time-save").performClick()

        composeRule.runOnIdle {
            assertEquals("schedule-date-only-1" to Instant.parse("2026-06-12T00:00:00Z"), reminderRequest)
        }
    }

    @Test
    fun `today shows calendar write job status with reconnect and retry actions`() {
        var sourceManagementClicks = 0
        var openedSource: String? = null
        val retriedJobs = mutableListOf<String>()

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        calendarWriteJobs = listOf(
                            CalendarWriteJobUi(
                                jobId = "job-reauth",
                                actionItemId = "pa-schedule-1",
                                title = "Jane Kim calendar candidate",
                                provider = SourceType.GOOGLE_CALENDAR,
                                scheduleEventLinkId = "schedule-link-1",
                                status = CalendarWriteJobStatusKind.NEEDS_REAUTH,
                                clientAction = "connect_calendar",
                            ),
                            CalendarWriteJobUi(
                                jobId = "job-failed",
                                actionItemId = "pa-schedule-2",
                                title = "Investor meeting",
                                provider = "google_calendar",
                                scheduleEventLinkId = "schedule-link-2",
                                status = CalendarWriteJobStatusKind.CHECK_FAILED,
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onOpenSources = { sourceManagementClicks += 1 },
                    onOpenSource = { openedSource = it },
                    onPullRefresh = {},
                    onRetryCalendarWriteJob = { retriedJobs += it },
                )
            }
        }

        composeRule.onNodeWithTag("schedule-calendar-write-status-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Jane Kim calendar candidate").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.schedule_calendar_write_status_needs_reauth)).assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-calendar-write-reconnect-job-reauth")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("schedule-calendar-write-retry-job-failed")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(0, sourceManagementClicks)
            assertEquals(SourceType.GOOGLE_CALENDAR, openedSource)
            assertEquals(listOf("job-failed"), retriedJobs)
        }
    }

    @Test
    fun `today calendar write reconnect falls back to source list without provider route`() {
        var sourceManagementClicks = 0

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        calendarWriteJobs = listOf(
                            CalendarWriteJobUi(
                                jobId = "job-reauth",
                                actionItemId = "pa-schedule-1",
                                title = "Calendar candidate",
                                provider = null,
                                scheduleEventLinkId = "schedule-link-1",
                                status = CalendarWriteJobStatusKind.NEEDS_REAUTH,
                                clientAction = "connect_calendar",
                            ),
                        ),
                    ),
                    onOpenSettings = {},
                    onOpenSources = { sourceManagementClicks += 1 },
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithTag("schedule-calendar-write-reconnect-job-reauth")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, sourceManagementClicks)
        }
    }

    @Test
    fun `today content shows error state`() {
        var retryClicks = 0

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        error = UiMessage.resource(R.string.today_error_load_failed),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = { retryClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.today_error_load_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.error_state_retry)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, retryClicks)
        }
    }

    @Test
    fun `P1-GAP-004 today auth failure opens recovery instead of blind retry`() {
        var recoverAuthClicks = 0
        var retryClicks = 0

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        error = UiMessage.resource(R.string.today_error_sign_in_required),
                    ),
                    onOpenSettings = {},
                    onPullRefresh = { retryClicks += 1 },
                    onRecoverAuth = { recoverAuthClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.auth_recovery_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_error_sign_in_required)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.error_state_retry)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.auth_recovery_login_cta)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, recoverAuthClicks)
            assertEquals(0, retryClicks)
        }
    }

    @Test
    fun `today content shows empty state`() {
        var screenshotImports = 0

        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.schedule_section_today)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.schedule_section_empty)).onFirst().assertIsDisplayed()
        composeRule.onNodeWithTag("schedule-timeline-list")
            .performScrollToNode(hasText(string(R.string.schedule_section_this_week)))
        composeRule.onAllNodesWithText(string(R.string.schedule_section_this_week)).onFirst().assertIsDisplayed()
        composeRule.onNodeWithTag("evidence-import-fab").performClick()
        composeRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, screenshotImports)
        }
    }

    @Test
    fun `today processing strip shows active and action-needed counts together`() {
        var processingClicks = 0

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        processingStatus = TodayProcessingStatusUi(
                            activeCount = 1,
                            actionCount = 1,
                            latestPhase = ProcessingPhase.ERROR,
                            latestUpdatedAt = Instant.parse("2026-05-25T08:00:00Z"),
                        ),
                    ),
                    onOpenSettings = {},
                    onOpenProcessingStatus = { processingClicks += 1 },
                    onPullRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("1개 연결 확인 중, 1개 확인 필요").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_processing_open)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, processingClicks)
        }
    }

    @Test
    fun `schedule conflict review presents first conflict as decision queue with primary action`() {
        val resolutions = mutableListOf<Pair<String, String>>()
        val conflicts = listOf(
            scheduleConflict(
                linkId = "link-1",
                calendarTitle = "캘린더 인터뷰",
                sourceTitle = "메일 인터뷰",
                evidence = "메일에서 같은 인터뷰 시간이 발견되었습니다.",
            ),
            scheduleConflict(
                linkId = "link-2",
                calendarTitle = "두 번째 캘린더",
                sourceTitle = "두 번째 메일",
                evidence = "두 번째 evidence",
            ),
        )

        composeRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        scheduleConflictReviewItems = conflicts,
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onResolveScheduleConflict = { linkId, choice ->
                        resolutions += linkId to choice
                    },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.today_schedule_conflict_queue_progress_fmt, 1, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithText("캘린더 인터뷰").assertIsDisplayed()
        composeRule.onNodeWithText("메일 인터뷰").assertIsDisplayed()
        composeRule.onAllNodesWithText("두 번째 캘린더").assertCountEquals(0)

        composeRule.onNodeWithTag("schedule-conflict-primary").performClick()
        composeRule.onNodeWithTag("schedule-conflict-keep-both").performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "link-1" to ScheduleEventLinkResolutionChoice.SAME_SCHEDULE,
                    "link-1" to ScheduleEventLinkResolutionChoice.KEEP_BOTH,
                ),
                resolutions,
            )
        }
    }

    @Test
    fun `overall sync indicator shows synced label`() {
        composeRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(
                    state = OverallSyncState.Synced(
                        Instant.parse("2026-04-24T01:30:00Z"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.today_synced_time_fmt, ""), substring = true).assertExists()
    }

    @Test
    fun `overall sync indicator shows partial failure label`() {
        composeRule.setContent {
            BecalmTheme {
                OverallSyncIndicator(state = OverallSyncState.PartialFailure)
            }
        }

        composeRule.onNodeWithText(string(R.string.today_partial_failure)).assertExists()
    }

    @Test
    fun `source status strip renders provided active sources only`() {
        composeRule.setContent {
            BecalmTheme {
                SourceStatusStrip(
                    sources = listOf(
                        SourceStatusChip("voice", SourceSyncStatus.Syncing),
                        SourceStatusChip("gmail", SourceSyncStatus.Connected),
                    ),
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.raw_event_source_badge_voice)).assertExists()
        composeRule.onNodeWithText("Gmail").assertExists()
        composeRule.onAllNodesWithText(string(R.string.raw_event_source_badge_outlook_mail)).assertCountEquals(0)
    }

    @Test
    fun `source status strip exposes per-source status semantics and optional detail navigation`() {
        var openedSourceType: String? = null

        composeRule.setContent {
            BecalmTheme {
                SourceStatusStrip(
                    sources = listOf(
                        SourceStatusChip(
                            sourceType = SourceType.GMAIL,
                            status = SourceSyncStatus.Connected,
                            lastSyncedAt = Instant.fromEpochMilliseconds(1_713_430_200_000L),
                        ),
                    ),
                    onSourceClick = { openedSourceType = it },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(
                "Gmail, ${string(R.string.sources_status_connected)}, " +
                    string(R.string.sources_status_last_success_at_fmt, ""),
                substring = true,
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.sources_status_connected),
                ),
            )
        composeRule
            .onNodeWithContentDescription(
                string(R.string.sources_status_open_source_detail_a11y),
                substring = true,
            )
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(SourceType.GMAIL, openedSourceType)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)

    private fun scheduleConflict(
        linkId: String,
        calendarTitle: String,
        sourceTitle: String,
        evidence: String,
    ): ScheduleConflictReviewItem = ScheduleConflictReviewItem(
        linkId = linkId,
        calendarTitle = calendarTitle,
        calendarStartAt = Instant.parse("2026-05-25T01:00:00Z"),
        calendarStatus = "confirmed",
        sourceTitle = sourceTitle,
        sourceStartAt = Instant.parse("2026-05-25T01:15:00Z"),
        sourceStatus = "needs_review",
        sourceType = SourceType.GMAIL,
        evidence = evidence,
    )

    private fun scheduleAction(
        id: String,
        title: String,
        reason: String,
        primaryVerb: String = string(R.string.schedule_action_add_to_calendar),
    ): com.becalm.android.ui.actions.PersonActionItemUi =
        com.becalm.android.ui.actions.PersonActionItemUi(
            id = id,
            personId = "person-schedule",
            personDisplayName = "김민홍",
            actionKind = "add_to_calendar",
            title = title,
            primaryVerb = primaryVerb,
            shortReason = reason,
            commitmentId = "commitment-$id",
            calendarEventId = null,
            sourceEventId = "source-$id",
            sourceType = SourceType.GMAIL,
            sourceRef = "gmail-thread-$id",
            dueAt = Instant.parse("2026-06-04T01:00:00Z"),
            dueHint = "오늘",
            urgencyScore = 0.86,
            confidence = 0.81,
            reasonCodes = listOf("surface:schedule", "calendar:missing"),
            evidence = PersonActionEvidenceUi(
                kind = "source_event",
                id = "source-$id",
                sourceRef = "gmail-thread-$id",
                occurredAt = Instant.parse("2026-06-03T10:00:00Z"),
                label = "Gmail",
                quote = "목요일 오후에 미팅 가능할까요?",
            ),
        )
}
