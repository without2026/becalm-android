package com.becalm.android.integration.local.ui.today

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.components.OverallSyncIndicator
import com.becalm.android.ui.components.SourceStatusChip
import com.becalm.android.ui.components.SourceStatusStrip
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ScheduleConflictReviewItem
import com.becalm.android.ui.today.TodayTimelineContent
import com.becalm.android.ui.today.TodayProcessingStatusUi
import com.becalm.android.ui.today.TodayUiState
import com.becalm.android.data.repository.ProcessingPhase
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

        composeRule.onNodeWithText(string(R.string.today_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.today_empty_message)).assertIsDisplayed()
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
}
