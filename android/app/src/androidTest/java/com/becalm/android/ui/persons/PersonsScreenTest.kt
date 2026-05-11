package com.becalm.android.ui.persons

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
class PersonsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun persons_screen_shows_offline_badge_and_unassigned_section_when_state_requires_them() {
        composeTestRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personId = "+821012345678",
                                displayName = "김철수",
                                lastInteractionAt = Instant.parse("2026-04-24T01:00:00Z"),
                                interactionCount = 2,
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
                        offlineLastSyncAt = Instant.parse("2026-04-24T00:30:00Z"),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText(appString(R.string.persons_unassigned_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("미분류 이벤트").assertIsDisplayed()
        composeTestRule.onNodeWithText(appString(R.string.persons_offline_badge_no_sync), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun persons_screen_person_card_keeps_work_context_out_of_contact_row() {
        composeTestRule.setContent {
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
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = {},
                    onPersonClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("김철수").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("김철수 · ABC Corp · 팀장").assertCountEquals(0)
        composeTestRule.onNodeWithText(appString(R.string.persons_pending_commitments_fmt, 2)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("계약서 검토 요청").assertCountEquals(0)
    }

    @Test
    fun persons_search_input_routes_typed_query() {
        var typedQuery: String? = null
        var screenshotImports = 0
        var query by mutableStateOf("")

        composeTestRule.setContent {
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
                        typedQuery = it
                        query = it
                    },
                    onPersonClick = {},
                    onMessageScreenshotImport = { screenshotImports += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag("persons-search-input").performTextReplacement("김")
        composeTestRule.onNodeWithTag("evidence-import-fab").performClick()
        composeTestRule.onNodeWithText(appString(R.string.evidence_import_sheet_title)).assertIsDisplayed()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("evidence-import-message-screenshot")
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle {
            assertEquals("김", typedQuery)
            assertEquals(1, screenshotImports)
        }
    }

    private fun appString(resId: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId)

    private fun appString(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId, *args)
}
