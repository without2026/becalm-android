package com.becalm.android.ui.sources

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
class SourcesListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sources_list_shows_contacts_pseudo_source_enrichment_summary() {
        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "contacts",
                                status = "CONNECTED",
                                lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                                lastError = null,
                                enrichedCount = 7,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("연락처").assertIsDisplayed()
        composeTestRule.onNodeWithText("7명", substring = true).assertIsDisplayed()
    }

    @Test
    fun sources_list_shows_empty_state() {
        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(items = emptyList()),
                    onBack = {},
                    onRowClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.sources_empty_title)).assertIsDisplayed()
    }

    @Test
    fun sources_list_dispatches_contacts_row_click() {
        var tappedSource: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                SourcesListScreenContent(
                    state = SourcesListUiState(
                        items = listOf(
                            SourceStatusRow(
                                sourceType = "contacts",
                                status = "CONNECTED",
                                lastSyncAt = Instant.parse("2026-04-24T01:00:00Z"),
                                lastError = null,
                                enrichedCount = 7,
                            ),
                        ),
                    ),
                    onBack = {},
                    onRowClick = { tappedSource = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("sources-row-contacts").performClick()

        composeTestRule.runOnIdle {
            assertEquals("contacts", tappedSource)
        }
    }

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)
}
