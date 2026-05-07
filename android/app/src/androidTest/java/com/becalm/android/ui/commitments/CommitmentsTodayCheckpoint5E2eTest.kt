@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.ui.commitments

import android.content.Context
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.CommitmentScheduleStatus
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentEditValidator
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.TodayCommitmentRowTreatment
import com.becalm.android.ui.today.TodayTimelineContent
import com.becalm.android.ui.today.TodayUiState
import com.becalm.android.ui.today.TimelineItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommitmentsTodayCheckpoint5E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_053_commitments_tab_groups_cards_by_person_and_due_recency_order() {
        setCommitments(
            state = CommitmentUiState(
                loading = false,
                items = listOf(row("alice-today", "Alice today"), row("alice-tomorrow", "Alice tomorrow"), row("bob-later", "Bob later")),
                activeItems = listOf(
                    row("alice-today", "Alice today", person = "Alice Kim", dueAt = Instant.parse("2026-05-07T01:00:00Z")),
                    row("alice-tomorrow", "Alice tomorrow", person = "Alice Kim", dueAt = Instant.parse("2026-05-08T01:00:00Z")),
                    row("bob-later", "Bob later", person = "Bob Lee", dueAt = Instant.parse("2026-05-09T01:00:00Z")),
                ),
            ),
        )

        composeTestRule.onAllNodesWithText("Alice Kim").assertCountEquals(3)
        composeTestRule.onNodeWithText(string(R.string.commitments_person_group_count_fmt, 2)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Bob Lee").assertCountEquals(2)
        composeTestRule.onNodeWithText("Alice today").assertIsDisplayed()
        composeTestRule.onNodeWithText("Alice tomorrow").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob later").assertIsDisplayed()
    }

    @Test
    fun e2e_054_commitment_filters_render_give_take_schedule_closed_and_keep_decisions_hidden() {
        var selectedFilter: CommitmentFilter? = null

        setCommitments(
            state = CommitmentUiState(
                loading = false,
                filter = CommitmentFilter.ALL,
                items = listOf(
                    row("give", "내가 보낼 제안서", direction = "give"),
                    row("take", "상대가 보낼 가격표", direction = "take"),
                    row("schedule", "금요일 리뷰 미팅", itemType = "schedule", direction = null),
                    row("closed", "완료된 공유", actionState = CommitmentState.COMPLETED),
                ),
                activeItems = listOf(
                    row("give", "내가 보낼 제안서", direction = "give"),
                    row("take", "상대가 보낼 가격표", direction = "take"),
                    row("schedule", "금요일 리뷰 미팅", itemType = "schedule", direction = null),
                ),
                completedSection = CommitmentSectionUiState(
                    count = 1,
                    expanded = true,
                    items = listOf(row("closed", "완료된 공유", actionState = CommitmentState.COMPLETED)),
                ),
            ),
            onFilterChange = { selectedFilter = it },
        )

        composeTestRule.onNodeWithTag("commitment-filter-give").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-take").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-schedule").performClick()
        composeTestRule.onNodeWithTag("commitment-filter-closed").performClick()
        composeTestRule.onNodeWithText("내가 보낼 제안서").assertIsDisplayed()
        composeTestRule.onNodeWithText("상대가 보낼 가격표").assertIsDisplayed()
        composeTestRule.onNodeWithText("금요일 리뷰 미팅").assertIsDisplayed()
        composeTestRule.onNodeWithText("완료된 공유").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("A안 승인").assertCountEquals(0)

        composeTestRule.runOnIdle {
            assertEquals(CommitmentFilter.CLOSED, selectedFilter)
        }
    }

    @Test
    fun e2e_055_user_edits_commitment_card_fields_before_rest_crud_save() {
        var title: String? = null
        var counterpartyRef: String? = null
        var saved = 0

        composeTestRule.setContent {
            BecalmTheme {
                var state by remember {
                    mutableStateOf(
                        EditUiState(
                            loading = false,
                            readOnly = EditReadOnly(
                                quote = "금요일까지 제안서를 보내겠습니다.",
                                quoteDisputed = false,
                                sourceLabel = CommitmentText(
                                    R.string.commitment_detail_llm_source_fmt,
                                    listOf("Gmail", "5/7 10:00"),
                                ),
                            ),
                            title = "기존 약속",
                            dueAtMillis = Instant.parse("2026-05-08T01:00:00Z").toEpochMilliseconds(),
                            dueIsApproximate = false,
                            dueHint = "",
                            counterpartyRef = "alice@example.com",
                            direction = "give",
                        ),
                    )
                }
                EditSheetContent(
                    state = state,
                    onTitleChange = {
                        title = it
                        state = state.copy(title = it)
                    },
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {},
                    onCounterpartyRefChange = {
                        counterpartyRef = it
                        state = state.copy(counterpartyRef = it)
                    },
                    onDirectionChange = {},
                    onToggleDispute = {},
                    onSave = { saved += 1 },
                    onCancel = {},
                    onConfirmDelete = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("commitment-edit-title").performTextReplacement("수정된 약속")
        composeTestRule.onNodeWithTag("commitment-edit-person-ref").performTextReplacement("bob@example.com")
        composeTestRule.onNodeWithTag("commitment-edit-save").performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals("수정된 약속", title)
            assertEquals("bob@example.com", counterpartyRef)
            assertEquals(1, saved)
        }
    }

    @Test
    fun e2e_056_detail_routes_complete_cancel_and_edit_actions() {
        var completeCount = 0
        var cancelCount = 0
        var editCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                DetailSheetContent(
                    entity = commitmentEntity(),
                    quote = "금요일까지 제안서를 보내겠습니다.",
                    actionState = CommitmentState.PENDING,
                    source = CommitmentSourcePresentation(),
                    history = CommitmentHistoryPresentation(),
                    actionButtons = CommitmentDetailActionState(
                        availableActions = setOf(CommitmentSheetAction.COMPLETE),
                        editEnabled = true,
                    ),
                    counterpartyDisplayName = "Alice Kim",
                    onRemind = {},
                    onFollowUp = {},
                    onComplete = { completeCount += 1 },
                    onCancel = { cancelCount += 1 },
                    onEdit = { editCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.commitment_action_complete)).performClick()
        composeTestRule.onNodeWithText(string(R.string.commitment_action_cancel)).performClick()
        composeTestRule.onNodeWithTag("commitment-detail-edit").performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, completeCount)
            assertEquals(1, cancelCount)
            assertEquals(1, editCount)
        }
    }

    @Test
    fun e2e_057_user_deletes_commitment_from_edit_sheet() {
        var deleteCount = 0
        composeTestRule.setContent {
            BecalmTheme {
                EditSheetContent(
                    state = EditUiState(
                        loading = false,
                        readOnly = EditReadOnly(
                            quote = "금요일까지 제안서를 보내겠습니다.",
                            quoteDisputed = false,
                            sourceLabel = CommitmentText(
                                R.string.commitment_detail_llm_source_fmt,
                                listOf("Gmail", "5/7 10:00"),
                            ),
                        ),
                        title = "삭제할 약속",
                        counterpartyRef = "alice@example.com",
                        direction = "give",
                    ),
                    onTitleChange = {},
                    onDueAtMillisChange = {},
                    onDueIsApproximateChange = {},
                    onDueHintChange = {},
                    onCounterpartyRefChange = {},
                    onDirectionChange = {},
                    onToggleDispute = {},
                    onSave = {},
                    onCancel = {},
                    onConfirmDelete = { deleteCount += 1 },
                )
            }
        }
        composeTestRule.onNodeWithTag("commitment-edit-delete").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("commitment-edit-delete-confirm-ok").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, deleteCount)
        }
    }

    @Test
    fun e2e_058_today_shows_timed_work_and_untimed_needs_time_section() {
        setToday(
            timeline = listOf(
                TimelineItem.Commitment(
                    id = "timed-action",
                    itemType = CommitmentItemType.ACTION,
                    title = "10시까지 제안서 보내기",
                    direction = "give",
                    scheduleStatus = null,
                    rowTreatment = TodayCommitmentRowTreatment.ACTION,
                    counterpartyDisplayName = "Alice Kim",
                    dueAt = Instant.parse("2026-05-07T01:00:00Z"),
                    dueIsApproximate = false,
                    dueHint = null,
                    sortKey = Instant.parse("2026-05-07T01:00:00Z"),
                    timelineAt = Instant.parse("2026-05-07T01:00:00Z"),
                    isTimed = true,
                ),
                TimelineItem.Commitment(
                    id = "untimed-action",
                    itemType = CommitmentItemType.ACTION,
                    title = "시간 정해서 회신하기",
                    direction = "take",
                    scheduleStatus = null,
                    rowTreatment = TodayCommitmentRowTreatment.ACTION,
                    counterpartyDisplayName = "Bob Lee",
                    dueAt = null,
                    dueIsApproximate = false,
                    dueHint = null,
                    sortKey = Instant.parse("2026-05-07T02:00:00Z"),
                    timelineAt = null,
                    isTimed = false,
                ),
                TimelineItem.Commitment(
                    id = "schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    title = "오후 리뷰 미팅",
                    direction = null,
                    scheduleStatus = CommitmentScheduleStatus.CONFIRMED,
                    rowTreatment = TodayCommitmentRowTreatment.SCHEDULE,
                    counterpartyDisplayName = "Carol Park",
                    dueAt = Instant.parse("2026-05-07T06:00:00Z"),
                    dueIsApproximate = false,
                    dueHint = null,
                    sortKey = Instant.parse("2026-05-07T06:00:00Z"),
                    timelineAt = Instant.parse("2026-05-07T06:00:00Z"),
                    isTimed = true,
                ),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.today_timed_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText("10시까지 제안서 보내기").assertIsDisplayed()
        composeTestRule.onNodeWithText("오후 리뷰 미팅").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_untimed_section)).assertIsDisplayed()
        composeTestRule.onNodeWithText("시간 정해서 회신하기").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_no_due_time)).assertIsDisplayed()
    }

    @Test
    fun e2e_059_user_adds_missing_due_time_from_today() {
        var openedAddTime: String? = null
        setToday(
            timeline = listOf(
                TimelineItem.Commitment(
                    id = "needs-time",
                    itemType = CommitmentItemType.ACTION,
                    title = "시간 없는 회신",
                    direction = "give",
                    scheduleStatus = null,
                    rowTreatment = TodayCommitmentRowTreatment.ACTION,
                    counterpartyDisplayName = "Alice Kim",
                    dueAt = null,
                    dueIsApproximate = false,
                    dueHint = null,
                    sortKey = Instant.parse("2026-05-07T02:00:00Z"),
                    timelineAt = null,
                    isTimed = false,
                ),
            ),
            onAddDueTime = { openedAddTime = it },
        )

        composeTestRule.onNodeWithText(string(R.string.today_add_due_time)).performClick()

        composeTestRule.runOnIdle {
            assertEquals("needs-time", openedAddTime)
        }
    }

    @Test
    fun e2e_060_today_empty_state_keeps_source_status_shell_available() {
        setToday(timeline = emptyList())

        composeTestRule.onNodeWithText(string(R.string.today_empty_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.today_empty_message)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("evidence-import-fab").assertIsDisplayed()
    }

    private fun setCommitments(
        state: CommitmentUiState,
        onFilterChange: (CommitmentFilter) -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                CommitmentManagementScreenContent(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    pullState = pullState,
                    onFilterChange = onFilterChange,
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    onMeetingTranscriptImport = {},
                    onManualTextImport = {},
                    onOpenDetail = {},
                    onToggleCompletedSection = {},
                    onToggleCancelledSection = {},
                )
            }
        }
    }

    private fun setToday(
        timeline: List<TimelineItem>,
        onAddDueTime: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                TodayTimelineContent(
                    state = TodayUiState(
                        loading = false,
                        timeline = timeline,
                    ),
                    onOpenSettings = {},
                    onPullRefresh = {},
                    onAddDueTime = onAddDueTime,
                )
            }
        }
    }

    private fun row(
        id: String,
        title: String,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        actionState: CommitmentState = CommitmentState.PENDING,
        person: String = "Alice Kim",
        dueAt: Instant? = Instant.parse("2026-05-07T01:00:00Z"),
    ): CommitmentRow =
        CommitmentRow(
            id = id,
            itemType = itemType,
            title = title,
            direction = direction,
            scheduleStatus = if (itemType == CommitmentItemType.SCHEDULE) CommitmentScheduleStatus.CONFIRMED else null,
            decisionStatus = null,
            derivedStatus = actionState.wireValue.uppercase(),
            actionState = actionState,
            dueAt = dueAt,
            dueIsApproximate = false,
            counterpartyDisplayName = person,
            sourceType = SourceType.GMAIL,
            sourceTitle = "업무 메일",
            sourceOccurredAt = Instant.parse("2026-05-06T01:00:00Z"),
            isManual = false,
        )

    private fun commitmentEntity(): CommitmentEntity =
        CommitmentEntity(
            id = "commitment-1",
            userId = "user-1",
            itemType = CommitmentItemType.ACTION,
            direction = "give",
            scheduleStatus = null,
            decisionStatus = null,
            counterpartyRaw = "Alice Kim",
            counterpartyRef = "alice@example.com",
            title = "제안서 보내기",
            description = null,
            quote = "금요일까지 제안서를 보내겠습니다.",
            sourceEventTitle = "Gmail",
            sourceEventOccurredAt = Instant.parse("2026-05-07T01:00:00Z"),
            dueAt = Instant.parse("2026-05-08T01:00:00Z"),
            dueHint = null,
            dueIsApproximate = false,
            actionState = "pending",
            sourceType = SourceType.GMAIL,
            sourceRef = "raw-1",
            confidence = 0.9,
            commitmentState = CommitmentLifecycleLegacy.DRAFT,
            syncStatus = "synced",
            createdAt = Instant.parse("2026-05-07T01:00:00Z"),
            updatedAt = Instant.parse("2026-05-07T01:00:00Z"),
        )

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
