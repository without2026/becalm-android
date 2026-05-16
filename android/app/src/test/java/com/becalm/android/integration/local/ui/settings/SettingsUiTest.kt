package com.becalm.android.integration.local.ui.settings

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.becalm.android.R
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ProcessingPhase
import com.becalm.android.ui.settings.ProcessingStatusContent
import com.becalm.android.ui.settings.ProcessingStatusRow
import com.becalm.android.ui.settings.ProcessingStatusUiState
import com.becalm.android.ui.settings.PrivacyManagementScreenContent
import com.becalm.android.ui.settings.PrivacyManagementUiState
import com.becalm.android.ui.settings.SelfIdentityAnchorUi
import com.becalm.android.ui.settings.SettingsIdentityContent
import com.becalm.android.ui.settings.SettingsIdentityUiState
import com.becalm.android.ui.settings.SettingsScreenContent
import com.becalm.android.ui.settings.SettingsUiState
import com.becalm.android.ui.settings.SourceConnectionOwnershipUi
import com.becalm.android.ui.theme.BecalmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SettingsUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `settings content shows paused banner signout note and dispatches actions`() {
        var sourcesClicks = 0
        var processingClicks = 0
        var privacyClicks = 0
        var identityClicks = 0
        var wipeClicks = 0

        composeRule.setContent {
            BecalmTheme {
                SettingsScreenContent(
                    state = SettingsUiState(
                        loading = false,
                        userEmail = "user@example.com",
                        processingPaused = true,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onToggleNotifications = {},
                    onTogglePipa = {},
                    onIdentityClick = { identityClicks += 1 },
                    onSourcesClick = { sourcesClicks += 1 },
                    onProcessingStatusClick = { processingClicks += 1 },
                    onPrivacyClick = { privacyClicks += 1 },
                    onRequestSignOut = {},
                    onRequestWipe = { wipeClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.processing_paused_banner)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.settings_sign_out_pipa_note)).assertIsDisplayed()
        composeRule.onNodeWithTag("settings-identity-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-sources-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-processing-status-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-privacy-row").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithTag("settings-wipe-button").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(1, identityClicks)
            assertEquals(1, sourcesClicks)
            assertEquals(1, processingClicks)
            assertEquals(1, privacyClicks)
            assertEquals(1, wipeClicks)
        }
    }

    @Test
    fun `settings pipa toggle invokes callback`() {
        var pipaToggle: Boolean? = null

        composeRule.setContent {
            BecalmTheme {
                SettingsScreenContent(
                    state = SettingsUiState(
                        loading = false,
                        pipaConsentEnabled = false,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onToggleNotifications = {},
                    onTogglePipa = { pipaToggle = it },
                    onSourcesClick = {},
                    onPrivacyClick = {},
                    onRequestSignOut = {},
                    onRequestWipe = {},
                )
            }
        }

        composeRule.onNodeWithTag("settings-pipa-toggle").performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertEquals(true, pipaToggle)
        }
    }

    @Test
    fun `identity content exposes profile anchors and source ownership actions`() {
        var saveClicks = 0
        var addAnchorClicks = 0
        var archivedAnchor: String? = null
        var ownershipChange: Pair<String, String>? = null

        composeRule.setContent {
            BecalmTheme {
                SettingsIdentityContent(
                    state = SettingsIdentityUiState(
                        loading = false,
                        displayName = "민홍",
                        phone = "+821012345678",
                        anchors = listOf(
                            SelfIdentityAnchorUi(
                                id = "anchor-1",
                                type = "email",
                                value = "me@example.com",
                                status = "active",
                                trust = "user_confirmed",
                            ),
                            SelfIdentityAnchorUi(
                                id = "anchor-speaker",
                                type = "speaker_label",
                                value = "SPEAKER_01",
                                status = "active",
                                trust = "user_confirmed",
                                scope = "source_event",
                            ),
                        ),
                        connections = listOf(
                            SourceConnectionOwnershipUi(
                                id = "conn-1",
                                title = "Gmail",
                                accountLabel = "work@example.com",
                                ownership = "unknown",
                                status = "connected",
                            ),
                        ),
                    ),
                    onDisplayNameChange = {},
                    onPhoneChange = {},
                    onSaveProfile = { saveClicks += 1 },
                    onAnchorTypeChange = {},
                    onAnchorValueChange = {},
                    onAddAnchor = { addAnchorClicks += 1 },
                    onArchiveAnchor = { archivedAnchor = it },
                    onSetConnectionOwnership = { id, ownership -> ownershipChange = id to ownership },
                )
            }
        }

        composeRule.onNodeWithTag("settings-identity-save").performClick()
        composeRule.onNodeWithTag("settings-identity-list")
            .performScrollToNode(hasTestTag("settings-identity-anchor-add"))
        composeRule.onNodeWithText(string(R.string.settings_identity_anchor_alias)).assertExists()
        composeRule.onNodeWithTag("settings-identity-anchor-add").performClick()
        composeRule.onNodeWithTag("settings-identity-list")
            .performScrollToNode(hasTestTag("settings-identity-anchor-archive"))
        composeRule.onNodeWithTag("settings-identity-anchor-archive").performClick()
        composeRule.onAllNodesWithText("SPEAKER_01").assertCountEquals(0)
        composeRule.onNodeWithTag("settings-identity-list")
            .performScrollToNode(hasText("Gmail"))
        composeRule.onNodeWithText(string(R.string.settings_identity_connection_shared)).assertExists()
        composeRule.onNodeWithText(string(R.string.settings_identity_connection_delegated)).assertExists()
        composeRule.onNodeWithText(string(R.string.settings_identity_connection_unknown)).assertExists()
        composeRule.onNodeWithText(string(R.string.settings_identity_connection_self)).performClick()

        composeRule.runOnIdle {
            assertEquals(1, saveClicks)
            assertEquals(1, addAnchorClicks)
            assertEquals("anchor-1", archivedAnchor)
            assertEquals("conn-1" to "self", ownershipChange)
        }
    }

    @Test
    fun `privacy management content shows all pipa action cards and count subtitle`() {
        var exportClicks = 0
        var withdrawClicks = 0
        var pauseClicks = 0
        var archiveClicks = 0
        var deleteClicks = 0
        var activityLogClicks = 0

        composeRule.setContent {
            BecalmTheme {
                PrivacyManagementScreenContent(
                    state = PrivacyManagementUiState(
                        loading = false,
                        commitmentCount = 4,
                        enrichmentCount = 2,
                        emailCount = 9,
                        sourceArchiveCount = 2,
                        sourceArchiveBytes = 2048L,
                    ),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onExportClick = { exportClicks += 1 },
                    onOpenConsentWithdraw = { withdrawClicks += 1 },
                    onOpenProcessingPause = { pauseClicks += 1 },
                    onOpenSourceArchiveDelete = { archiveClicks += 1 },
                    onOpenAccountDeletion = { deleteClicks += 1 },
                    onOpenActivityLog = { activityLogClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.privacy_export_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_withdraw_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-pause-card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-source-archive-card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-delete-card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("privacy-activity-log-card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.privacy_delete_subtitle_fmt, 4, 2, 9)).assertIsDisplayed()

        composeRule.onNodeWithTag("privacy-export-card").performScrollTo().performClick()
        composeRule.onNodeWithTag("privacy-withdraw-card").performScrollTo().performClick()
        composeRule.onNodeWithTag("privacy-pause-card").performScrollTo().performClick()
        composeRule.onNodeWithTag("privacy-source-archive-card").performScrollTo().performClick()
        composeRule.onNodeWithTag("privacy-delete-card").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, exportClicks)
            assertEquals(1, withdrawClicks)
            assertEquals(1, pauseClicks)
            assertEquals(1, archiveClicks)
            assertEquals(1, deleteClicks)
            assertEquals(0, activityLogClicks)
        }
    }

    @Test
    fun `processing status content uses shared source labels and localized phase copy`() {
        composeRule.setContent {
            BecalmTheme {
                ProcessingStatusContent(
                    state = ProcessingStatusUiState(
                        rows = listOf(
                            ProcessingStatusRow(
                                sourceType = SourceType.VOICE,
                                phase = ProcessingPhase.SYNCED,
                                itemCount = 3,
                                message = null,
                                updatedAt = null,
                            ),
                            ProcessingStatusRow(
                                sourceType = "raw_source_type",
                                phase = ProcessingPhase.ERROR,
                                itemCount = 0,
                                message = "retry required",
                                updatedAt = null,
                            ),
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.raw_event_source_badge_voice)).assertIsDisplayed()
        composeRule.onNodeWithText(
            "${string(R.string.processing_phase_synced)} · ${string(R.string.processing_status_item_count_fmt, 3)}",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.raw_event_source_badge_unknown)).assertIsDisplayed()
        composeRule.onAllNodesWithText("raw_source_type").assertCountEquals(0)
    }

    @Test
    fun `processing status groups active action needed and quiet rows with summary`() {
        composeRule.setContent {
            BecalmTheme {
                ProcessingStatusContent(
                    state = ProcessingStatusUiState(
                        rows = listOf(
                            ProcessingStatusRow(
                                sourceType = SourceType.GMAIL,
                                phase = ProcessingPhase.GEMINI,
                                itemCount = 4,
                                message = null,
                                updatedAt = null,
                            ),
                            ProcessingStatusRow(
                                sourceType = SourceType.OUTLOOK_MAIL,
                                phase = ProcessingPhase.ERROR,
                                itemCount = 0,
                                message = "token expired",
                                updatedAt = null,
                            ),
                            ProcessingStatusRow(
                                sourceType = SourceType.GOOGLE_CALENDAR,
                                phase = ProcessingPhase.SYNCED,
                                itemCount = 2,
                                message = null,
                                updatedAt = null,
                            ),
                        ),
                    ),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.processing_status_summary_active_action_fmt, 1, 1)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.processing_status_group_active)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.processing_phase_memory), substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.processing_status_group_action_needed)).assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.processing_phase_attention_needed), substring = true)
            .assertCountEquals(3)
        composeRule.onNodeWithTag("processing-status-list")
            .performScrollToNode(hasText(string(R.string.processing_status_group_quiet)))
        composeRule.onNodeWithText(string(R.string.processing_status_group_quiet)).assertExists()
    }

    @Test
    fun `processing status empty state explains future incoming work`() {
        composeRule.setContent {
            BecalmTheme {
                ProcessingStatusContent(
                    state = ProcessingStatusUiState(rows = emptyList()),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText(string(R.string.processing_status_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.processing_status_empty_message)).assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId, *args)
}
