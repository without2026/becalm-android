package com.becalm.android.ui.evidence

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
    fun e2e_031_user_imports_meeting_transcript_from_global_evidence_sheet() {
        var transcriptImports = 0

        renderSheet(onMeetingTranscriptImport = { transcriptImports += 1 })

        composeTestRule.onNodeWithTag("evidence-import-meeting-transcript").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, transcriptImports)
        }
    }

    @Test
    fun e2e_033_user_imports_messenger_screenshot_from_global_evidence_sheet() {
        var screenshotImports = 0

        renderSheet(onMessageScreenshotImport = { screenshotImports += 1 })

        composeTestRule.onNodeWithTag("evidence-import-message-screenshot").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, screenshotImports)
        }
    }

    @Test
    fun e2e_035_user_imports_manual_text_evidence() {
        var submittedText: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                ManualTextEvidenceDialog(
                    onDismiss = {},
                    onSubmit = { submittedText = it },
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.evidence_import_manual_text_title)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("evidence-import-manual-text-input")
            .performTextInput("내일까지 민준에게 견적서를 보내기")
        composeTestRule.onNodeWithTag("evidence-import-manual-text-save").performClick()

        composeTestRule.runOnIdle {
            assertEquals("내일까지 민준에게 견적서를 보내기", submittedText)
        }
    }

    @Test
    fun e2e_036_blank_manual_text_is_rejected_before_submit() {
        var submitCount = 0

        composeTestRule.setContent {
            BecalmTheme {
                ManualTextEvidenceDialog(
                    onDismiss = {},
                    onSubmit = { submitCount += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("evidence-import-manual-text-save").assertIsNotEnabled()

        composeTestRule.runOnIdle {
            assertEquals(0, submitCount)
        }
    }

    private fun renderSheet(
        onMessageScreenshotImport: () -> Unit = {},
        onMeetingAudioImport: () -> Unit = {},
        onMeetingTranscriptImport: () -> Unit = {},
        onManualTextImport: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            BecalmTheme {
                EvidenceImportSheet(
                    onDismiss = {},
                    onMessageScreenshotImport = onMessageScreenshotImport,
                    onMeetingAudioImport = onMeetingAudioImport,
                    onMeetingTranscriptImport = onMeetingTranscriptImport,
                    onManualTextImport = onManualTextImport,
                )
            }
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
