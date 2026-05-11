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
import com.becalm.android.ui.evidence.EvidenceImportSheet
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
