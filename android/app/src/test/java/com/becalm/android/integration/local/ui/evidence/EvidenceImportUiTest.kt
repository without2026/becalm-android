package com.becalm.android.integration.local.ui.evidence

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.ui.evidence.EvidenceImportSheet
import com.becalm.android.ui.evidence.EvidenceImportSheetHost
import com.becalm.android.ui.evidence.EvidenceImportUiState
import com.becalm.android.ui.evidence.MeetingSpeakerReviewUiState
import com.becalm.android.ui.evidence.rememberEvidenceImportSheetController
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class EvidenceImportUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `message screenshot action routes to screenshot import only`() {
        var screenshotImports = 0

        renderSheet(
            onMessageScreenshotImport = { screenshotImports += 1 },
        )

        composeRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, screenshotImports)
        }
    }

    @Test
    fun `sheet does not expose transcript or manual text paths`() {
        renderSheet()

        composeRule.onAllNodesWithTag("evidence-import-meeting-transcript").assertCountEquals(0)
        composeRule.onAllNodesWithTag("evidence-import-manual-text").assertCountEquals(0)
        composeRule.onNodeWithTag("evidence-import-message-screenshot").assertIsDisplayed()
        composeRule.onNodeWithTag("evidence-import-meeting-audio").assertIsDisplayed()
    }

    @Test
    fun `sheet exposes visible back action`() {
        var dismissed = 0

        renderSheet(onDismiss = { dismissed += 1 })

        composeRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("evidence-import-sheet-back")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun `meeting review asks for self speaker before final import`() {
        var selected: String? = null
        var confirmed = 0

        composeRule.setContent {
            BecalmTheme {
                EvidenceImportSheetHost(
                    controller = rememberEvidenceImportSheetController(),
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    state = EvidenceImportUiState(
                        meetingReview = MeetingSpeakerReviewUiState(
                            audioUri = android.net.Uri.parse("content://meeting/audio"),
                            speakerPreviewId = "preview-1",
                            speakers = listOf(
                                MeetingSpeakerPreviewDto("SPEAKER_01", listOf("제가 자료 보낼게요."), 0.0, 12.0),
                                MeetingSpeakerPreviewDto("SPEAKER_02", listOf("금요일까지 부탁드립니다."), 12.5, 8.0),
                            ),
                        ),
                    ),
                    onMeetingSelfSpeakerSelected = { selected = it },
                    onMeetingSpeakerReviewConfirmed = { confirmed += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.evidence_import_meeting_review_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("meeting-speaker-SPEAKER_02")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText(string(R.string.evidence_import_meeting_review_confirm))
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals("SPEAKER_02", selected)
            assertEquals(1, confirmed)
        }
    }

    @Test
    fun `import host renders persistent processing or review entry after upload`() {
        var reviewClicks = 0
        composeRule.setContent {
            BecalmTheme {
                EvidenceImportSheetHost(
                    controller = rememberEvidenceImportSheetController(),
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    state = EvidenceImportUiState(
                        statusMessage = com.becalm.android.ui.components.UiMessage.resource(
                            R.string.evidence_import_status_review_required,
                        ),
                    ),
                    onReviewRequiredClick = { reviewClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("evidence-import-status").assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.evidence_import_status_review_required)).assertIsDisplayed()
        composeRule.onNodeWithTag("evidence-import-review-action")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertEquals(1, reviewClicks)
        }
    }

    @Test
    fun `review required banner CTA is visible and clickable from persistent state`() {
        var navigatedToReview = false
        composeRule.setContent {
            BecalmTheme {
                EvidenceImportSheetHost(
                    controller = rememberEvidenceImportSheetController(),
                    onMessageScreenshotImport = {},
                    onMeetingAudioImport = {},
                    state = EvidenceImportUiState(
                        statusMessage = com.becalm.android.ui.components.UiMessage.resource(
                            R.string.evidence_import_status_review_required,
                        ),
                    ),
                    onReviewRequiredClick = { navigatedToReview = true },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.evidence_import_review_action)).assertIsDisplayed()
        composeRule.onNodeWithTag("evidence-import-review-action")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(true, navigatedToReview)
        }
    }

    private fun renderSheet(
        onDismiss: () -> Unit = {},
        onMessageScreenshotImport: () -> Unit = {},
        onMeetingAudioImport: () -> Unit = {},
    ) {
        composeRule.setContent {
            BecalmTheme {
                EvidenceImportSheet(
                    onDismiss = onDismiss,
                    onMessageScreenshotImport = onMessageScreenshotImport,
                    onMeetingAudioImport = onMeetingAudioImport,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
