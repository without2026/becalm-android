@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.integration.local.ui.commitments

import android.content.Context
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
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
        var openedDetailId: String? = null

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val active = activeRow("active-1", "활성 약속")
                val schedule = scheduleRow("schedule-1", "일정 변경")
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
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = { openedDetailId = it },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.commitments_filter_all)).assertIsDisplayed()
        composeRule.onAllNodesWithText("김철수").assertCountEquals(2)
        composeRule.onNodeWithTag("commitment-list").performScrollToNode(hasText("일정 변경"))
        composeRule.onNodeWithText("일정 변경").assertIsDisplayed()
        composeRule.onNodeWithTag("commitment-list").performScrollToNode(hasText("활성 약속"))
        composeRule.onNodeWithText("활성 약속").performClick()
        composeRule.onNodeWithTag("evidence-import-fab").performClick()
        composeRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeRule.onAllNodesWithTag("commitment-filter-schedule").assertCountEquals(0)
        composeRule.onNodeWithTag("commitment-filter-give").performClick()

        composeRule.runOnIdle {
            assertEquals("active-1", openedDetailId)
            assertEquals(CommitmentFilter.GIVE, selectedFilter)
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
        counterpartyDisplayName = "김철수",
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
        counterpartyDisplayName = "박과장",
        isManual = false,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
