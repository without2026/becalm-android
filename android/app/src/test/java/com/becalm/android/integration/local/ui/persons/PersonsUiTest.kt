package com.becalm.android.integration.local.ui.persons

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.persons.PersonDetailCommitmentSummary
import com.becalm.android.ui.persons.PersonDetailScreenContent
import com.becalm.android.ui.persons.PersonDetailUiState
import com.becalm.android.ui.persons.PersonActionFeedStatusKind
import com.becalm.android.ui.persons.PersonActionFeedStatusUi
import com.becalm.android.ui.persons.PersonActionSummary
import com.becalm.android.ui.persons.PersonRow
import com.becalm.android.ui.persons.PersonsScreenContent
import com.becalm.android.ui.persons.PersonsUiState
import com.becalm.android.ui.persons.ManualMemorySyncStatusKind
import com.becalm.android.ui.persons.ManualMemorySyncStatusUi
import com.becalm.android.ui.persons.SourceEventCardProjection
import com.becalm.android.ui.persons.SourceEventCardRow
import com.becalm.android.ui.persons.UnassignedEventSummary
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.onboarding.FirstMemoryFollowUpAction
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
class PersonsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `persons screen shows offline badge unassigned section and enriched row meta`() {
        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "+821012345678",
                                displayName = "김철수",
                                companyName = "ABC Corp",
                                jobTitle = "팀장",
                                lastInteractionAt = Instant.parse("2026-04-24T01:00:00Z"),
                                interactionCount = 3,
                                pendingCommitmentCount = 2,
                                lastInteractionSnippet = "계약서 검토 요청",
                            ),
                        ),
                        unassignedEvents = listOf(
                            UnassignedEventSummary(
                                id = "event-unassigned",
                                sourceType = "voice",
                                title = "미분류 이벤트",
                                timestamp = Instant.parse("2026-04-24T01:05:00Z"),
                            ),
                        ),
                        showOfflineBadge = true,
                        offlineLastSyncAt = null,
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.person_matching_required_banner_title)).assertExists()
        composeRule.onNodeWithText(string(R.string.persons_offline_badge_no_sync)).assertIsDisplayed()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText("김철수", substring = true))
        composeRule.onNodeWithText("김철수").assertExists()
        composeRule.onAllNodesWithText("김철수 · ABC Corp · 팀장").assertCountEquals(0)
        composeRule.onNodeWithText(string(R.string.persons_pending_commitments_fmt, 2), substring = true).assertExists()
        composeRule.onNodeWithText("계약서 검토 요청", substring = true).assertExists()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText(string(R.string.persons_unassigned_title)))
        composeRule.onNodeWithText(string(R.string.persons_unassigned_title)).assertExists()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText("미분류 이벤트"))
        composeRule.onNodeWithText("미분류 이벤트").assertExists()
    }

    @Test
    fun `persons screen promotes backend person action cache into prototype next action surface`() {
        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "person-action",
                                displayName = "김민홍",
                                companyName = "거래처",
                                jobTitle = "대표",
                                lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
                                interactionCount = 4,
                                topAction = PersonActionSummary(
                                    id = "act-urgent",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    shortReason = "6/1 통화에서 오늘까지 보내기로 한 약속입니다.",
                                    actionKind = "reply",
                                    dueAt = null,
                                    dueHint = "오늘까지",
                                    urgencyScore = 92.0,
                                ),
                            ),
                            PersonRow(
                                personId = "person-week",
                                displayName = "이준호",
                                companyName = "세무사",
                                lastInteractionAt = Instant.parse("2026-06-03T01:00:00Z"),
                                interactionCount = 2,
                                topAction = PersonActionSummary(
                                    id = "act-week",
                                    title = "분기 미팅 날짜 제안하기",
                                    primaryVerb = "제안",
                                    shortReason = "이번 주 안에 후보 시간을 보내면 됩니다.",
                                    actionKind = "schedule",
                                    dueAt = null,
                                    dueHint = "이번 주",
                                    urgencyScore = 25.0,
                                ),
                            ),
                            PersonRow(
                                personId = "person-stale",
                                displayName = "최민서",
                                companyName = "파트너사",
                                lastInteractionAt = Instant.parse("2025-05-10T01:00:00Z"),
                                interactionCount = 1,
                                lastInteractionSnippet = "24일째 연락 없음 — 복기하고 재개하기",
                            ),
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.persons_home_headline)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_home_body)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_section_pending_commitments)).assertExists()
        composeRule.onNodeWithTag("persons-action-row-act-urgent").assertIsDisplayed()
        composeRule.onNodeWithText("오늘까지 · 수정 계약서 회신").assertExists()
        composeRule.onAllNodesWithText("6/1 통화에서 오늘까지 보내기로 한 약속입니다.")
            .assertCountEquals(0)
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText(string(R.string.persons_section_this_week)))
        composeRule.onNodeWithText(string(R.string.persons_section_this_week)).assertExists()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText("분기 미팅 날짜 제안하기", substring = true))
        composeRule.onNodeWithTag("persons-action-row-act-week").assertIsDisplayed()
        composeRule.onNodeWithText("이번 주 · 분기 미팅 날짜 제안하기").assertIsDisplayed()
        composeRule.onNodeWithTag("persons-list")
            .performScrollToNode(hasText(string(R.string.persons_section_recent_contacts)))
        composeRule.onNodeWithText(string(R.string.persons_section_recent_contacts)).assertExists()
        composeRule.onNodeWithText("24일째 연락 없음 — 복기하고 재개하기").assertExists()
    }

    @Test
    fun `persons screen surfaces source status and reconnect entry on action first home`() {
        var sourceManagementClicks = 0
        var openedSource: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "person-action",
                                displayName = "김민홍",
                                lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
                                interactionCount = 4,
                                topAction = PersonActionSummary(
                                    id = "act-urgent",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    shortReason = "오늘까지 보내기로 한 약속입니다.",
                                    actionKind = "reply",
                                    dueAt = null,
                                    urgencyScore = 92.0,
                                ),
                            ),
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "needs_reauth",
                                lastSyncedAt = null,
                            ),
                            SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                status = SourceSyncStatus.Connected,
                                errorMessage = null,
                                lastSyncedAt = Instant.parse("2026-06-04T00:55:00Z"),
                            ),
                        ),
                        overall = OverallSyncState.PartialFailure,
                    ),
                    onQueryChange = {},
                    onPersonClick = {},
                    onOpenSources = { sourceManagementClicks += 1 },
                    onOpenSource = { openedSource = it },
                )
            }
        }

        composeRule.onNodeWithText(
            string(
                R.string.persons_source_status_delayed_prefix_fmt,
                string(R.string.raw_event_source_badge_gmail),
            ),
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_source_status_delayed_suffix), substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.today_source_attention_action))
            .assertCountEquals(0)
        composeRule.onNodeWithTag("persons-source-reconnect-${SourceType.GMAIL}").performClick()
        composeRule.runOnIdle {
            assertEquals(0, sourceManagementClicks)
            assertEquals(SourceType.GMAIL, openedSource)
        }
        composeRule.onAllNodesWithText(string(R.string.raw_event_source_badge_google_calendar))
            .assertCountEquals(0)
    }

    @Test
    fun `persons screen summarizes multiple source warnings with action feed degraded status`() {
        var sourceManagementClicks = 0
        var processingStatusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "person-action",
                                displayName = "김민홍",
                                lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
                                interactionCount = 4,
                                topAction = PersonActionSummary(
                                    id = "act-urgent",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    shortReason = "오늘까지 보내기로 한 약속입니다.",
                                    actionKind = "reply",
                                    dueAt = null,
                                    urgencyScore = 92.0,
                                ),
                            ),
                        ),
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.DEGRADED,
                            backlogLagSeconds = 600,
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "needs_reauth",
                                lastSyncedAt = null,
                            ),
                            SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                status = SourceSyncStatus.Disconnected,
                                errorMessage = null,
                                lastSyncedAt = null,
                            ),
                        ),
                        overall = OverallSyncState.PartialFailure,
                    ),
                    onQueryChange = {},
                    onPersonClick = {},
                    onOpenSources = { sourceManagementClicks += 1 },
                    onOpenProcessingStatus = { processingStatusClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("persons-source-summary").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_source_attention_mixed_fmt, 1, 1), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_degraded_compact), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_lag_minutes_fmt, 10), substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("persons-action-feed-statusline")
            .assertCountEquals(0)
        composeRule.onNodeWithText("수정 계약서 회신", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.raw_event_source_badge_google_calendar))
            .assertCountEquals(0)
        composeRule.onNodeWithTag("persons-source-supporting-status-action").performClick()
        composeRule.onNodeWithTag("persons-source-summary-action").performClick()

        composeRule.runOnIdle {
            assertEquals(1, processingStatusClicks)
            assertEquals(1, sourceManagementClicks)
        }
    }

    @Test
    fun `persons screen surfaces action feed readiness when sources are healthy`() {
        var processingStatusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "person-action",
                                displayName = "김민홍",
                                lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
                                interactionCount = 4,
                                topAction = PersonActionSummary(
                                    id = "act-urgent",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    shortReason = "오늘까지 보내기로 한 약속입니다.",
                                    actionKind = "reply",
                                    dueAt = null,
                                    urgencyScore = 92.0,
                                ),
                            ),
                        ),
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.QUOTA_DELAY,
                            backlogLagSeconds = 600,
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Connected,
                                errorMessage = null,
                                lastSyncedAt = Instant.parse("2026-06-04T00:55:00Z"),
                            ),
                        ),
                        overall = OverallSyncState.Synced(Instant.parse("2026-06-04T00:55:00Z")),
                    ),
                    onQueryChange = {},
                    onPersonClick = {},
                    onOpenProcessingStatus = { processingStatusClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("persons-action-feed-statusline").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_quota), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_lag_minutes_fmt, 10), substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.raw_event_source_badge_gmail))
            .assertCountEquals(0)
        composeRule.onNodeWithTag("persons-action-feed-status-action").performClick()

        composeRule.runOnIdle {
            assertEquals(1, processingStatusClicks)
        }
    }

    @Test
    fun `persons screen folds action feed readiness into source warning line`() {
        var openedSource: String? = null
        var processingStatusClicks = 0

        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "person-action",
                                displayName = "김민홍",
                                lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
                                interactionCount = 4,
                                topAction = PersonActionSummary(
                                    id = "act-urgent",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    shortReason = "오늘까지 보내기로 한 약속입니다.",
                                    actionKind = "reply",
                                    dueAt = null,
                                    urgencyScore = 92.0,
                                ),
                            ),
                        ),
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.DEGRADED,
                            backlogLagSeconds = 600,
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "needs_reauth",
                                lastSyncedAt = null,
                            ),
                        ),
                        overall = OverallSyncState.PartialFailure,
                    ),
                    onQueryChange = {},
                    onPersonClick = {},
                    onOpenSource = { openedSource = it },
                    onOpenProcessingStatus = { processingStatusClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("persons-source-statusline-${SourceType.GMAIL}").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_degraded_compact), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_lag_minutes_fmt, 10), substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("persons-action-feed-statusline")
            .assertCountEquals(0)
        composeRule.onNodeWithText("수정 계약서 회신", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("persons-source-supporting-status-action").performClick()
        composeRule.onNodeWithTag("persons-source-reconnect-${SourceType.GMAIL}").performClick()

        composeRule.runOnIdle {
            assertEquals(1, processingStatusClicks)
            assertEquals(SourceType.GMAIL, openedSource)
        }
    }

    @Test
    fun `persons screen keeps matching source and processing warnings compact above action rows`() {
        var matchingClicks = 0
        var openedSource: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "person-action",
                                displayName = "김민홍",
                                lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
                                interactionCount = 4,
                                topAction = PersonActionSummary(
                                    id = "act-urgent",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    shortReason = "오늘까지 보내기로 한 약속입니다.",
                                    actionKind = "reply",
                                    dueAt = null,
                                    urgencyScore = 92.0,
                                ),
                            ),
                        ),
                        unassignedEvents = listOf(
                            UnassignedEventSummary(
                                id = "event-unassigned",
                                sourceType = SourceType.GMAIL,
                                title = "상대 확인 필요",
                                timestamp = Instant.parse("2026-06-04T01:05:00Z"),
                            ),
                        ),
                        actionFeedStatus = PersonActionFeedStatusUi(
                            kind = PersonActionFeedStatusKind.DEGRADED,
                            backlogLagSeconds = 600,
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    headerState = MainTabHeaderState(
                        sourceStatus = mapOf(
                            SourceType.GMAIL to SourceStatusUi(
                                status = SourceSyncStatus.Error,
                                errorMessage = "needs_reauth",
                                lastSyncedAt = null,
                            ),
                        ),
                        overall = OverallSyncState.PartialFailure,
                    ),
                    onQueryChange = {},
                    onPersonClick = {},
                    onOpenUnassigned = { matchingClicks += 1 },
                    onOpenSource = { openedSource = it },
                )
            }
        }

        composeRule.onNodeWithTag("persons-matching-required-statusline").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_matching_required_status_prefix_fmt, 1), substring = true)
            .assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.person_matching_required_banner_title))
            .assertCountEquals(0)
        composeRule.onNodeWithTag("persons-source-statusline-${SourceType.GMAIL}").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.persons_action_feed_status_degraded_compact), substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("수정 계약서 회신", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag("evidence-import-fab")
            .assertCountEquals(0)

        composeRule.onNodeWithTag("persons-matching-required-status-action").performClick()
        composeRule.onNodeWithTag("persons-source-reconnect-${SourceType.GMAIL}").performClick()

        composeRule.runOnIdle {
            assertEquals(1, matchingClicks)
            assertEquals(SourceType.GMAIL, openedSource)
        }
    }

    @Test
    fun `persons search input routes typed query`() {
        var typedQuery: String? = null
        var screenshotImports = 0

        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        query = query,
                        people = listOf(
                            PersonRow(
                                personId = "kim@example.com",
                                displayName = "김철수",
                                lastInteractionAt = Instant.parse("2026-04-24T01:00:00Z"),
                                interactionCount = 1,
                            ),
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {
                        query = it
                        typedQuery = it
                    },
                    onPersonClick = {},
                    onMessageScreenshotImport = { screenshotImports += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("persons-search-input").performTextInput("김")
        composeRule.onNodeWithTag("persons-search-clear").performClick()
        composeRule.onNodeWithTag("evidence-import-fab").performClick()
        composeRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals("", typedQuery)
            assertEquals(1, screenshotImports)
        }
    }

    @Test
    fun `persons search empty keeps prototype empty state instead of first memory form`() {
        composeRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        query = "없는 사람",
                        people = emptyList(),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.persons_search_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag("persons-search-empty").assertIsDisplayed()
        composeRule.onAllNodesWithTag("persons-empty-first-memory").assertCountEquals(0)
    }

    @Test
    fun `person detail shows source filters and source-only timeline`() {
        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "+821012345678",
                        displayName = "김철수",
                        nickname = "철수",
                        companyName = "ABC Corp",
                        jobTitle = "팀장",
                        eventCount = 2,
                        pendingCommitmentCount = 1,
                        sourceEventCards = listOf(
                            SourceEventCardProjection(
                                sourceEventKey = "raw:event-8",
                                sourceType = "gmail",
                                rawEventId = "event-8",
                                occurredAt = Instant.parse("2026-04-24T02:00:00Z"),
                                title = "메일",
                                snippet = "메일 회신",
                                commitmentsExtractedCount = 0,
                            ),
                            SourceEventCardProjection(
                                sourceEventKey = "raw:event-7",
                                sourceType = "voice",
                                rawEventId = "event-7",
                                occurredAt = Instant.parse("2026-04-24T01:00:00Z"),
                                title = "콜 녹음",
                                snippet = "금요일까지 회신",
                                commitmentsExtractedCount = 1,
                                myActions = listOf(
                                    PersonDetailCommitmentSummary(
                                        title = "제안서 보내기",
                                        itemType = CommitmentItemType.ACTION,
                                        direction = "give",
                                        status = "pending",
                                    ),
                                ),
                                theirActions = listOf(
                                    PersonDetailCommitmentSummary(
                                        title = "완료된 약속",
                                        itemType = CommitmentItemType.ACTION,
                                        direction = "take",
                                        status = "completed",
                                    ),
                                ),
                            ),
                        ),
                        loading = false,
                    ),
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_filter_all)))
        composeRule.onNodeWithText(string(R.string.person_detail_filter_all)).assertIsDisplayed()
        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_timeline_section_fmt, 2)))
        composeRule.onNodeWithText(string(R.string.person_detail_timeline_section_fmt, 2)).assertIsDisplayed()
        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText("콜 녹음"))
        composeRule.onNodeWithText(string(R.string.raw_event_commitments_extracted, 1)).assertExists()
        composeRule.onAllNodesWithText("제안서 보내기").assertCountEquals(0)
        composeRule.onAllNodesWithText("완료된 약속").assertCountEquals(0)
    }

    @Test
    fun `person detail load more action is exposed after capped timeline`() {
        var loadMoreRequests = 0

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "김철수",
                        eventCount = 150,
                        sourceEventCards = (0 until 150).map { index ->
                            SourceEventCardProjection(
                                sourceEventKey = "raw:event-$index",
                                sourceType = "gmail",
                                rawEventId = "event-$index",
                                occurredAt = Instant.fromEpochMilliseconds(index * 1_000L),
                                title = "고객 메일 $index",
                                snippet = null,
                            )
                        },
                        canLoadMoreTimeline = true,
                        loading = false,
                    ),
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onLoadMoreTimeline = { loadMoreRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_load_more)))
        composeRule.onNodeWithText(string(R.string.person_detail_load_more)).performClick()

        composeRule.runOnIdle { assertEquals(1, loadMoreRequests) }
    }

    @Test
    fun `person detail blocking error exposes retry action`() {
        var retryRequests = 0

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "김철수",
                        loading = false,
                        error = UiMessage.resource(R.string.person_detail_error_load_failed),
                    ),
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onRetry = { retryRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.error_state_retry)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.error_state_retry)).performClick()

        composeRule.runOnIdle { assertEquals(1, retryRequests) }
    }

    @Test
    fun `source event card dispatches raw event tap`() {
        var tappedEventId: String? = null

        composeRule.setContent {
            BecalmTheme {
                SourceEventCardRow(
                    card = SourceEventCardProjection(
                        sourceEventKey = "raw:event-7",
                        sourceType = "voice",
                        rawEventId = "event-7",
                        occurredAt = Instant.parse("2026-04-24T01:00:00Z"),
                        title = "콜 녹음",
                        snippet = "금요일까지 회신",
                        commitmentsExtractedCount = 1,
                    ),
                    onEventTap = { tappedEventId = it },
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-source-card-raw:event-7").performClick()

        composeRule.runOnIdle { assertEquals("event-7", tappedEventId) }
    }

    @Test
    fun `person detail lifts backend next action into recommendation panel`() {
        var tappedEventId: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "김철수",
                        topActions = listOf(
                            PersonActionItemUi(
                                id = "pa-reply",
                                personId = "person-1",
                                personDisplayName = "김철수",
                                actionKind = "reply",
                                title = "자료 확인 요청에 답장",
                                primaryVerb = string(R.string.person_detail_next_action_email_reply),
                                shortReason = "확인 후 답장 주세요.",
                                commitmentId = null,
                                calendarEventId = null,
                                sourceEventId = null,
                                sourceType = "gmail",
                                sourceRef = "raw:mail-1",
                                dueAt = null,
                                dueHint = null,
                                urgencyScore = 88.0,
                                confidence = 0.86,
                                reasonCodes = emptyList(),
                                evidence = null,
                            ),
                        ),
                        sourceEventCards = listOf(
                            SourceEventCardProjection(
                                sourceEventKey = "raw:mail-1",
                                sourceType = "gmail",
                                rawEventId = "mail-1",
                                occurredAt = Instant.parse("2026-04-24T01:00:00Z"),
                                title = "자료 확인 요청",
                                snippet = "확인 후 답장 주세요.",
                            ),
                        ),
                        loading = false,
                    ),
                    title = "김철수",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = { tappedEventId = it },
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_next_action_email_reply)))
        composeRule.onNodeWithTag("person-detail-next-action-panel").assertIsDisplayed()
        composeRule.onAllNodesWithTag("person-detail-relationship-recall").assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.person_detail_next_action_email_reply)).assertCountEquals(1)
        composeRule.onNodeWithTag("person-detail-action-pa-reply").performClick()

        composeRule.runOnIdle {
            assertEquals("mail-1", tappedEventId)
        }
    }

    @Test
    fun `person detail shows stale relationship recall when there is no active next action`() {
        var tappedEventId: String? = null

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "최민서",
                        sourceEventCards = listOf(
                            SourceEventCardProjection(
                                sourceEventKey = "raw:mail-1",
                                sourceType = "gmail",
                                rawEventId = "mail-1",
                                occurredAt = Instant.parse("2025-05-10T01:00:00Z"),
                                title = "견적 재검토 요청",
                                snippet = "진행 여부 회신을 기다리던 대화",
                            ),
                        ),
                        loading = false,
                    ),
                    title = "최민서",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = { tappedEventId = it },
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_recall_title)))
        composeRule.onNodeWithTag("person-detail-relationship-recall").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_detail_recall_title)).assertIsDisplayed()
        composeRule.onNodeWithText("연락이 없어요", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_detail_recall_open_last)).performClick()

        composeRule.runOnIdle {
            assertEquals("mail-1", tappedEventId)
        }
    }

    @Test
    fun `person detail first memory recommendation rows are actionable`() {
        var clicked: FirstMemoryFollowUpAction? = null

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "민지",
                        sourceEventCards = listOf(
                            SourceEventCardProjection(
                                sourceEventKey = "manual:mail-1",
                                sourceType = "manual",
                                rawEventId = null,
                                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
                                title = "민지님과 직접 입력한 약속",
                                snippet = "금요일까지 제안서 초안 보내기",
                                firstMemoryOrigin = "email",
                            ),
                        ),
                        loading = false,
                    ),
                    title = "민지",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onFirstMemoryFollowUpAction = { clicked = it },
                )
            }
        }

        composeRule.onNodeWithTag("first-memory-action-gmail").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(FirstMemoryFollowUpAction.GMAIL, clicked)
        }
    }

    @Test
    fun `person detail manual memory failed sync is visible and retryable`() {
        var retried = false

        composeRule.setContent {
            BecalmTheme {
                PersonDetailScreenContent(
                    state = PersonDetailUiState(
                        personId = "person-1",
                        displayName = "민지",
                        sourceEventCards = listOf(
                            SourceEventCardProjection(
                                sourceEventKey = "manual:mail-1",
                                sourceType = "manual",
                                rawEventId = null,
                                occurredAt = Instant.parse("2026-05-26T00:00:00Z"),
                                title = "민지님과 직접 입력한 약속",
                                snippet = "금요일까지 제안서 초안 보내기",
                                firstMemoryOrigin = "email",
                            ),
                        ),
                        manualMemorySyncStatus = ManualMemorySyncStatusUi(
                            kind = ManualMemorySyncStatusKind.FAILED,
                            failedCount = 1,
                        ),
                        loading = false,
                    ),
                    title = "민지",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onEventTap = {},
                    onRetryManualMemorySync = { retried = true },
                )
            }
        }

        composeRule.onNodeWithTag("person-detail-list")
            .performScrollToNode(hasText(string(R.string.person_detail_manual_memory_sync_failed_title)))
        composeRule.onNodeWithTag("person-detail-manual-memory-sync").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.person_detail_manual_memory_sync_retry)).performClick()

        composeRule.runOnIdle {
            assertEquals(true, retried)
        }
    }

    @Test
    fun `source event card shows source preview without extracted buckets`() {
        composeRule.setContent {
            BecalmTheme {
                Column {
                    SourceEventCardRow(
                        card = SourceEventCardProjection(
                            sourceEventKey = "raw:event-1",
                            sourceType = "gmail",
                            rawEventId = "event-1",
                            occurredAt = Instant.parse("2026-04-24T01:00:00Z"),
                            title = "메일",
                            snippet = null,
                            commitmentsExtractedCount = 1,
                            myActions = listOf(
                                PersonDetailCommitmentSummary(
                                    title = "제안서 보내기",
                                    itemType = CommitmentItemType.ACTION,
                                    direction = "give",
                                    status = "pending",
                                ),
                            ),
                        ),
                        onEventTap = {},
                    )
                    SourceEventCardRow(
                        card = SourceEventCardProjection(
                            sourceEventKey = "calendar:event-2",
                            sourceType = "google_calendar",
                            rawEventId = null,
                            occurredAt = Instant.parse("2026-04-24T02:00:00Z"),
                            title = "데모 미팅",
                            snippet = null,
                            commitmentsExtractedCount = 1,
                            schedules = listOf(
                                PersonDetailCommitmentSummary(
                                    title = "데모 미팅",
                                    itemType = CommitmentItemType.SCHEDULE,
                                    status = "confirmed",
                                ),
                            ),
                        ),
                        onEventTap = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithText(string(R.string.raw_event_commitments_extracted, 1)).assertCountEquals(2)
        composeRule.onAllNodesWithText(string(R.string.person_detail_bucket_my_actions)).assertCountEquals(0)
        composeRule.onAllNodesWithText(string(R.string.commitment_item_type_schedule)).assertCountEquals(0)
        composeRule.onAllNodesWithText("give").assertCountEquals(0)
        composeRule.onAllNodesWithText("pending").assertCountEquals(0)
        composeRule.onAllNodesWithText("confirmed").assertCountEquals(0)
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
