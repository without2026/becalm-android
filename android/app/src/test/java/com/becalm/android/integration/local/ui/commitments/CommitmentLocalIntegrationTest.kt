package com.becalm.android.integration.local.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.util.RecordingLogger
import com.becalm.android.data.local.datastore.UserPrefsStoreImpl
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.PersonEnrichmentEntity
import com.becalm.android.data.remote.api.RailwayApi
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.CommitmentParticipantRepository
import com.becalm.android.data.repository.CommitmentRepositoryImpl
import com.becalm.android.data.repository.PersonEnrichmentRepositoryImpl
import com.becalm.android.data.repository.SourceEventParticipantRepository
import com.becalm.android.domain.reminder.ReminderScheduler
import com.becalm.android.integration.local.LocalIntegrationSupport
import com.becalm.android.ui.commitments.CommitmentDetailViewModel
import com.becalm.android.ui.commitments.CommitmentManagementViewModel
import com.becalm.android.ui.navigation.BecalmRoute
import com.becalm.android.worker.WorkScheduler
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CommitmentLocalIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private val db = LocalIntegrationSupport.inMemoryDatabase()
    private val logger = RecordingLogger()
    private val api = mockk<RailwayApi>(relaxed = true)
    private val cursorStore = mockk<com.becalm.android.data.local.datastore.SyncCursorStore>(relaxed = true)
    private val userPrefsStore = UserPrefsStoreImpl(
        dataStore = LocalIntegrationSupport.prefsDataStore("commitment-local"),
    )
    private val commitmentRepository = CommitmentRepositoryImpl(
        dao = db.commitmentDao(),
        api = api,
        cursorStore = cursorStore,
        userPrefsStore = userPrefsStore,
        database = db,
        logger = logger,
        ioDispatcher = UnconfinedTestDispatcher(),
    )
    private val enrichmentRepository = PersonEnrichmentRepositoryImpl(
        dao = db.personEnrichmentDao(),
        logger = logger,
    )
    private val sourceEventParticipantRepository = mockk<SourceEventParticipantRepository>(relaxed = true)
    private val commitmentParticipantRepository = mockk<CommitmentParticipantRepository>(relaxed = true)
    private val workScheduler = mockk<WorkScheduler>(relaxed = true)
    private val reminderScheduler = mockk<ReminderScheduler>(relaxed = true)

    @Before
    fun setUp() = runTest {
        Dispatchers.setMain(dispatcher)
        userPrefsStore.setCurrentUserId(USER_ID)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `CMT-009 management screen sections project active completed and cancelled rows from room`() = runTest {
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = PERSON_REF,
                displayName = "김철수",
                lastSyncedAt = Instant.parse("2026-04-23T00:00:00Z"),
            ),
        )
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "pending-row",
                    title = "활성 약속",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = Instant.parse("2026-04-24T00:00:00Z"),
                ),
                commitment(
                    id = "completed-row",
                    title = "완료 약속",
                    actionState = "completed",
                    sourceType = SourceType.MANUAL,
                    dueAt = null,
                ),
                commitment(
                    id = "cancelled-row",
                    title = "취소 약속",
                    actionState = "cancelled",
                    sourceType = SourceType.OUTLOOK_MAIL,
                    dueAt = null,
                ),
            ),
        )

        val viewModel = CommitmentManagementViewModel(
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            workScheduler = workScheduler,
            reminderScheduler = reminderScheduler,
            userPrefsStore = userPrefsStore,
            logger = logger,
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.items.size < 3 || state.completedSection.count != 1 || state.cancelledSection.count != 1) {
                state = awaitItem()
            }

            assertEquals(1, state.activeItems.size)
            assertEquals("활성 약속", state.activeItems.single().title)
            assertEquals(1, state.completedSection.count)
            assertEquals(listOf("완료 약속"), state.completedSection.items.map { it.title })
            assertFalse(state.completedSection.expanded)
            assertEquals(1, state.cancelledSection.count)
            assertEquals(listOf("취소 약속"), state.cancelledSection.items.map { it.title })

            viewModel.onToggleCompletedSection()
            val expanded = awaitItem()
            assertTrue(expanded.completedSection.expanded)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `management screen hides non person lifecycle actions but keeps schedules`() = runTest {
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "slack-action",
                    title = "Slack 이메일 주소를 확인하세요",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = null,
                    sourceEventTitle = "Slack에서 이메일 주소를 확인하세요.",
                ),
                commitment(
                    id = "asan-action",
                    title = "아산나눔재단에 추가 문의 사항을 채널톡으로 문의하다",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = null,
                    sourceEventTitle = "[아산 두어스] 2026 아산 두어스 지원서 제출이 완료되었습니다.",
                ),
                commitment(
                    id = "asan-schedule",
                    itemType = CommitmentItemType.SCHEDULE,
                    title = "2026 아산 두어스 서류 결과 안내를 받다",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = Instant.parse("2026-04-30T08:00:00Z"),
                    sourceEventTitle = "[아산 두어스] 2026 아산 두어스 지원서 제출이 완료되었습니다.",
                ),
            ),
        )

        val viewModel = CommitmentManagementViewModel(
            commitmentRepository = commitmentRepository,
            sourceEventParticipantRepository = sourceEventParticipantRepository,
            commitmentParticipantRepository = commitmentParticipantRepository,
            workScheduler = workScheduler,
            reminderScheduler = reminderScheduler,
            userPrefsStore = userPrefsStore,
            logger = logger,
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.items.isEmpty()) state = awaitItem()

            assertEquals(listOf("asan-schedule"), state.items.map { it.id })
            assertEquals(listOf("asan-schedule"), state.activeItems.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CMT-003 EDIT-008 and MAN-004 detail sheet projects source and edit history from room`() = runTest {
        enrichmentRepository.upsert(
            PersonEnrichmentEntity(
                personRef = PERSON_REF,
                displayName = "김철수",
                lastSyncedAt = Instant.parse("2026-04-23T00:00:00Z"),
            ),
        )
        db.commitmentDao().insert(
            commitment(
                id = "manual-detail",
                title = "수동 약속",
                actionState = "pending",
                sourceType = SourceType.MANUAL,
                dueAt = null,
                createdAt = Instant.parse("2026-04-18T05:30:00Z"),
                lastEditedAt = Instant.parse("2026-04-18T05:30:00Z"),
                quoteDisputedAt = Instant.parse("2026-04-18T06:00:00Z"),
                supersedesCommitmentId = "old-id",
            ),
        )

        val viewModel = CommitmentDetailViewModel(
            commitmentRepository = commitmentRepository,
            personEnrichmentRepository = enrichmentRepository,
            userPrefsStore = userPrefsStore,
            savedStateHandle = SavedStateHandle(mapOf(BecalmRoute.CommitmentDetail.ARG_ID to "manual-detail")),
            logger = logger,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.entity == null) {
                state = awaitItem()
            }

            assertEquals("김철수", state.counterpartyDisplayName)
            assertTrue(state.source.isManual)
            assertEquals(R.string.commitment_detail_manual_source_fmt, state.source.sourceLabel?.resId)
            assertEquals(listOf("2026-04-18 14:30"), state.source.sourceLabel?.args)
            assertEquals(Instant.parse("2026-04-18T05:30:00Z"), state.history.lastEditedAt)
            assertEquals(Instant.parse("2026-04-18T06:00:00Z"), state.history.disputeRaisedAt)
            assertTrue(state.history.showSupersedeLink)
            assertTrue(state.actionButtons.editEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `management rows order by exact due date before fallback source time`() = runTest {
        db.commitmentDao().insertAll(
            listOf(
                commitment(
                    id = "no-due",
                    title = "시간 없는 약속",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = null,
                    sourceEventOccurredAt = Instant.parse("2026-04-29T04:00:00Z"),
                ),
                commitment(
                    id = "late-exact",
                    title = "늦은 정확한 약속",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = Instant.parse("2026-04-25T01:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-29T03:00:00Z"),
                ),
                commitment(
                    id = "approx",
                    title = "대략적인 약속",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = Instant.parse("2026-04-23T01:00:00Z"),
                    dueIsApproximate = true,
                    sourceEventOccurredAt = Instant.parse("2026-04-29T02:00:00Z"),
                ),
                commitment(
                    id = "early-exact",
                    title = "빠른 정확한 약속",
                    actionState = "pending",
                    sourceType = SourceType.GMAIL,
                    dueAt = Instant.parse("2026-04-24T01:00:00Z"),
                    sourceEventOccurredAt = Instant.parse("2026-04-29T01:00:00Z"),
                ),
            ),
        )

        commitmentRepository.observeManagementRowsForUser(USER_ID).test {
            val rows = awaitItem()
            assertEquals(
                listOf("early-exact", "late-exact", "no-due", "approx"),
                rows.map { it.id },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun commitment(
        id: String,
        title: String,
        actionState: String,
        sourceType: String,
        dueAt: Instant?,
        itemType: String = CommitmentItemType.ACTION,
        dueIsApproximate: Boolean = false,
        sourceEventTitle: String = "source-$id",
        sourceEventOccurredAt: Instant = Instant.parse("2026-04-18T05:00:00Z"),
        createdAt: Instant = Instant.parse("2026-04-18T05:30:00Z"),
        lastEditedAt: Instant? = null,
        quoteDisputedAt: Instant? = null,
        supersedesCommitmentId: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = USER_ID,
        itemType = itemType,
        direction = "give",
        counterpartyRaw = PERSON_REF,
        counterpartyRef = PERSON_REF,
        title = title,
        description = null,
        quote = "quote-$id",
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = dueAt,
        dueHint = null,
        dueIsApproximate = dueIsApproximate,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = "ref-$id",
        confidence = 0.9,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = createdAt,
        updatedAt = createdAt,
        lastEditedBy = USER_ID,
        lastEditedAt = lastEditedAt,
        quoteDisputed = quoteDisputedAt != null,
        quoteDisputedAt = quoteDisputedAt,
        supersedesCommitmentId = supersedesCommitmentId,
    )

    private companion object {
        private const val USER_ID = "user-1"
        private const val PERSON_REF = "+821012345678"
    }
}
