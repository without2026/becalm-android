package com.becalm.android.integration.local.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.ui.sources.ContactsSourceDetailContent
import com.becalm.android.ui.sources.ContactsSourceDetailUiState
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
class ContactsSourceDetailUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `contacts source detail shows summary and permission recovery action`() {
        var actionClicks = 0

        composeRule.setContent {
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

        composeRule.onNodeWithText(string(R.string.contacts_source_connection_state_fmt, "DISCONNECTED")).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.contacts_source_enriched_count_fmt, 4)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_grant)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, actionClicks)
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
