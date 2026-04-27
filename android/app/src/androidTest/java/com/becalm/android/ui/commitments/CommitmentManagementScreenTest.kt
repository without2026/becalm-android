@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.ui.commitments

import android.content.Context
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
                    onOpenCreate = {},
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
        var openCreateCount = 0
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
                            decisionRow("decision-1", "Decision chosen"),
                        ),
                        activeItems = listOf(
                            activeRow("active-1", "Active commitment"),
                            scheduleRow("schedule-1", "Schedule change"),
                            decisionRow("decision-1", "Decision chosen"),
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
                    onOpenCreate = { openCreateCount += 1 },
                    onOpenDetail = { openedDetailId = it },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.commitments_filter_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_action)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_give)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_take)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_schedule)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_decision)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice Kim").assertIsDisplayed()
        composeTestRule.onNodeWithText("Schedule change").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active commitment").performClick()
        composeTestRule.onNodeWithTag("commitment-fab-add").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-schedule").performClick()

        composeTestRule.runOnIdle {
            assertEquals("active-1", openedDetailId)
            assertEquals(1, openCreateCount)
            assertEquals(CommitmentFilter.SCHEDULE, selectedFilter)
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

    private fun decisionRow(id: String, title: String): CommitmentRow = CommitmentRow(
        id = id,
        itemType = "decision",
        title = title,
        direction = null,
        scheduleStatus = null,
        decisionStatus = "chosen",
        derivedStatus = null,
        actionState = com.becalm.android.domain.commitment.CommitmentState.PENDING,
        dueAt = null,
        dueIsApproximate = false,
        dueHint = null,
        counterpartyDisplayName = "Bob Lee",
        isManual = false,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
