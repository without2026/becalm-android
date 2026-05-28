@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.ui.commitments

import android.content.Context
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommitmentManagementScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun commitment_management_shows_empty_state() {
        composeTestRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = emptyList(),
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.commitments_empty_title)).assertIsDisplayed()
    }

    @Test
    fun commitment_management_shows_filters_sections_fab_and_detail_tap() {
        var selectedFilter: CommitmentFilter? = null
        var openedDetailId: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val active = activeRow("active-1", "Active commitment")
                val schedule = scheduleRow("schedule-1", "Schedule change")
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(active, schedule),
                        activeItems = listOf(active, schedule),
                        confirmedSection = CommitmentSectionUiState(
                            expanded = true,
                            count = 2,
                            items = listOf(
                                active,
                                schedule,
                            ),
                        ),
                        completedSection = CommitmentSectionUiState(
                            expanded = false,
                            count = 1,
                            items = listOf(activeRow("completed-1", "Completed commitment")),
                        ),
                        cancelledSection = CommitmentSectionUiState(
                            expanded = false,
                            count = 1,
                            items = listOf(activeRow("cancelled-1", "Cancelled commitment")),
                        ),
                        filter = CommitmentFilter.ALL,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = { selectedFilter = it },
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = { openedDetailId = it },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("commitment-filter-all").assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-filter-give").assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-filter-take").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("commitment-filter-schedule").assertCountEquals(0)
        composeTestRule.onNodeWithTag("commitment-filter-closed").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Alice Kim").assertCountEquals(2)
        composeTestRule.onNodeWithText("Schedule change").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active commitment").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-give").performClick()

        composeTestRule.runOnIdle {
            assertEquals("active-1", openedDetailId)
            assertEquals(CommitmentFilter.GIVE, selectedFilter)
        }
    }

    @Test
    fun commitment_management_schedule_filter_shows_timeline_rows_and_detail_tap() {
        var openedDetailId: String? = null
        var pastExpanded by mutableStateOf(false)

        composeTestRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val currentSchedule = scheduleRow(
                    id = "schedule-1",
                    title = "Schedule change",
                    dueAt = Instant.parse("2026-05-04T01:30:00Z"),
                    counterpartyDisplayName = "Carol Park",
                    scheduleTimelineTiming = ScheduleTimelineTiming(
                        dayLabel = "D-0",
                        timeLabel = "10:30",
                        isUntimed = false,
                    ),
                )
                val untimedSchedule = scheduleRow(
                    id = "schedule-untimed",
                    title = "Pick a time",
                    dueAt = null,
                    counterpartyDisplayName = null,
                    scheduleTimelineTiming = ScheduleTimelineTiming(
                        dayLabel = null,
                        timeLabel = null,
                        isUntimed = true,
                    ),
                )
                val pastSchedule = scheduleRow(
                    id = "schedule-past",
                    title = "Past meeting",
                    dueAt = Instant.parse("2026-05-03T01:30:00Z"),
                    counterpartyDisplayName = "Dana Lee",
                    scheduleTimelineTiming = ScheduleTimelineTiming(
                        dayLabel = "D+1",
                        timeLabel = "10:30",
                        isUntimed = false,
                    ),
                )
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(pastSchedule, currentSchedule, untimedSchedule),
                        scheduleUpcomingItems = listOf(currentSchedule, untimedSchedule),
                        schedulePastSection = CommitmentSectionUiState(
                            count = 1,
                            items = listOf(pastSchedule),
                            expanded = pastExpanded,
                            dimmed = true,
                        ),
                        filter = CommitmentFilter.SCHEDULE,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = { openedDetailId = it },
                    onTogglePastSection = { pastExpanded = !pastExpanded },
                )
            }
        }

        composeTestRule.onNodeWithTag("commitment-schedule-timeline").assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-schedule-card-schedule-1").assertIsDisplayed()
        composeTestRule.onNodeWithText("D-0").assertIsDisplayed()
        composeTestRule.onNodeWithText("10:30").assertIsDisplayed()
        composeTestRule.onNodeWithText("Carol Park").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pick a time").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Past meeting").assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.commitment_section_past_fmt, 1)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.today_untimed_section)).assertCountEquals(2)

        composeTestRule.onNodeWithTag("commitment-schedule-past-header").performClick()
        composeTestRule.onNodeWithText("Past meeting").assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-schedule-card-schedule-1").performClick()
        composeTestRule.runOnIdle {
            assertEquals("schedule-1", openedDetailId)
        }
    }

    @Test
    // spec: MSG-001
    // spec: MAN-001
    // spec: MAN-003
    fun commitment_management_fab_opens_evidence_import_sheet() {
        var screenshotImports = 0
        var meetingAudioImports = 0

        composeTestRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = emptyList(),
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = { screenshotImports += 1 },
                    onMeetingAudioImport = { meetingAudioImports += 1 },
                    onOpenDetail = {},
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.evidence_import_message_screenshot)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, screenshotImports)
            assertEquals(0, meetingAudioImports)
        }
    }

    private fun activeRow(id: String, title: String): CommitmentRow = CommitmentRow(
        id = id,
        itemType = "action",
        title = title,
        direction = "give",
        scheduleStatus = null,
        decisionStatus = null,
        derivedStatus = "PENDING",
        actionState = com.becalm.android.domain.commitment.CommitmentState.PENDING,
        dueAt = Instant.parse("2026-04-24T01:00:00Z"),
        dueIsApproximate = false,
        dueHint = null,
        counterpartyDisplayName = "Alice Kim",
        isManual = false,
    )

    private fun scheduleRow(
        id: String,
        title: String,
        dueAt: Instant? = Instant.parse("2026-04-24T01:00:00Z"),
        counterpartyDisplayName: String? = "Carol Park",
        scheduleTimelineTiming: ScheduleTimelineTiming? = null,
    ): CommitmentRow = CommitmentRow(
        id = id,
        itemType = "schedule",
        title = title,
        direction = null,
        scheduleStatus = "changed",
        decisionStatus = null,
        derivedStatus = null,
        actionState = com.becalm.android.domain.commitment.CommitmentState.PENDING,
        dueAt = dueAt,
        dueIsApproximate = false,
        dueHint = null,
        counterpartyDisplayName = counterpartyDisplayName,
        isManual = false,
        scheduleTimelineTiming = scheduleTimelineTiming,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
