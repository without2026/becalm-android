@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.ui.commitments

import android.content.Context
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(
                            activeRow("active-1", "Active commitment"),
                            scheduleRow("schedule-1", "Schedule change"),
                        ),
                        activeItems = listOf(
                            activeRow("active-1", "Active commitment"),
                            scheduleRow("schedule-1", "Schedule change"),
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
        composeTestRule.onNodeWithTag("commitment-filter-schedule").assertIsDisplayed()
        composeTestRule.onNodeWithTag("commitment-filter-closed").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Alice Kim").assertCountEquals(2)
        composeTestRule.onNodeWithText("Schedule change").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active commitment").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-schedule").performClick()

        composeTestRule.runOnIdle {
            assertEquals("active-1", openedDetailId)
            assertEquals(CommitmentFilter.SCHEDULE, selectedFilter)
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
        composeTestRule.onNodeWithText("증거 추가").assertIsDisplayed()
        composeTestRule.onNodeWithText("메신저 스크린샷").performClick()

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

    private fun scheduleRow(id: String, title: String): CommitmentRow = CommitmentRow(
        id = id,
        itemType = "schedule",
        title = title,
        direction = null,
        scheduleStatus = "changed",
        decisionStatus = null,
        derivedStatus = null,
        actionState = com.becalm.android.domain.commitment.CommitmentState.PENDING,
        dueAt = Instant.parse("2026-04-24T01:00:00Z"),
        dueIsApproximate = false,
        dueHint = null,
        counterpartyDisplayName = "Carol Park",
        isManual = false,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
