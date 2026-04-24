@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.integration.local.ui.commitments

import android.content.Context
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.commitments.CommitmentFilter
import com.becalm.android.ui.commitments.CommitmentManagementScreenContent
import com.becalm.android.ui.commitments.CommitmentRow
import com.becalm.android.ui.commitments.CommitmentSectionUiState
import com.becalm.android.ui.commitments.CommitmentUiState
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CommitmentManagementUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `commitment management shows empty state`() {
        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.commitments_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `commitment management shows filters sections fab and detail tap`() {
        var selectedFilter: CommitmentFilter? = null
        var openCreateCount = 0
        var openedDetailId: String? = null
        var toggleCompletedCount = 0
        var toggleCancelledCount = 0

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(activeRow("active-1", "활성 약속")),
                        activeItems = listOf(activeRow("active-1", "활성 약속")),
                        completedSection = CommitmentSectionUiState(
                            expanded = false,
                            count = 1,
                            items = listOf(activeRow("completed-1", "완료 약속")),
                        ),
                        cancelledSection = CommitmentSectionUiState(
                            expanded = false,
                            count = 1,
                            items = listOf(activeRow("cancelled-1", "취소 약속")),
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

        composeRule.onNodeWithText(string(R.string.commitments_filter_all)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitments_filter_give)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitments_filter_take)).assertIsDisplayed()
        composeRule.onNodeWithText("활성 약속").performClick()
        composeRule.onNodeWithText(string(R.string.commitment_section_completed_fmt, 1)).performClick()
        composeRule.onNodeWithText(string(R.string.commitment_section_cancelled_fmt, 1)).performClick()
        composeRule.onNodeWithTag("commitment-fab-add").performClick()
        composeRule.onNodeWithTag("commitment-filter-give").performClick()

        composeRule.runOnIdle {
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
        counterpartyDisplayName = "김철수",
        isManual = false,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
