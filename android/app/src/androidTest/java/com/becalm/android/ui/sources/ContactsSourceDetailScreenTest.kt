package com.becalm.android.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.becalm.android.R
import com.becalm.android.ui.theme.BecalmTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactsSourceDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun contacts_source_detail_shows_summary_and_permission_recovery_action() {
        var actionClicks = 0

        composeTestRule.setContent {
            BecalmTheme {
                ContactsSourceDetailContent(
                    state = ContactsSourceDetailUiState(
                        connectionState = "DISCONNECTED",
                        enrichedCount = 4,
                        lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                        showPermissionRevokeButton = false,
                    ),
                    onPermissionAction = { actionClicks += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText(
            string(R.string.contacts_source_connection_state_fmt, "DISCONNECTED"),
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.contacts_source_enriched_count_fmt, 4))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.action_grant)).performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, actionClicks)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
