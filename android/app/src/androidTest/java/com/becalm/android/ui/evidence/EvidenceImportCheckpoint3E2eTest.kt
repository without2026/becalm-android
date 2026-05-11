package com.becalm.android.ui.evidence

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EvidenceImportCheckpoint3E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_030_user_imports_meeting_audio_from_global_evidence_sheet() {
        var audioImports = 0

        renderSheet(onMeetingAudioImport = { audioImports += 1 })

        composeTestRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("evidence-import-meeting-audio").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, audioImports)
        }
    }

    @Test
    fun e2e_031_global_evidence_sheet_blocks_transcript_and_manual_text_paths() {
        renderSheet()

        composeTestRule.onAllNodesWithTag("evidence-import-meeting-transcript").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("evidence-import-manual-text").assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.evidence_import_meeting_audio)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.evidence_import_message_screenshot)).assertIsDisplayed()
    }

    @Test
    fun e2e_033_user_imports_messenger_screenshot_from_global_evidence_sheet() {
        var screenshotImports = 0

        renderSheet(
            onMessageScreenshotImport = { screenshotImports += 1 },
        )

        composeTestRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, screenshotImports)
        }
    }

    @Test
    fun e2e_037_evidence_import_sheet_has_visible_back_action() {
        var dismissed = 0

        renderSheet(onDismiss = { dismissed += 1 })

        composeTestRule.onNodeWithText(string(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("evidence-import-sheet-back")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun e2e_039_evidence_import_host_routes_each_path_to_the_matching_action() {
        var screenshotImports = 0
        var audioImports = 0

        composeTestRule.setContent {
            BecalmTheme {
                val controller = rememberEvidenceImportSheetController()
                EvidenceImportFloatingActionButton(onClick = controller::openSheet)
                EvidenceImportSheetHost(
                    controller = controller,
                    onMessageScreenshotImport = { screenshotImports += 1 },
                    onMeetingAudioImport = { audioImports += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onNodeWithTag("evidence-import-meeting-audio")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onAllNodesWithTag("evidence-import-meeting-transcript").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("evidence-import-manual-text").assertCountEquals(0)

        composeTestRule.runOnIdle {
            assertEquals(1, screenshotImports)
            assertEquals(1, audioImports)
        }
    }

    private fun renderSheet(
        onDismiss: () -> Unit = {},
        onMessageScreenshotImport: () -> Unit = {},
        onMeetingAudioImport: () -> Unit = {},
    ) {
        composeTestRule.setContent {
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
