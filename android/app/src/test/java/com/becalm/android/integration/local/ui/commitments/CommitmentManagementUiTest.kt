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
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.actions.PersonActionFeedStatusKind
import com.becalm.android.ui.actions.PersonActionFeedStatusUi
import com.becalm.android.ui.actions.PersonActionEvidenceUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.commitments.CommitmentActionEvidenceDetailUi
import com.becalm.android.ui.commitments.CommitmentFilter
import com.becalm.android.ui.commitments.CommitmentManagementScreenContent
import com.becalm.android.ui.commitments.CommitmentRow
import com.becalm.android.ui.commitments.CommitmentSectionUiState
import com.becalm.android.ui.commitments.CommitmentUiState
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `commitment management compact source warning opens source detail`() {
        var openSources = 0
        var openedSource: String? = null
        var statusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.DEGRADED,
                            backlogLagSeconds = 180,
                        ),
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
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "token expired",
                                lastSyncedAt = null,
                            ),
                        ),
                    ),
                    onOpenSources = { openSources += 1 },
                    onOpenSource = { openedSource = it },
                    onStatusDetailsClick = { statusClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("commitments-source-statusline-${SourceType.GMAIL}").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_degraded_compact), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("commitments-source-supporting-status-action").performClick()
        composeRule.onNodeWithTag("commitments-source-reconnect-${SourceType.GMAIL}").performClick()

        composeRule.runOnIdle {
            assertEquals(0, openSources)
            assertEquals(SourceType.GMAIL, openedSource)
            assertEquals(1, statusClicks)
        }
    }

    @Test
    fun `commitment management shows action feed degraded status with cached rows`() {
        var statusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.DEGRADED,
                            backlogLagSeconds = 180,
                        ),
                        topActions = listOf(personAction("pa-1")),
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onStatusDetailsClick = { statusClicks += 1 },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeRule.onNodeWithTag("commitments-action-feed-statusline").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_degraded), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("commitments-action-feed-statusline-action").performClick()

        composeRule.runOnIdle {
            assertEquals(1, statusClicks)
        }
    }

    @Test
    fun `commitment management summarizes multiple source warnings without pushing actions away`() {
        var openSources = 0
        var statusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.QUOTA_DELAY,
                            backlogLagSeconds = 600,
                        ),
                        topActions = listOf(
                            personAction("action-1"),
                        ),
                        items = listOf(activeRow("active-1", "제안서 초안 보내기")),
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "token expired",
                                lastSyncedAt = null,
                            ),
                            SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                status = SourceSyncStatus.Disconnected,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                        ),
                    ),
                    onOpenSources = { openSources += 1 },
                    onStatusDetailsClick = { statusClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("commitments-source-summary").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_source_attention_mixed_fmt, 1, 1), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_quota_compact), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Backend follow-up", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("commitments-source-supporting-status-action").performClick()
        composeRule.onNodeWithTag("commitments-source-summary-action").performClick()

        composeRule.runOnIdle {
            assertEquals(1, statusClicks)
            assertEquals(1, openSources)
        }
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
        composeRule.onAllNodesWithText(string(R.string.commitments_filter_closed)).assertCountEquals(0)
        composeRule.onAllNodesWithText("김철수").assertCountEquals(2)
        composeRule.onNodeWithTag("commitment-list").performScrollToNode(hasText("일정 변경"))
        composeRule.onNodeWithText("일정 변경").assertIsDisplayed()
        composeRule.onAllNodesWithText("완료 약속").assertCountEquals(0)
        composeRule.onAllNodesWithText("취소 약속").assertCountEquals(0)
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

    @Test
    fun `commitment action panel renders without legacy commitment rows`() {
        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = emptyList(),
                        topActions = listOf(personAction("pa-only")),
                        filter = CommitmentFilter.ALL,
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

        composeRule.onAllNodesWithText(string(R.string.commitments_empty_title)).assertCountEquals(0)
        composeRule.onNodeWithTag("commitment-action-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Backend follow-up").assertIsDisplayed()
    }

    @Test
    fun `commitment action panel defers legacy sections below urgent actions`() {
        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val legacy = activeRow("legacy-1", "Legacy confirmed row")
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(legacy),
                        topActions = listOf(personAction("pa-1")),
                        confirmedSection = CommitmentSectionUiState(
                            expanded = true,
                            count = 1,
                            items = listOf(legacy),
                        ),
                        filter = CommitmentFilter.ALL,
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

        composeRule.onNodeWithTag("commitment-action-panel").assertIsDisplayed()
        composeRule.onNodeWithText("Backend follow-up").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitment_section_confirmed_fmt, 1)).assertIsDisplayed()
        composeRule.onAllNodesWithText("Legacy confirmed row").assertCountEquals(0)
    }

    @Test
    fun `commitment action panel complete button calls person action completion handler`() {
        var completedActionId: String? = null

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val active = activeRow("active-1", "활성 약속")
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(active),
                        topActions = listOf(personAction("pa-1")),
                        activeItems = listOf(active),
                        reviewSection = CommitmentSectionUiState(
                            expanded = true,
                            count = 1,
                            items = listOf(active),
                        ),
                        filter = CommitmentFilter.ALL,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onCompletePersonAction = { completedActionId = it },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeRule.onNodeWithTag("commitment-list")
            .performScrollToNode(hasText("Backend follow-up"))
        composeRule.onNodeWithText("Backend follow-up").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.commitment_action_complete))
            .onFirst()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("pa-1", completedActionId)
        }
    }

    @Test
    fun `commitment action panel evidence button forwards backend evidence lookup ids`() {
        var openedActionId: String? = null
        var openedEvidenceKind: String? = null
        var openedEvidenceId: String? = null

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val active = activeRow("active-1", "활성 약속")
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(active),
                        topActions = listOf(
                            personAction(
                                id = "pa-evidence",
                                evidence = PersonActionEvidenceUi(
                                    kind = "source_event",
                                    id = "source-1",
                                    sourceRef = "mail-1",
                                    occurredAt = null,
                                    label = "메일",
                                    quote = "제안서를 내일까지 보내 주세요.",
                                ),
                            ),
                        ),
                        activeItems = listOf(active),
                        reviewSection = CommitmentSectionUiState(
                            expanded = true,
                            count = 1,
                            items = listOf(active),
                        ),
                        filter = CommitmentFilter.ALL,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onOpenPersonActionEvidence = { actionId, evidenceKind, evidenceId ->
                        openedActionId = actionId
                        openedEvidenceKind = evidenceKind
                        openedEvidenceId = evidenceId
                    },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeRule.onNodeWithTag("commitment-list")
            .performScrollToNode(hasText("Backend follow-up"))
        composeRule.onAllNodesWithText(string(R.string.commitment_action_evidence))
            .onFirst()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("pa-evidence", openedActionId)
            assertEquals("source_event", openedEvidenceKind)
            assertEquals("source-1", openedEvidenceId)
        }
    }

    @Test
    fun `commitment action panel reminder button forwards take action id`() {
        var remindedActionId: String? = null

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                val active = activeRow("active-1", "활성 약속")
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = listOf(active),
                        topActions = listOf(
                            personAction(
                                id = "pa-remind",
                                reasonCodes = listOf("direction:take", "waiting_on"),
                            ),
                        ),
                        activeItems = listOf(active),
                        reviewSection = CommitmentSectionUiState(
                            expanded = true,
                            count = 1,
                            items = listOf(active),
                        ),
                        filter = CommitmentFilter.ALL,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onRemindPersonAction = { remindedActionId = it },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeRule.onNodeWithTag("commitment-list")
            .performScrollToNode(hasText("Backend follow-up"))
        composeRule.onAllNodesWithText(string(R.string.commitment_action_remind))
            .onFirst()
            .performClick()

        composeRule.runOnIdle {
            assertEquals("pa-remind", remindedActionId)
        }
    }

    @Test
    fun `commitment action evidence dialog renders why original and dismisses`() {
        var dismissed = false

        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = CommitmentUiState(
                        loading = false,
                        items = emptyList(),
                        evidenceDetail = CommitmentActionEvidenceDetailUi(
                            actionItemId = "pa-dialog",
                            evidenceLabel = "메일",
                            whyText = "내일까지 제안서를 보내 달라는 요청",
                            originalTitle = "Proposal thread",
                            originalText = "Full original email body",
                            originalIsLocal = true,
                            sourceType = "gmail",
                        ),
                    ),
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = {},
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onOpenDetail = {},
                    onDismissPersonActionEvidence = { dismissed = true },
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.commitment_action_evidence_why)).assertIsDisplayed()
        composeRule.onNodeWithText("내일까지 제안서를 보내 달라는 요청").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitment_action_evidence_original)).assertIsDisplayed()
        composeRule.onNodeWithText("Full original email body").assertIsDisplayed()
        composeRule.onNodeWithText("Proposal thread").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.commitment_action_evidence_close)).performClick()

        composeRule.runOnIdle {
            assertTrue(dismissed)
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

    private fun personAction(
        id: String,
        evidence: PersonActionEvidenceUi? = null,
        reasonCodes: List<String> = listOf("commitment:due"),
    ): PersonActionItemUi = PersonActionItemUi(
        id = id,
        personId = "person-1",
        personDisplayName = "김철수",
        actionKind = "follow_up",
        title = "Backend follow-up",
        primaryVerb = "상세 보기",
        shortReason = "dev backend action",
        commitmentId = "active-1",
        calendarEventId = null,
        sourceEventId = null,
        sourceType = "gmail",
        sourceRef = "mail-1",
        dueAt = Instant.parse("2026-04-24T01:00:00Z"),
        dueHint = null,
        urgencyScore = 91.0,
        confidence = 0.95,
        reasonCodes = reasonCodes,
        evidence = evidence,
    )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
