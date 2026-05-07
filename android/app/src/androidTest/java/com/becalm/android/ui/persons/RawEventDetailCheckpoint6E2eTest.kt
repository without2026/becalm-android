package com.becalm.android.ui.persons

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RawEventDetailCheckpoint6E2eTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun e2e_068_original_file_missing_shows_deleted_original_notice_without_crash() {
        composeTestRule.setContent {
            BecalmTheme {
                RawEventDetailContent(
                    state = RawEventDetailUiState(
                        eventId = "raw-deleted-original",
                        sourceType = SourceType.GMAIL,
                        eventTitle = "견적서 확인",
                        timestamp = Instant.parse("2026-05-07T03:00:00Z"),
                        snippet = "원문이 삭제된 메일",
                        archivedOriginal = ArchivedOriginalUi(
                            bodyText = null,
                            deletedFromDevice = true,
                            truncated = false,
                        ),
                        loading = false,
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("견적서 확인").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.raw_event_original_deleted_notice)).assertIsDisplayed()
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
