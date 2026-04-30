package com.becalm.android.ui.persons

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
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
                                personRef = "+821012345678",
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
        composeTestRule.onNodeWithText("오프라인", substring = true).assertIsDisplayed()
    }

    @Test
    fun persons_screen_person_card_shows_enrichment_meta_pending_count_and_snippet() {
        composeTestRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personRef = "+821012345678",
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

        composeTestRule.onNodeWithText("김철수 · ABC Corp · 팀장").assertIsDisplayed()
        composeTestRule.onNodeWithText("미이행 2건").assertIsDisplayed()
        composeTestRule.onNodeWithText("계약서 검토 요청").assertIsDisplayed()
    }

    @Test
    fun persons_search_input_routes_typed_query() {
        var typedQuery: String? = null

        composeTestRule.setContent {
            BecalmTheme {
                PersonsScreenContent(
                    state = PersonsUiState(
                        people = listOf(
                            PersonRow(
                                personRef = "kim@example.com",
                                displayName = "김철수",
                                lastInteractionAt = Instant.parse("2026-04-24T01:00:00Z"),
                                interactionCount = 1,
                            ),
                        ),
                        loading = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onQueryChange = { typedQuery = it },
                    onPersonClick = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("persons-search-input").performTextInput("김")

        composeTestRule.runOnIdle {
            assertEquals("김", typedQuery)
        }
    }

    private fun appString(resId: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId)
}
