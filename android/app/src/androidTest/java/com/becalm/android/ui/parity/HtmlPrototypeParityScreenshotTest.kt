@file:OptIn(androidx.compose.material.ExperimentalMaterialApi::class)

package com.becalm.android.ui.parity

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import com.becalm.android.R
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.ScheduleEventLinkResolutionChoice
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.domain.commitment.CommitmentState
import com.becalm.android.domain.commitment.ManualCommitmentDraft
import com.becalm.android.ui.actions.PersonActionDraftDialog
import com.becalm.android.ui.actions.PersonActionEvidenceDetailUi
import com.becalm.android.ui.actions.PersonActionEvidenceDialog
import com.becalm.android.ui.actions.PersonActionEvidenceUi
import com.becalm.android.ui.actions.PersonActionItemUi
import com.becalm.android.ui.actions.PersonActionProviderWriteUi
import com.becalm.android.ui.commitments.CommitmentDetailActionState
import com.becalm.android.ui.commitments.CommitmentDetailSheet
import com.becalm.android.ui.commitments.CommitmentFilter
import com.becalm.android.ui.commitments.CommitmentHistoryPresentation
import com.becalm.android.ui.commitments.CommitmentManagementScreenContent
import com.becalm.android.ui.commitments.CommitmentRow
import com.becalm.android.ui.commitments.CommitmentSectionUiState
import com.becalm.android.ui.commitments.CommitmentSheetAction
import com.becalm.android.ui.commitments.CommitmentSourcePresentation
import com.becalm.android.ui.commitments.CommitmentUiState
import com.becalm.android.ui.commitments.CreateSheetContent
import com.becalm.android.ui.commitments.CreateUiState
import com.becalm.android.ui.commitments.DetailUiState
import com.becalm.android.ui.components.BecalmBottomNavigation
import com.becalm.android.ui.components.SourceSyncStatus
import com.becalm.android.ui.evidence.EvidenceImportSheet
import com.becalm.android.ui.main.MainTabHeaderState
import com.becalm.android.ui.main.OverallSyncState
import com.becalm.android.ui.main.SourceStatusUi
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.ui.onboarding.GmailActivationPreviewContent
import com.becalm.android.ui.onboarding.GmailActivationPreviewStatus
import com.becalm.android.ui.onboarding.GmailActivationPreviewUi
import com.becalm.android.ui.onboarding.GmailActivationPreviewUiState
import com.becalm.android.ui.onboarding.OnboardingIntroContent
import com.becalm.android.ui.onboarding.OnboardingCalendarPreviewItemUi
import com.becalm.android.ui.onboarding.OnboardingCalendarPreviewUi
import com.becalm.android.ui.onboarding.OnboardingContactsPreviewUi
import com.becalm.android.ui.onboarding.OnboardingSelfIdentityUi
import com.becalm.android.ui.onboarding.OnboardingSetupItem
import com.becalm.android.ui.onboarding.OnboardingSetupItemUi
import com.becalm.android.ui.onboarding.OnboardingSourceProvider
import com.becalm.android.ui.onboarding.SourceConnectionCategory
import com.becalm.android.ui.onboarding.SourceConnectionItemUi
import com.becalm.android.ui.onboarding.SourceConnectionState
import com.becalm.android.ui.onboarding.SourceConnectionsContent
import com.becalm.android.ui.persons.PersonActionFeedStatusKind
import com.becalm.android.ui.persons.PersonActionFeedStatusUi
import com.becalm.android.ui.persons.PersonActionDraftSheetStatus
import com.becalm.android.ui.persons.PersonActionDraftSheetUiState
import com.becalm.android.ui.persons.PersonActionSummary
import com.becalm.android.ui.persons.PersonDetailScreenContent
import com.becalm.android.ui.persons.PersonDetailUiState
import com.becalm.android.ui.persons.PersonRow
import com.becalm.android.ui.persons.PersonsScreenContent
import com.becalm.android.ui.persons.PersonsUiState
import com.becalm.android.ui.persons.ArchivedOriginalUi
import com.becalm.android.ui.persons.EmailBodyUi
import com.becalm.android.ui.persons.RawEventCommitmentSummary
import com.becalm.android.ui.persons.RawEventDetailContent
import com.becalm.android.ui.persons.RawEventDetailUiState
import com.becalm.android.ui.persons.SourceEventCardProjection
import com.becalm.android.ui.persons.UnassignedEventSummary
import com.becalm.android.ui.theme.BecalmTheme
import com.becalm.android.ui.today.ScheduleConflictReviewItem
import com.becalm.android.ui.today.ScheduleRangeFilter
import com.becalm.android.ui.today.TodayTimelineContent
import com.becalm.android.ui.today.TodayUiState
import com.becalm.android.ui.today.TimelineItem
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.Instant
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HtmlPrototypeParityScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun capturePersonsActionFirstListWithSourceStatus() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            people = listOf(
                                personRow(
                                    personId = "person-urgent",
                                    name = "김민홍",
                                    company = "거래처",
                                    action = PersonActionSummary(
                                        id = "act-urgent",
                                        title = "수정 계약서 회신",
                                        primaryVerb = "답장",
                                        shortReason = "오늘까지 보내기로 한 약속입니다.",
                                        actionKind = "reply",
                                        dueAt = null,
                                        dueHint = "오늘까지",
                                        urgencyScore = 92.0,
                                    ),
                                ),
                                personRow(
                                    personId = "person-week",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-week",
                                        title = "분기 미팅 날짜 제안하기",
                                        primaryVerb = "제안",
                                        shortReason = "이번 주 안에 후보 시간을 보내면 됩니다.",
                                        actionKind = "schedule",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 25.0,
                                    ),
                                ),
                                staleRecallPersonRow(),
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "needs_reauth",
                                    lastSyncedAt = null,
                                ),
                                SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                    status = SourceSyncStatus.Connected,
                                    errorMessage = null,
                                    lastSyncedAt = Instant.parse("2026-06-04T00:55:00Z"),
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                    MainTabBottomNavigationOverlay(
                        currentRoute = BecalmRoute.Persons.path,
                        personActionBadgeCount = 1,
                    )
                }
            }
        }

        captureScreenshot("persons-action-first-source-status.png")
    }

    @Test
    fun capturePersonsActionFeedDegradedStatus() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            people = listOf(
                                personRow(
                                    personId = "person-urgent",
                                    name = "김민홍",
                                    company = "거래처",
                                    action = PersonActionSummary(
                                        id = "act-urgent",
                                        title = "수정 계약서 회신",
                                        primaryVerb = "답장",
                                        shortReason = "오늘까지 보내기로 한 약속입니다.",
                                        actionKind = "reply",
                                        dueAt = null,
                                        dueHint = "오늘까지",
                                        urgencyScore = 92.0,
                                    ),
                                ),
                                personRow(
                                    personId = "person-week",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-week",
                                        title = "분기 미팅 날짜 제안하기",
                                        primaryVerb = "제안",
                                        shortReason = "이번 주 안에 후보 시간을 보내면 됩니다.",
                                        actionKind = "schedule",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 25.0,
                                    ),
                                ),
                                staleRecallPersonRow(),
                            ),
                            actionFeedStatus = PersonActionFeedStatusUi(
                                kind = PersonActionFeedStatusKind.QUOTA_DELAY,
                                backlogLagSeconds = 600,
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Connected,
                                    errorMessage = null,
                                    lastSyncedAt = Instant.parse("2026-06-04T00:55:00Z"),
                                ),
                            ),
                            overall = OverallSyncState.Synced(Instant.parse("2026-06-04T00:55:00Z")),
                        ),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                }
            }
        }

        captureScreenshot("persons-action-feed-degraded-status.png")
    }

    @Test
    fun capturePersonsSourceAndProcessingWarnings() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            people = listOf(
                                personRow(
                                    personId = "person-urgent",
                                    name = "김민홍",
                                    company = "거래처",
                                    action = PersonActionSummary(
                                        id = "act-urgent",
                                        title = "수정 계약서 회신",
                                        primaryVerb = "답장",
                                        shortReason = "오늘까지 보내기로 한 약속입니다.",
                                        actionKind = "reply",
                                        dueAt = null,
                                        dueHint = "오늘까지",
                                        urgencyScore = 92.0,
                                    ),
                                ),
                                personRow(
                                    personId = "person-week",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-week",
                                        title = "분기 미팅 날짜 제안하기",
                                        primaryVerb = "제안",
                                        shortReason = "이번 주 안에 후보 시간을 보내면 됩니다.",
                                        actionKind = "schedule",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 25.0,
                                    ),
                                ),
                                staleRecallPersonRow(),
                            ),
                            actionFeedStatus = PersonActionFeedStatusUi(
                                kind = PersonActionFeedStatusKind.DEGRADED,
                                backlogLagSeconds = 600,
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "needs_reauth",
                                    lastSyncedAt = null,
                                ),
                                SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                    status = SourceSyncStatus.Connected,
                                    errorMessage = null,
                                    lastSyncedAt = Instant.parse("2026-06-04T00:55:00Z"),
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                    MainTabBottomNavigationOverlay(
                        currentRoute = BecalmRoute.Persons.path,
                        personActionBadgeCount = 1,
                    )
                }
            }
        }

        captureScreenshot("persons-source-processing-status.png")
    }

    @Test
    fun capturePersonsMatchingSourceAndProcessingWarnings() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            people = listOf(
                                personRow(
                                    personId = "person-urgent",
                                    name = "김민홍",
                                    company = "거래처",
                                    action = PersonActionSummary(
                                        id = "act-urgent",
                                        title = "수정 계약서 회신",
                                        primaryVerb = "답장",
                                        shortReason = "오늘까지 보내기로 한 약속입니다.",
                                        actionKind = "reply",
                                        dueAt = null,
                                        dueHint = "오늘까지",
                                        urgencyScore = 92.0,
                                    ),
                                ),
                                personRow(
                                    personId = "person-week",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-week",
                                        title = "분기 미팅 날짜 제안하기",
                                        primaryVerb = "제안",
                                        shortReason = "이번 주 안에 후보 시간을 보내면 됩니다.",
                                        actionKind = "schedule",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 25.0,
                                    ),
                                ),
                                staleRecallPersonRow(),
                            ),
                            unassignedEvents = listOf(
                                UnassignedEventSummary(
                                    id = "event-unassigned",
                                    sourceType = SourceType.GMAIL,
                                    title = "상대 확인 필요",
                                    timestamp = Instant.parse("2026-06-04T01:05:00Z"),
                                ),
                            ),
                            actionFeedStatus = PersonActionFeedStatusUi(
                                kind = PersonActionFeedStatusKind.DEGRADED,
                                backlogLagSeconds = 600,
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "needs_reauth",
                                    lastSyncedAt = null,
                                ),
                                SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                    status = SourceSyncStatus.Connected,
                                    errorMessage = null,
                                    lastSyncedAt = Instant.parse("2026-06-04T00:55:00Z"),
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                    MainTabBottomNavigationOverlay(
                        currentRoute = BecalmRoute.Persons.path,
                        personActionBadgeCount = 1,
                    )
                }
            }
        }

        captureScreenshot("persons-matching-source-processing-status.png")
    }

    @Test
    fun capturePersonsMultipleSourceWarnings() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            people = listOf(
                                personRow(
                                    personId = "person-urgent",
                                    name = "김민홍",
                                    company = "거래처",
                                    action = PersonActionSummary(
                                        id = "act-urgent",
                                        title = "수정 계약서 회신",
                                        primaryVerb = "답장",
                                        shortReason = "오늘까지 보내기로 한 약속입니다.",
                                        actionKind = "reply",
                                        dueAt = null,
                                        dueHint = "오늘까지",
                                        urgencyScore = 92.0,
                                    ),
                                ),
                                personRow(
                                    personId = "person-week",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-week",
                                        title = "분기 미팅 날짜 제안하기",
                                        primaryVerb = "제안",
                                        shortReason = "이번 주 안에 후보 시간을 보내면 됩니다.",
                                        actionKind = "schedule",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 25.0,
                                    ),
                                ),
                                staleRecallPersonRow(),
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "needs_reauth",
                                    lastSyncedAt = null,
                                ),
                                SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                    status = SourceSyncStatus.Disconnected,
                                    errorMessage = null,
                                    lastSyncedAt = null,
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                    MainTabBottomNavigationOverlay(
                        currentRoute = BecalmRoute.Persons.path,
                        personActionBadgeCount = 1,
                    )
                }
            }
        }

        captureScreenshot("persons-multiple-source-status.png")
    }

    @Test
    fun capturePersonsRoleSearchTaxResult() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            query = "세무사",
                            people = listOf(
                                personRow(
                                    personId = "person-tax",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-tax",
                                        title = "분기 신고 자료 확인",
                                        primaryVerb = "확인",
                                        shortReason = "세무사에게 이번 주 안에 자료를 보내야 합니다.",
                                        actionKind = "follow_up",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 61.0,
                                    ),
                                ),
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                    MainTabBottomNavigationOverlay(
                        currentRoute = BecalmRoute.Persons.path,
                        personActionBadgeCount = 1,
                    )
                }
            }
        }

        captureScreenshot("persons-role-search-tax.png")
    }

    @Test
    fun capturePersonsRoleSearchTaxResultWithSourceStatus() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonsScreenContent(
                        state = PersonsUiState(
                            query = "세무사",
                            people = listOf(
                                personRow(
                                    personId = "person-tax",
                                    name = "이준호",
                                    company = "세무사",
                                    action = PersonActionSummary(
                                        id = "act-tax",
                                        title = "분기 미팅 날짜 제안하기",
                                        primaryVerb = "제안",
                                        shortReason = "세무사에게 이번 주 안에 후보 시간을 보내야 합니다.",
                                        actionKind = "follow_up",
                                        dueAt = null,
                                        dueHint = "이번 주",
                                        urgencyScore = 61.0,
                                    ),
                                ),
                            ),
                            loading = false,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "needs_reauth",
                                    lastSyncedAt = null,
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                        onQueryChange = {},
                        onPersonClick = {},
                    )
                    MainTabBottomNavigationOverlay(
                        currentRoute = BecalmRoute.Persons.path,
                        personActionBadgeCount = 1,
                    )
                }
            }
        }

        captureScreenshot("persons-role-search-tax-source-status.png")
    }

    @Test
    fun capturePersonDetailStaleRelationshipRecall() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonDetailScreenContent(
                        state = PersonDetailUiState(
                            personId = "person-stale",
                            displayName = "최민서",
                            companyName = "파트너사",
                            eventCount = 1,
                            emailInteractionCount = 1,
                            sourceEventCards = listOf(
                                SourceEventCardProjection(
                                    sourceEventKey = "raw:mail-1",
                                    sourceType = SourceType.GMAIL,
                                    rawEventId = "mail-1",
                                    occurredAt = Instant.parse("2025-05-10T01:00:00Z"),
                                    title = "견적 재검토 요청",
                                    snippet = "진행 여부 회신을 기다리던 대화",
                                ),
                            ),
                            loading = false,
                        ),
                        title = "최민서",
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onEventTap = {},
                    )
                }
            }
        }

        captureScreenshot("person-detail-stale-recall.png")
    }

    @Test
    fun capturePersonDetailPrimaryActionDensity() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonDetailScreenContent(
                        state = PersonDetailUiState(
                            personId = "person-kim",
                            displayName = "김민홍",
                            companyName = "거래처",
                            jobTitle = "대표",
                            eventCount = 4,
                            emailInteractionCount = 3,
                            pendingCommitmentCount = 3,
                            channelSources = setOf(SourceType.GMAIL, SourceType.GOOGLE_CALENDAR),
                            topActions = listOf(
                                personAction(
                                    id = "action-kim-reply",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    actionKind = "reply",
                                    commitmentId = "commit-kim-reply",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "오늘까지 보내기로 한 Give 약속입니다.",
                                    evidence = PersonActionEvidenceUi(
                                        kind = "source_event",
                                        id = "mail-kim-1",
                                        sourceRef = "raw:mail-kim-1",
                                        occurredAt = Instant.parse("2026-06-04T01:00:00Z"),
                                        label = "Gmail",
                                        quote = "오늘 안에 수정 계약서 초안을 보내주시면 검토하겠습니다.",
                                    ),
                                ),
                                personAction(
                                    id = "action-kim-evidence",
                                    title = "회의 자료 다시 보내기",
                                    primaryVerb = "보내기",
                                    actionKind = "follow_up",
                                    commitmentId = "commit-kim-file",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "상대가 자료를 기다리고 있습니다.",
                                ),
                                personAction(
                                    id = "action-kim-schedule",
                                    title = "다음 미팅 날짜 확정",
                                    primaryVerb = "확정",
                                    actionKind = "schedule",
                                    commitmentId = "commit-kim-schedule",
                                    sourceType = SourceType.GOOGLE_CALENDAR,
                                    shortReason = "캘린더와 메일 일정이 아직 다릅니다.",
                                ),
                            ),
                            sourceEventCards = listOf(
                                SourceEventCardProjection(
                                    sourceEventKey = "raw:mail-kim-1",
                                    sourceType = SourceType.GMAIL,
                                    rawEventId = "mail-kim-1",
                                    occurredAt = Instant.parse("2026-06-04T01:00:00Z"),
                                    title = "Re: 수정 계약서 회신",
                                    snippet = "오늘 안에 수정 계약서 초안을 보내주시면 검토하겠습니다.",
                                    commitmentsExtractedCount = 2,
                                ),
                            ),
                            loading = false,
                        ),
                        title = "김민홍",
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onEventTap = {},
                    )
                }
            }
        }

        captureScreenshot("person-detail-primary-action-density.png")
    }

    @Test
    fun capturePersonActionEvidenceWhyAndOriginalSheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonDetailScreenContent(
                        state = PersonDetailUiState(
                            personId = "person-kim",
                            displayName = "김도현",
                            companyName = "거래처",
                            jobTitle = "대표",
                            eventCount = 4,
                            emailInteractionCount = 3,
                            pendingCommitmentCount = 3,
                            channelSources = setOf(SourceType.GMAIL, SourceType.GOOGLE_CALENDAR),
                            topActions = listOf(
                                personAction(
                                    id = "action-kim-reply",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    actionKind = "reply",
                                    commitmentId = "commit-kim-reply",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "오늘까지 보내기로 한 Give 약속입니다.",
                                ),
                            ),
                            sourceEventCards = listOf(
                                SourceEventCardProjection(
                                    sourceEventKey = "raw:call-kim-1",
                                    sourceType = SourceType.VOICE,
                                    rawEventId = "call-kim-1",
                                    occurredAt = Instant.parse("2026-06-01T09:00:00Z"),
                                    title = "수정 계약서 조항 논의",
                                    snippet = "검수 완료 기준과 다음 회신 시점을 논의했습니다.",
                                    commitmentsExtractedCount = 2,
                                ),
                            ),
                            loading = false,
                        ),
                        title = "김도현",
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onEventTap = {},
                    )
                    PersonActionEvidenceDialog(
                        detail = evidenceDetail(
                            actionItemId = "action-kim-reply",
                            label = "통화 · 6/1",
                            title = "수정 계약서 단가 조항 논의",
                            why = "이 통화에서 Give 약속(계약서 회신)과 다음 일정(6/9 2차 미팅)이 추출됐어요.",
                            original = "[6/1 통화 전사 · 18분 12초 · 자동 녹음]\n\n" +
                                "(00:31) 나: 안녕하세요 대표님. 견적서 검토 감사합니다. 어떻게 보셨어요?\n\n" +
                                "(00:42) 김도현 대표: 전체 범위랑 일정은 내부에서도 괜찮다는 분위기예요. 다만 단가 조항 한 군데가 걸려요.\n\n" +
                                "(01:20) 김도현 대표: 3항 정산 기준이 착수 시점으로 돼 있는데, 저희는 검수 완료 시점이어야 결재가 돌아요.\n\n" +
                                "(02:05) 나: 검수 완료 기준으로 바꾸는 건 문제없습니다. 대신 검수 기한을 영업일 5일로 명시할게요.\n\n" +
                                "(02:38) 김도현 대표: 좋습니다. 그 정도면 합리적이네요.\n\n" +
                                "(03:22) 김도현 대표: 단가 조항만 한 줄 정리해서 다시 주시면 바로 내부 검토 돌릴게요.\n\n" +
                                "(03:40) 나: 네, 이번 주 안에 수정본 보내드리겠습니다.",
                            sourceType = SourceType.VOICE,
                            metadata = "통화 · 자동 녹음 · 18분 12초",
                            originalIsLocal = true,
                        ),
                        onDismiss = {},
                    )
                }
            }
        }

        captureScreenshot("person-action-evidence-sheet.png", useDeviceScreenshot = true)
    }

    @Test
    fun capturePersonActionDraftReplySheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonDetailScreenContent(
                        state = PersonDetailUiState(
                            personId = "person-kim",
                            displayName = "김도현",
                            companyName = "파트너",
                            jobTitle = "대표",
                            eventCount = 4,
                            emailInteractionCount = 3,
                            pendingCommitmentCount = 3,
                            channelSources = setOf(SourceType.GMAIL, SourceType.GOOGLE_CALENDAR),
                            topActions = listOf(
                                personAction(
                                    id = "action-kim-reply",
                                    title = "수정 계약서 회신",
                                    primaryVerb = "답장",
                                    actionKind = "reply",
                                    commitmentId = "commit-kim-reply",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "오늘까지 보내기로 한 Give 약속입니다.",
                                ),
                            ),
                            sourceEventCards = listOf(
                                SourceEventCardProjection(
                                    sourceEventKey = "raw:mail-kim-1",
                                    sourceType = SourceType.GMAIL,
                                    rawEventId = "mail-kim-1",
                                    occurredAt = Instant.parse("2026-06-01T09:00:00Z"),
                                    title = "Re: 수정 계약서 회신",
                                    snippet = "수정 계약서 단가 조항을 검수 완료 기준으로 반영해 달라고 요청했습니다.",
                                    commitmentsExtractedCount = 2,
                                ),
                            ),
                            loading = false,
                        ),
                        title = "김도현",
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onEventTap = {},
                    )
                    PersonActionDraftDialog(
                        state = PersonActionDraftSheetUiState(
                            actionItemId = "action-kim-reply",
                            actionTitle = "수정 계약서 회신",
                            status = PersonActionDraftSheetStatus.READY,
                            draftKind = "reply",
                            draftId = "draft-kim-reply",
                            subject = "Re: 수정 계약서 회신",
                            body = "김 대표님, 안녕하세요.\n\n" +
                                "지난 통화에서 말씀해주신 단가 조항을 반영해 수정 계약서를 첨부드립니다. " +
                                "범위표 기준으로 한 줄 정리했고, 검토에 도움이 되도록 변경점만 따로 표시해두었습니다.\n\n" +
                                "다음 주 화요일 3시 2차 미팅 때 최종본으로 정리하겠습니다.",
                            recipientLabel = "김도현 <dh.kim@partner.co.kr>",
                            provenanceLabels = listOf("통화 · 6/1 맥락", "말투 반영", "근거 2개"),
                            requiresUserReview = true,
                            generatedAt = Instant.parse("2026-06-04T01:00:00Z"),
                        ),
                        onSubjectChange = {},
                        onBodyChange = {},
                        onRetry = {},
                        onOpenEvidence = {},
                        onDismiss = {},
                    )
                }
            }
        }

        captureScreenshot("person-action-draft-reply-sheet.png", useDeviceScreenshot = true)
    }

    @Test
    fun capturePersonReconnectDraftSheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonDetailScreenContent(
                        state = PersonDetailUiState(
                            personId = "person-choi",
                            displayName = "최민서",
                            companyName = "클라이언트",
                            jobTitle = null,
                            eventCount = 2,
                            emailInteractionCount = 1,
                            callInteractionCount = 1,
                            pendingCommitmentCount = 1,
                            channelSources = setOf(SourceType.GMAIL, SourceType.VOICE),
                            topActions = listOf(
                                personAction(
                                    id = "action-choi-reconnect",
                                    title = "오랜만의 안부",
                                    primaryVerb = "초안",
                                    actionKind = "reconnect_person",
                                    commitmentId = "commit-choi-reconnect",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "오래된 대화 이후 후속 확인이 필요합니다.",
                                ),
                            ),
                            sourceEventCards = listOf(
                                SourceEventCardProjection(
                                    sourceEventKey = "raw:mail-choi-1",
                                    sourceType = SourceType.GMAIL,
                                    rawEventId = "mail-choi-1",
                                    occurredAt = Instant.parse("2026-05-28T02:00:00Z"),
                                    title = "수정 견적 내부 검토",
                                    snippet = "수정 견적 내부 검토가 진행 중이며 안부 확인이 필요합니다.",
                                    commitmentsExtractedCount = 1,
                                ),
                            ),
                            loading = false,
                        ),
                        title = "최민서",
                        snackbarHostState = SnackbarHostState(),
                        onBack = {},
                        onEventTap = {},
                    )
                    PersonActionDraftDialog(
                        state = PersonActionDraftSheetUiState(
                            actionItemId = "action-choi-reconnect",
                            actionTitle = "오랜만의 안부",
                            status = PersonActionDraftSheetStatus.READY,
                            draftKind = "reconnect_person",
                            draftId = "draft-choi-reconnect",
                            subject = "오랜만의 안부",
                            body = "민서님, 안녕하세요. 한동안 연락이 뜸했네요.\n\n" +
                                "지난번 보내드린 수정 견적 내부 검토는 잘 진행되고 계신지 궁금해 짧게 안부 전합니다. " +
                                "4월에 말씀 주셨던 하반기 예산 승인 방향도 슬슬 같이 보면 좋을 시점인 것 같아요.\n\n" +
                                "편하신 때 15분만 통화 어떠세요? 부담 없이 진행 상황만 맞춰보면 좋겠습니다.",
                            recipientLabel = "최민서 <ms.choi@client.com>",
                            provenanceLabels = listOf("통화 · 6/1 맥락", "말투 반영", "근거 2개"),
                            requiresUserReview = true,
                            generatedAt = Instant.parse("2026-06-04T01:00:00Z"),
                        ),
                        onSubjectChange = {},
                        onBodyChange = {},
                        onRetry = {},
                        onOpenEvidence = {},
                        onDismiss = {},
                    )
                }
            }
        }

        captureScreenshot("person-reconnect-draft-sheet.png", useDeviceScreenshot = true)
    }

    @Test
    fun captureRawEventDetailOriginalContext() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    RawEventDetailContent(
                        state = RawEventDetailUiState(
                            eventId = "mail-raw-1",
                            sourceType = SourceType.GMAIL,
                            eventTitle = "Re: 수정 계약서 회신",
                            timestamp = Instant.parse("2026-06-01T09:00:00Z"),
                            snippet = "오늘 안에 수정 계약서 초안을 보내주시면 검토 후 바로 회신드리겠습니다.",
                            extractedCommitments = listOf(
                                RawEventCommitmentSummary(
                                    id = "commit-give",
                                    title = "수정 계약서 초안 보내기",
                                    itemType = "action",
                                    direction = "give",
                                    status = "pending",
                                    quote = "오늘 안에 수정 계약서 초안을 보내주시면 검토 후 바로 회신드리겠습니다.",
                                ),
                                RawEventCommitmentSummary(
                                    id = "commit-take",
                                    title = "최종본 회신 받기",
                                    itemType = "action",
                                    direction = "take",
                                    status = "pending",
                                    quote = "검토 후 바로 회신드리겠습니다.",
                                ),
                            ),
                            emailBody = EmailBodyUi(
                                bodyPlain = "김민홍 대표님,\n\n오늘 안에 수정 계약서 초안을 보내겠습니다. 검토 후 회신 부탁드립니다.",
                                bodyHtml = null,
                            ),
                            archivedOriginal = ArchivedOriginalUi(
                                bodyText = "김민홍 대표님,\n\n오늘 안에 수정 계약서 초안을 보내겠습니다. 검토 후 회신 부탁드립니다.",
                                deletedFromDevice = false,
                                truncated = false,
                            ),
                            attachmentCount = 1,
                            commitmentsExtractedCount = 2,
                            syncStatus = "synced",
                            loading = false,
                        ),
                    )
                }
            }
        }

        captureScreenshot("raw-event-detail-original-context.png")
    }

    @Test
    fun captureScheduleMissingCalendarActions() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    TodayTimelineContent(
                        state = TodayUiState(
                            loading = false,
                            today = kotlinx.datetime.LocalDate(2026, 6, 4),
                            scheduleRangeFilter = ScheduleRangeFilter.NEXT_7_DAYS,
                            scheduleActions = listOf(
                                personAction(
                                    id = "schedule-opinion-deadline",
                                    title = "박서연 변호사 의견서 제출 - 6/5",
                                    primaryVerb = "확인",
                                    actionKind = "add_to_calendar",
                                    commitmentId = "commit-opinion-deadline",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "5/30 메일에 기한 명시 · 미등록",
                                    evidence = PersonActionEvidenceUi(
                                        kind = "source_event",
                                        id = "source-opinion-deadline",
                                        sourceRef = "gmail-thread-opinion",
                                        occurredAt = Instant.parse("2026-06-03T10:00:00Z"),
                                        label = "Gmail",
                                        quote = "의견서는 6월 5일까지 제출하면 됩니다.",
                                    ),
                                    providerWrite = readyCalendarWrite("schedule-link-opinion"),
                                ),
                                personAction(
                                    id = "schedule-contract-reply",
                                    title = "김도현 대표 계약서 회신 마감",
                                    primaryVerb = "확인",
                                    actionKind = "add_to_calendar",
                                    commitmentId = "commit-contract-reply",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "6/1 통화 약속 · 미등록",
                                    evidence = PersonActionEvidenceUi(
                                        kind = "source_event",
                                        id = "source-contract-reply",
                                        sourceRef = "gmail-thread-contract",
                                        occurredAt = Instant.parse("2026-06-03T10:30:00Z"),
                                        label = "Gmail",
                                        quote = "계약서 회신은 오늘 안에 마무리하겠습니다.",
                                    ),
                                    providerWrite = readyCalendarWrite("schedule-link-contract"),
                                ),
                            ),
                            timeline = listOf(
                                TimelineItem.Meeting(
                                    id = "calendar-investor-meeting",
                                    sourceType = SourceType.GOOGLE_CALENDAR,
                                    sourceRef = "calendar-investor-meeting",
                                    title = "투자자 미팅",
                                    attendeesRaw = "최신 IR 자료 확인",
                                    status = "confirmed",
                                    sortKey = Instant.parse("2026-06-04T06:00:00Z"),
                                ),
                            ),
                        ),
                        onOpenSettings = {},
                        onPullRefresh = {},
                    )
                    MainTabBottomNavigationOverlay(currentRoute = BecalmRoute.Today.path)
                }
            }
        }

        captureScreenshot("schedule-missing-calendar-actions.png")
    }

    @Test
    fun captureScheduleCandidatesAndConflictReview() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    TodayTimelineContent(
                        state = TodayUiState(
                            loading = false,
                            scheduleRangeFilter = ScheduleRangeFilter.NEXT_7_DAYS,
                            scheduleConflictReviewItems = listOf(
                                ScheduleConflictReviewItem(
                                    linkId = "link-1",
                                    calendarTitle = "캘린더 인터뷰",
                                    calendarStartAt = Instant.parse("2026-06-04T01:00:00Z"),
                                    calendarStatus = "confirmed",
                                    sourceTitle = "메일 인터뷰",
                                    sourceStartAt = Instant.parse("2026-06-04T01:15:00Z"),
                                    sourceStatus = "needs_review",
                                    sourceType = SourceType.GMAIL,
                                    evidence = "메일에서 같은 인터뷰 시간이 발견되었습니다.",
                                ),
                                ScheduleConflictReviewItem(
                                    linkId = "link-2",
                                    calendarTitle = "다음 주 미팅",
                                    calendarStartAt = Instant.parse("2026-06-05T04:00:00Z"),
                                    calendarStatus = "tentative",
                                    sourceTitle = "후보 일정",
                                    sourceStartAt = Instant.parse("2026-06-05T04:30:00Z"),
                                    sourceStatus = "needs_review",
                                    sourceType = SourceType.GOOGLE_CALENDAR,
                                    evidence = "캘린더에는 없고 메일에만 있는 후보입니다.",
                                ),
                            ),
                            scheduleActions = listOf(
                                personAction(
                                    id = "schedule-missing",
                                    title = "내일 3시 데모 미팅 캘린더 확인",
                                    primaryVerb = "확인",
                                    actionKind = "schedule",
                                    commitmentId = "commit-schedule",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "메일에는 후보가 있지만 캘린더에는 확정 일정이 없습니다.",
                                ),
                            ),
                        ),
                        onOpenSettings = {},
                        onPullRefresh = {},
                        onResolveScheduleConflict = { _, _ -> ScheduleEventLinkResolutionChoice.SAME_SCHEDULE },
                    )
                    MainTabBottomNavigationOverlay(currentRoute = BecalmRoute.Today.path)
                }
            }
        }

        captureScreenshot("schedule-candidates-conflict-review.png")
    }

    @Test
    fun captureScheduleActionEvidenceWhyAndOriginalSheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    PersonActionEvidenceDialog(
                        detail = evidenceDetail(
                            actionItemId = "schedule-missing",
                            label = "Gmail · 캘린더에 없음",
                            title = "박서연 변호사 의견서 기한",
                            why = "메일에 제출 기한이 있지만 연결된 캘린더에는 같은 일정이 없어 후보 확인이 필요합니다.",
                            original = "박서연 변호사: 의견서는 6월 5일까지 제출하면 됩니다. 캘린더 초대는 따로 보내지 않았습니다.",
                            sourceType = SourceType.GMAIL,
                        ),
                        onDismiss = {},
                    )
                }
            }
        }

        captureScreenshot("schedule-action-evidence-sheet.png", useDeviceScreenshot = true)
    }

    @Test
    fun captureCommitmentsOpenGiveTakeActions() {
        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                Box(Modifier.fillMaxSize()) {
                    CommitmentManagementScreenContent(
                        state = CommitmentUiState(
                            loading = false,
                            topActions = listOf(
                                personAction(
                                    id = "action-give",
                                    title = "제안서 초안 보내기",
                                    primaryVerb = "보내기",
                                    actionKind = "send",
                                    commitmentId = "commit-give",
                                    sourceType = SourceType.GMAIL,
                                    shortReason = "오늘까지 보내기로 한 Give 약속입니다.",
                                    evidence = PersonActionEvidenceUi(
                                        kind = "source_event",
                                        id = "mail-1",
                                        sourceRef = "raw:mail-1",
                                        occurredAt = Instant.parse("2026-06-04T01:00:00Z"),
                                        label = "Gmail",
                                        quote = "오늘 안에 초안을 부탁드립니다.",
                                    ),
                                ),
                                personAction(
                                    id = "action-take",
                                    title = "계약서 검토 답변 받기",
                                    primaryVerb = "확인",
                                    actionKind = "follow_up",
                                    commitmentId = "commit-take",
                                    sourceType = SourceType.GOOGLE_CALENDAR,
                                    shortReason = "상대가 보내기로 한 Take 약속이 지연됐습니다.",
                                ),
                            ),
                            items = listOf(
                                commitmentRow(
                                    id = "commit-give",
                                    title = "제안서 초안 보내기",
                                    direction = "give",
                                    counterparty = "김민홍",
                                ),
                                commitmentRow(
                                    id = "commit-take",
                                    title = "계약서 검토 답변 받기",
                                    direction = "take",
                                    counterparty = "최민서",
                                ),
                            ),
                            confirmedSection = CommitmentSectionUiState(
                                count = 2,
                                expanded = true,
                                dimmed = false,
                                items = listOf(
                                    commitmentRow(
                                        id = "commit-give",
                                        title = "제안서 초안 보내기",
                                        direction = "give",
                                        counterparty = "김민홍",
                                    ),
                                    commitmentRow(
                                        id = "commit-take",
                                        title = "계약서 검토 답변 받기",
                                        direction = "take",
                                        counterparty = "최민서",
                                    ),
                                ),
                            ),
                            filter = CommitmentFilter.ALL,
                        ),
                        snackbarHostState = SnackbarHostState(),
                        pullState = pullState,
                        onFilterChange = {},
                        onMessageScreenshotImport = {},
                        onMeetingAudioImport = {},
                        onOpenDetail = {},
                        onToggleCompletedSection = {},
                        onToggleCancelledSection = {},
                    )
                    MainTabBottomNavigationOverlay(currentRoute = BecalmRoute.Commitments.path)
                }
            }
        }

        captureScreenshot("commitments-open-give-take.png")
    }

    @Test
    fun captureCommitmentsOpenActionsWithSourceStatus() {
        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                Box(Modifier.fillMaxSize()) {
                    CommitmentManagementScreenContent(
                        state = openCommitmentActionState(),
                        snackbarHostState = SnackbarHostState(),
                        pullState = pullState,
                        onFilterChange = {},
                        onMessageScreenshotImport = {},
                        onMeetingAudioImport = {},
                        onOpenDetail = {},
                        onToggleCompletedSection = {},
                        onToggleCancelledSection = {},
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "token expired",
                                    lastSyncedAt = null,
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                    )
                    MainTabBottomNavigationOverlay(currentRoute = BecalmRoute.Commitments.path)
                }
            }
        }

        captureScreenshot("commitments-open-source-status.png")
    }

    @Test
    fun captureCommitmentsOpenActionsWithMultipleSourceWarnings() {
        composeRule.setContent {
            BecalmTheme {
                val pullState = rememberPullRefreshState(refreshing = false, onRefresh = {})
                Box(Modifier.fillMaxSize()) {
                    CommitmentManagementScreenContent(
                        state = openCommitmentActionState(),
                        snackbarHostState = SnackbarHostState(),
                        pullState = pullState,
                        onFilterChange = {},
                        onMessageScreenshotImport = {},
                        onMeetingAudioImport = {},
                        onOpenDetail = {},
                        onToggleCompletedSection = {},
                        onToggleCancelledSection = {},
                        headerState = MainTabHeaderState(
                            sourceStatus = mapOf(
                                SourceType.GMAIL to SourceStatusUi(
                                    status = SourceSyncStatus.Error,
                                    errorMessage = "token expired",
                                    lastSyncedAt = null,
                                ),
                                SourceType.GOOGLE_CALENDAR to SourceStatusUi(
                                    status = SourceSyncStatus.Disconnected,
                                    errorMessage = null,
                                    lastSyncedAt = null,
                                ),
                            ),
                            overall = OverallSyncState.PartialFailure,
                        ),
                    )
                    MainTabBottomNavigationOverlay(currentRoute = BecalmRoute.Commitments.path)
                }
            }
        }

        captureScreenshot("commitments-multiple-source-status.png")
    }

    @Test
    fun captureCommitmentDetailSourceEvidenceSheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    CommitmentDetailSheet(
                        commitmentId = "commit-give",
                        onDismiss = {},
                        stateOverride = DetailUiState(
                            entity = commitmentEntity(quote = "오늘 안에 초안을 부탁드립니다."),
                            quote = "오늘 안에 초안을 부탁드립니다.",
                            counterpartyDisplayName = "김민홍",
                            actionState = CommitmentState.PENDING,
                            source = CommitmentSourcePresentation(
                                isManual = false,
                                sourceType = SourceType.GMAIL,
                                sourceTitle = "Re: 수정 계약서 회신",
                                sourceOccurredAt = Instant.parse("2026-06-01T09:00:00Z"),
                            ),
                            actionButtons = CommitmentDetailActionState(
                                availableActions = setOf(
                                    CommitmentSheetAction.FOLLOW_UP,
                                    CommitmentSheetAction.COMPLETE,
                                ),
                                editEnabled = true,
                            ),
                            history = CommitmentHistoryPresentation(
                                lastEditedAt = Instant.parse("2026-06-01T09:30:00Z"),
                                showSupersedeLink = true,
                            ),
                            sourceEvidenceDetail = evidenceDetail(
                                actionItemId = "commit-give",
                                label = "Gmail · 원문 보관본",
                                title = "수정 계약서 회신",
                                why = "약속 행에 연결된 action row가 없어도 source_ref와 local original로 근거를 복구합니다.",
                                original = "김민홍 대표: 오늘 안에 초안을 부탁드립니다. 제가 확인하고 최종 의견을 보내겠습니다.",
                                sourceType = SourceType.GMAIL,
                            ),
                            loading = false,
                        ),
                        effectsOverride = emptyFlow(),
                        onReminderToggle = {},
                        onFollowUp = {},
                        onComplete = {},
                        onCancel = {},
                    )
                }
            }
        }

        captureScreenshot("commitment-detail-source-evidence-sheet.png", useDeviceScreenshot = true)
    }

    @Test
    fun captureEvidenceImportSheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    EvidenceImportSheet(
                        onDismiss = {},
                        onMessageScreenshotImport = {},
                        onMeetingAudioImport = {},
                    )
                }
            }
        }

        captureScreenshot("evidence-import-sheet.png", useDeviceScreenshot = true)
    }

    @Test
    fun captureCommitmentComposeSheet() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    CreateSheetContent(
                        state = CreateUiState(
                            supersedeSource = commitmentEntity(quote = "오늘 안에 초안을 부탁드립니다."),
                            draft = ManualCommitmentDraft(
                                title = "제안서 초안 보내기",
                                direction = "give",
                                quote = "오늘 안에 초안을 부탁드립니다.",
                                counterpartyRef = "김민홍",
                                dueAtMillis = null,
                                dueHint = "오늘 오후",
                                dueIsApproximate = false,
                            ),
                        ),
                        onTitleChange = {},
                        onDirectionChange = {},
                        onCounterpartyRefChange = {},
                        onDueAtMillisChange = {},
                        onDueHintChange = {},
                        onApproxChange = {},
                        onSave = {},
                        onCancel = {},
                    )
                }
            }
        }

        captureScreenshot("commitment-compose-sheet.png")
    }

    @Test
    fun captureOnboardingWelcomePrivacyNote() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    OnboardingIntroContent(
                        pageIndex = 0,
                        gmailConnectionState = SourceConnectionState.Idle,
                        contactsConnectionState = SourceConnectionState.Idle,
                        calendarConnectionState = SourceConnectionState.Idle,
                        callRecordingConnectionState = SourceConnectionState.Idle,
                        onNext = {},
                        onBack = {},
                        onConnectContacts = {},
                        onConnectGoogleCalendar = {},
                        onConnectCallRecording = {},
                        onSkipCallRecording = {},
                        onConnectGmail = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-welcome-privacy-note.png")
    }

    @Test
    fun captureOnboardingIdentityInputStep() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    OnboardingIntroContent(
                        pageIndex = 1,
                        gmailConnectionState = SourceConnectionState.Idle,
                        contactsConnectionState = SourceConnectionState.Idle,
                        calendarConnectionState = SourceConnectionState.Idle,
                        callRecordingConnectionState = SourceConnectionState.Idle,
                        selfIdentity = OnboardingSelfIdentityUi(
                            displayName = "민수",
                            email = "minsu@example.com",
                            phone = "",
                            alias = "",
                            confirmed = false,
                            saving = false,
                        ),
                        onNext = {},
                        onBack = {},
                        onConnectContacts = {},
                        onConnectGoogleCalendar = {},
                        onConnectCallRecording = {},
                        onSkipCallRecording = {},
                        onConnectGmail = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-identity-input.png")
    }

    @Test
    fun captureOnboardingPeopleConnectedStep() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    OnboardingIntroContent(
                        pageIndex = 2,
                        gmailConnectionState = SourceConnectionState.Idle,
                        contactsConnectionState = SourceConnectionState.Connected,
                        calendarConnectionState = SourceConnectionState.Idle,
                        callRecordingConnectionState = SourceConnectionState.Connected,
                        contactsPreview = OnboardingContactsPreviewUi(
                            totalCount = 24,
                            names = listOf("김민홍", "최민서", "이준호"),
                        ),
                        selfIdentity = OnboardingSelfIdentityUi(
                            displayName = "민수",
                            email = "minsu@example.com",
                            phone = "",
                            alias = "",
                            confirmed = true,
                            saving = false,
                        ),
                        onNext = {},
                        onBack = {},
                        onConnectContacts = {},
                        onConnectGoogleCalendar = {},
                        onConnectCallRecording = {},
                        onSkipCallRecording = {},
                        onConnectGmail = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-people-connected.png")
    }

    @Test
    fun captureOnboardingCalendarConnectedStep() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    OnboardingIntroContent(
                        pageIndex = 3,
                        gmailConnectionState = SourceConnectionState.Idle,
                        contactsConnectionState = SourceConnectionState.Connected,
                        calendarConnectionState = SourceConnectionState.Connected,
                        callRecordingConnectionState = SourceConnectionState.Idle,
                        calendarPreview = OnboardingCalendarPreviewUi(
                            events = listOf(
                                OnboardingCalendarPreviewItemUi(
                                    dayLabel = "내일",
                                    timeLabel = "15:00",
                                    title = "김민홍 대표 데모 미팅",
                                ),
                                OnboardingCalendarPreviewItemUi(
                                    dayLabel = "금요일",
                                    timeLabel = "10:30",
                                    title = "분기 마감 회의",
                                ),
                            ),
                        ),
                        selfIdentity = OnboardingSelfIdentityUi(
                            displayName = "민수",
                            email = "minsu@example.com",
                            phone = "",
                            alias = "",
                            confirmed = true,
                            saving = false,
                        ),
                        onNext = {},
                        onBack = {},
                        onConnectContacts = {},
                        onConnectGoogleCalendar = {},
                        onConnectCallRecording = {},
                        onSkipCallRecording = {},
                        onConnectGmail = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-calendar-connected.png")
    }

    @Test
    fun captureOnboardingMailConnectedStep() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    OnboardingIntroContent(
                        pageIndex = 4,
                        gmailConnectionState = SourceConnectionState.Connected,
                        contactsConnectionState = SourceConnectionState.Connected,
                        calendarConnectionState = SourceConnectionState.Connected,
                        callRecordingConnectionState = SourceConnectionState.Idle,
                        gmailActivationPreview = GmailActivationPreviewUiState(
                            previews = listOf(
                                GmailActivationPreviewUi(
                                    commitmentId = "commitment-1",
                                    personId = "person-minji",
                                    personName = "민지",
                                    participantId = "participant-minji",
                                    participantName = "민지",
                                    participantEmail = "minji@example.com",
                                    participantPhone = null,
                                    contactMatched = true,
                                    title = "금요일까지 제안서 초안 보내기",
                                    itemType = "action",
                                    direction = "give",
                                    scheduleStatus = null,
                                    decisionStatus = null,
                                    dueHint = "금요일",
                                    sourceType = SourceType.GMAIL,
                                    sourceTitle = "Re: BeCalm 사업 소개서 공유",
                                    actionItemId = "pa-preview-1",
                                    actionKind = "follow_up",
                                    reasonCodes = listOf("onboarding:high_confidence_7d"),
                                ),
                                GmailActivationPreviewUi(
                                    commitmentId = "commitment-2",
                                    personId = "person-dohyun",
                                    personName = "도현",
                                    participantId = "participant-dohyun",
                                    participantName = "도현",
                                    participantEmail = "dohyun@example.com",
                                    participantPhone = null,
                                    contactMatched = true,
                                    title = "수정 계약서 회신",
                                    itemType = "action",
                                    direction = "give",
                                    scheduleStatus = null,
                                    decisionStatus = null,
                                    dueHint = "오늘까지",
                                    sourceType = SourceType.GMAIL,
                                    sourceTitle = "Re: 계약서 조항 확인",
                                    actionItemId = "pa-preview-2",
                                    actionKind = "follow_up",
                                    reasonCodes = listOf("onboarding:high_confidence_7d"),
                                ),
                            ),
                        ),
                        selfIdentity = OnboardingSelfIdentityUi(
                            displayName = "민수",
                            email = "minsu@example.com",
                            phone = "",
                            alias = "",
                            confirmed = true,
                            saving = false,
                        ),
                        onNext = {},
                        onBack = {},
                        onConnectContacts = {},
                        onConnectGoogleCalendar = {},
                        onConnectCallRecording = {},
                        onSkipCallRecording = {},
                        onConnectGmail = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-mail-connected.png")
    }

    @Test
    fun captureOnboardingSourceConnectionsFirstSource() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    SourceConnectionsContent(
                        items = listOf(
                            SourceConnectionItemUi(
                                provider = OnboardingSourceProvider.GMAIL,
                                category = SourceConnectionCategory.Mail,
                                title = "Gmail",
                                description = "메일 속 약속 후보를 먼저 확인합니다.",
                                consentCopy = null,
                                state = SourceConnectionState.Idle,
                            ),
                            SourceConnectionItemUi(
                                provider = OnboardingSourceProvider.GOOGLE_CALENDAR,
                                category = SourceConnectionCategory.Calendar,
                                title = "Google Calendar",
                                description = "캘린더와 다른 출처의 일정 차이를 확인합니다.",
                                consentCopy = null,
                                state = SourceConnectionState.Connected,
                            ),
                        ),
                        headline = string(R.string.onb_setup_headline),
                        body = string(R.string.onb_setup_body),
                        continueLabel = string(R.string.onb_setup_start),
                        onConnect = {},
                        onSkip = {},
                        setupItems = listOf(
                            OnboardingSetupItemUi(
                                item = OnboardingSetupItem.Contacts,
                                title = string(R.string.onb_setup_contacts_title),
                                description = string(R.string.onb_setup_contacts_body),
                                state = SourceConnectionState.Connected,
                            ),
                        ),
                        selfIdentity = OnboardingSelfIdentityUi(
                            displayName = "민수",
                            email = "me@example.com",
                            phone = "",
                            alias = "",
                            confirmed = true,
                            saving = false,
                        ),
                        onContinue = {},
                        progressiveSetup = true,
                    )
                }
            }
        }

        captureScreenshot("onboarding-source-connections-first-source.png")
    }

    @Test
    fun captureOnboardingGmailActivationLoading() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    GmailActivationPreviewContent(
                        state = GmailActivationPreviewUiState(
                            status = GmailActivationPreviewStatus.StillProcessing,
                            progress = 0.55f,
                            progressMessage = "메일 속 약속 후보를 확인하고 있습니다",
                        ),
                        onUsePreview = {},
                        onRetry = {},
                        onStartWithoutPreview = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-gmail-activation-loading.png")
    }

    @Test
    fun captureOnboardingGmailActivationReady() {
        composeRule.setContent {
            BecalmTheme {
                Box(Modifier.fillMaxSize()) {
                    GmailActivationPreviewContent(
                        state = GmailActivationPreviewUiState(
                            previews = listOf(
                                GmailActivationPreviewUi(
                                    commitmentId = "commitment-1",
                                    personId = "person-minji",
                                    personName = "민지",
                                    participantId = "participant-minji",
                                    participantName = "민지",
                                    participantEmail = "minji@example.com",
                                    participantPhone = null,
                                    contactMatched = true,
                                    title = "금요일까지 제안서 초안 보내기",
                                    itemType = "action",
                                    direction = "give",
                                    scheduleStatus = null,
                                    decisionStatus = null,
                                    dueHint = "금요일",
                                    sourceType = SourceType.GMAIL,
                                    sourceTitle = "Re: BeCalm 사업 소개서 공유",
                                    actionItemId = "pa-preview-1",
                                    actionKind = "follow_up",
                                    reasonCodes = listOf("onboarding:high_confidence_7d"),
                                ),
                                GmailActivationPreviewUi(
                                    commitmentId = "commitment-2",
                                    personId = "person-dohyun",
                                    personName = "도현",
                                    participantId = "participant-dohyun",
                                    participantName = "도현",
                                    participantEmail = "dohyun@example.com",
                                    participantPhone = null,
                                    contactMatched = true,
                                    title = "수정 계약서 회신",
                                    itemType = "action",
                                    direction = "give",
                                    scheduleStatus = null,
                                    decisionStatus = null,
                                    dueHint = "오늘까지",
                                    sourceType = SourceType.GMAIL,
                                    sourceTitle = "Re: 계약서 조항 확인",
                                    actionItemId = "pa-preview-2",
                                    actionKind = "follow_up",
                                    reasonCodes = listOf("onboarding:high_confidence_7d"),
                                ),
                                GmailActivationPreviewUi(
                                    commitmentId = "commitment-3",
                                    personId = "person-junho",
                                    personName = "준호",
                                    participantId = "participant-junho",
                                    participantName = "준호",
                                    participantEmail = "junho@example.com",
                                    participantPhone = null,
                                    contactMatched = false,
                                    title = "분기 미팅 날짜 제안하기",
                                    itemType = "schedule",
                                    direction = null,
                                    scheduleStatus = "needs_review",
                                    decisionStatus = null,
                                    dueHint = "이번 주",
                                    sourceType = SourceType.GMAIL,
                                    sourceTitle = "Re: 분기 마감 미팅",
                                    actionItemId = "pa-preview-3",
                                    actionKind = "confirm_onboarding",
                                    reasonCodes = listOf("onboarding:confirm_candidate"),
                                ),
                            ),
                        ),
                        onUsePreview = {},
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
        }

        captureScreenshot("onboarding-gmail-activation-ready.png")
    }

    private fun captureScreenshot(filename: String, useDeviceScreenshot: Boolean = false) {
        composeRule.waitForIdle()
        if (useDeviceScreenshot) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(500)
            composeRule.waitForIdle()
        }
        val bitmap = if (useDeviceScreenshot) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        } else {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
        }
        try {
            assertTrue("Expected screenshot width > 1 for $filename", bitmap.width > 1)
            assertTrue("Expected screenshot height > 1 for $filename", bitmap.height > 1)
            assertTrue("Expected nonblank screenshot pixels for $filename", bitmap.hasNonblankSamples())
            val output = outputDir().resolve(filename)
            FileOutputStream(output).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            assertTrue("Expected screenshot to exist: ${output.absolutePath}", output.isFile)
            assertTrue("Expected screenshot to be non-empty: ${output.absolutePath}", output.length() > 0L)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun Bitmap.hasNonblankSamples(): Boolean {
        val distinctColors = mutableSetOf<Int>()
        var nonTransparentSamples = 0
        val xStep = (width / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        val yStep = (height / SAMPLE_GRID_SIZE).coerceAtLeast(1)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val color = getPixel(x, y)
                if ((color ushr 24) != 0) {
                    nonTransparentSamples += 1
                    distinctColors += color
                }
                x += xStep
            }
            y += yStep
        }
        return nonTransparentSamples > 0 && distinctColors.size > 1
    }

    private fun outputDir(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "ui-parity-fixtures/html-prototype-20260604")
            .apply { mkdirs() }
    }

    private companion object {
        private const val SAMPLE_GRID_SIZE = 128
    }

    @Composable
    private fun MainTabBottomNavigationOverlay(
        currentRoute: String,
        personActionBadgeCount: Int = 0,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                BecalmBottomNavigation(
                    currentRoute = currentRoute,
                    navController = rememberNavController(),
                    personActionBadgeCount = personActionBadgeCount,
                )
            }
        }
    }

    private fun string(resId: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    private fun personRow(
        personId: String,
        name: String,
        company: String?,
        action: PersonActionSummary,
    ): PersonRow = PersonRow(
        personId = personId,
        displayName = name,
        companyName = company,
        lastInteractionAt = Instant.parse("2026-06-04T01:00:00Z"),
        interactionCount = 4,
        topAction = action,
    )

    private fun staleRecallPersonRow(): PersonRow = PersonRow(
        personId = "person-stale-entry",
        displayName = "최민서",
        companyName = "파트너사",
        lastInteractionAt = Instant.parse("2025-05-10T01:00:00Z"),
        interactionCount = 1,
        lastInteractionSnippet = "24일째 연락 없음 — 복기하고 재개하기",
    )

    private fun personAction(
        id: String,
        title: String,
        primaryVerb: String,
        actionKind: String,
        commitmentId: String?,
        sourceType: String,
        shortReason: String,
        evidence: PersonActionEvidenceUi? = null,
        providerWrite: PersonActionProviderWriteUi? = null,
    ): PersonActionItemUi = PersonActionItemUi(
        id = id,
        personId = "person-1",
        personDisplayName = "김민홍",
        actionKind = actionKind,
        title = title,
        primaryVerb = primaryVerb,
        shortReason = shortReason,
        commitmentId = commitmentId,
        calendarEventId = null,
        sourceEventId = null,
        sourceType = sourceType,
        sourceRef = "mail-1",
        dueAt = Instant.parse("2026-06-04T01:00:00Z"),
        dueHint = null,
        urgencyScore = 91.0,
        confidence = 0.95,
        reasonCodes = listOf("source:due"),
        evidence = evidence,
        providerWrite = providerWrite,
    )

    private fun readyCalendarWrite(scheduleEventLinkId: String): PersonActionProviderWriteUi =
        PersonActionProviderWriteUi(
            kind = "add_to_calendar",
            state = "ready",
            provider = SourceType.GOOGLE_CALENDAR,
            sourceConnectionId = "conn-calendar",
            scheduleEventLinkId = scheduleEventLinkId,
        )

    private fun evidenceDetail(
        actionItemId: String,
        label: String,
        title: String,
        why: String,
        original: String,
        sourceType: String,
        metadata: String? = null,
        originalIsLocal: Boolean = false,
        originalTruncated: Boolean = false,
    ): PersonActionEvidenceDetailUi = PersonActionEvidenceDetailUi(
        actionItemId = actionItemId,
        evidenceLabel = label,
        whyText = why,
        originalTitle = title,
        originalText = original,
        metadataText = metadata,
        originalIsLocal = originalIsLocal,
        originalTruncated = originalTruncated,
        sourceType = sourceType,
    )

    private fun openCommitmentActionState(): CommitmentUiState = CommitmentUiState(
        loading = false,
        topActions = listOf(
            personAction(
                id = "action-give",
                title = "제안서 초안 보내기",
                primaryVerb = "보내기",
                actionKind = "send",
                commitmentId = "commit-give",
                sourceType = SourceType.GMAIL,
                shortReason = "오늘까지 보내기로 한 Give 약속입니다.",
                evidence = PersonActionEvidenceUi(
                    kind = "source_event",
                    id = "mail-1",
                    sourceRef = "raw:mail-1",
                    occurredAt = Instant.parse("2026-06-04T01:00:00Z"),
                    label = "Gmail",
                    quote = "오늘 안에 초안을 부탁드립니다.",
                ),
            ),
            personAction(
                id = "action-take",
                title = "계약서 검토 답변 받기",
                primaryVerb = "확인",
                actionKind = "follow_up",
                commitmentId = "commit-take",
                sourceType = SourceType.GOOGLE_CALENDAR,
                shortReason = "상대가 보내기로 한 Take 약속이 지연됐습니다.",
            ),
        ),
        items = listOf(
            commitmentRow(
                id = "commit-give",
                title = "제안서 초안 보내기",
                direction = "give",
                counterparty = "김민홍",
            ),
            commitmentRow(
                id = "commit-take",
                title = "계약서 검토 답변 받기",
                direction = "take",
                counterparty = "최민서",
            ),
        ),
        confirmedSection = CommitmentSectionUiState(
            count = 2,
            expanded = true,
            dimmed = false,
            items = listOf(
                commitmentRow(
                    id = "commit-give",
                    title = "제안서 초안 보내기",
                    direction = "give",
                    counterparty = "김민홍",
                ),
                commitmentRow(
                    id = "commit-take",
                    title = "계약서 검토 답변 받기",
                    direction = "take",
                    counterparty = "최민서",
                ),
            ),
        ),
        filter = CommitmentFilter.ALL,
    )

    private fun commitmentRow(
        id: String,
        title: String,
        direction: String,
        counterparty: String,
    ): CommitmentRow = CommitmentRow(
        id = id,
        itemType = "action",
        title = title,
        direction = direction,
        scheduleStatus = null,
        decisionStatus = null,
        derivedStatus = "PENDING",
        actionState = CommitmentState.PENDING,
        dueAt = Instant.parse("2026-06-04T01:00:00Z"),
        dueIsApproximate = false,
        counterpartyDisplayName = counterparty,
        sourceType = SourceType.GMAIL,
        sourceTitle = "고객 메일",
        sourceOccurredAt = Instant.parse("2026-06-03T10:00:00Z"),
        dueHint = null,
        isManual = false,
    )

    private fun commitmentEntity(
        quote: String,
    ): CommitmentEntity = CommitmentEntity(
        id = "commitment-1",
        userId = "user-1",
        itemType = CommitmentItemType.ACTION,
        direction = "give",
        scheduleStatus = null,
        decisionStatus = null,
        counterpartyRaw = "김민홍",
        counterpartyRef = "person-1",
        title = "제안서 보내기",
        description = null,
        quote = quote,
        sourceEventTitle = "고객 메일",
        sourceEventOccurredAt = Instant.parse("2026-06-04T01:00:00Z"),
        dueAt = Instant.parse("2026-06-04T09:00:00Z"),
        dueHint = "오늘 오후",
        dueIsApproximate = false,
        actionState = "pending",
        sourceType = SourceType.GMAIL,
        sourceRef = "mail-1",
        confidence = 0.91,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = Instant.parse("2026-06-04T01:00:00Z"),
        updatedAt = Instant.parse("2026-06-04T01:00:00Z"),
        lastEditedBy = "user-1",
        lastEditedAt = Instant.parse("2026-06-04T01:30:00Z"),
        quoteDisputed = false,
        quoteDisputedAt = null,
        deletedAt = null,
        supersedesCommitmentId = "old-1",
    )
}
