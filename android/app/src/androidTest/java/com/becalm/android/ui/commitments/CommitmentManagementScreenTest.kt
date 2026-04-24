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
        var toggleCompletedCount = 0
        var toggleCancelledCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(activeRow("active-1", "Active commitment")),
                        activeItems = listOf(activeRow("active-1", "Active commitment")),
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
                    onToggleCompletedSection = { toggleCompletedCount += 1 },
                    onToggleCancelledSection = { toggleCancelledCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.commitments_filter_all)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_give)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.commitments_filter_take)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Active commitment").performClick()
        composeTestRule.onNodeWithText(string(R.string.commitment_section_completed_fmt, 1)).performClick()
        composeTestRule.onNodeWithText(string(R.string.commitment_section_cancelled_fmt, 1)).performClick()
        composeTestRule.onNodeWithTag("commitment-fab-add").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-give").performClick()

        composeTestRule.runOnIdle {
            assertEquals("active-1", openedDetailId)
            assertEquals(1, toggleCompletedCount)
            assertEquals(1, toggleCancelledCount)
            assertEquals(1, openCreateCount)
            assertEquals(CommitmentFilter.GIVE, selectedFilter)
        }
    }

    private fun activeRow(id: String, title: String): CommitmentRow = CommitmentRow(
        id = id,
        title = title,
        direction = "give",
        derivedStatus = "PENDING",
        actionState = com.becalm.android.domain.commitment.CommitmentState.PENDING,
        dueAt = Instant.parse("2026-04-24T01:00:00Z"),
        dueIsApproximate = false,
        dueHint = null,
        counterpartyDisplayName = "Alice Kim",
        isManual = false,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
