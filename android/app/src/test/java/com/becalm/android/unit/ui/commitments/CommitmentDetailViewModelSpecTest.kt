package com.becalm.android.unit.ui.commitments

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.becalm.android.R
import com.becalm.android.core.util.Logger
import com.becalm.android.data.local.datastore.UserPrefsStore
import com.becalm.android.data.local.db.dao.MeetingSpeakerAliasDao
import com.becalm.android.data.local.db.dao.RawIngestionEventDao
import com.becalm.android.data.local.db.entity.CommitmentEntity
import com.becalm.android.data.local.db.entity.CommitmentItemType
import com.becalm.android.data.local.db.entity.CommitmentLifecycleLegacy
import com.becalm.android.data.local.db.entity.MeetingSpeakerAliasEntity
import com.becalm.android.data.local.db.entity.RawIngestionEventEntity
import com.becalm.android.data.local.db.entity.SourceArtifactEntity
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.ArchivedOriginal
import com.becalm.android.data.repository.CommitmentRepository
import com.becalm.android.data.repository.PersonEnrichmentRepository
import com.becalm.android.data.repository.SourceArtifactRepository
import com.becalm.android.ui.commitments.CommitmentDetailViewModel
import com.becalm.android.ui.navigation.BecalmRoute
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.reflect.full.memberProperties
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommitmentDetailViewModelSpecTest {

    private val testDispatcher = StandardTestDispatcher()
    private val commitmentRepository: CommitmentRepository = mockk(relaxed = true)
    private val personEnrichmentRepository: PersonEnrichmentRepository = mockk(relaxed = true)
    private val rawIngestionEventDao: RawIngestionEventDao = mockk(relaxed = true)
    private val meetingSpeakerAliasDao: MeetingSpeakerAliasDao = mockk(relaxed = true)
    private val sourceArtifactRepository: SourceArtifactRepository = mockk(relaxed = true)
    private val userPrefsStore: UserPrefsStore = mockk(relaxed = true)
    private val logger: Logger = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { userPrefsStore.observeCurrentUserId() } returns flowOf("user-1")
        every { userPrefsStore.observeDisabledCommitmentReminderIds() } returns flowOf(emptySet())
        every { personEnrichmentRepository.observeEnrichmentMap() } returns flowOf(emptyMap())
        coEvery { meetingSpeakerAliasDao.findForRawEvent(any(), any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `CMT-003 detail state projects full quote source attribution and available actions`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "detail-1") } returns flowOf(
            entity(
                id = "detail-1",
                quote = "다음 주까지 보고서 제출",
                sourceEventTitle = "2024-03-15 팀 미팅",
                sourceEventOccurredAt = Instant.parse("2026-04-18T06:00:00Z"),
                actionState = "pending",
            ),
        )

        val viewModel = buildViewModel("detail-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("다음 주까지 보고서 제출", state.quote)
        assertEquals("2024-03-15 팀 미팅", state.source.sourceTitle)
        assertEquals(Instant.parse("2026-04-18T06:00:00Z"), state.source.sourceOccurredAt)
        assertEquals(
            listOf("FOLLOW_UP", "COMPLETE", "CANCEL"),
            state.actionButtons.availableActions.map { action -> action.toString() },
        )
    }

    @Test
    fun `CMT-003 EDIT-001 tapping edit emits effect for editable commitment`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "detail-edit") } returns
            flowOf(entity(id = "detail-edit"))

        val viewModel = buildViewModel("detail-edit")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.actionButtons.editEnabled)

        viewModel.effects.test {
            viewModel.onEditClick()
            advanceUntilIdle()

            val effect = awaitItem()
            assertEquals("detail-edit", propertyValue(effect, "commitmentId"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EDIT-001 cancelled or deleted commitments expose read only detail state`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "cancelled") } returns
            flowOf(entity(id = "cancelled", actionState = "cancelled"))
        every { commitmentRepository.observeByIdForUser("user-1", "deleted") } returns
            flowOf(entity(id = "deleted", deletedAt = Instant.parse("2026-04-18T07:00:00Z")))

        val cancelledViewModel = buildViewModel("cancelled")
        val deletedViewModel = buildViewModel("deleted")
        advanceUntilIdle()

        assertFalse(cancelledViewModel.uiState.value.actionButtons.editEnabled)
        assertFalse(deletedViewModel.uiState.value.actionButtons.editEnabled)
    }

    @Test
    fun `EDIT-008 history projects last edited dispute and supersede markers`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "history-1") } returns flowOf(
            entity(
                id = "history-1",
                lastEditedAt = Instant.parse("2026-04-18T05:30:00Z"),
                quoteDisputed = true,
                quoteDisputedAt = Instant.parse("2026-04-18T05:31:00Z"),
                supersedesCommitmentId = "old-1",
            ),
        )

        val viewModel = buildViewModel("history-1")
        advanceUntilIdle()

        val history = viewModel.uiState.value.history
        assertEquals(Instant.parse("2026-04-18T05:30:00Z"), history.lastEditedAt)
        assertEquals(Instant.parse("2026-04-18T05:31:00Z"), history.disputeRaisedAt)
        assertTrue(history.showSupersedeLink)
    }

    @Test
    fun `MAN-004 manual commitments project manual source presentation and badge flag`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "manual-1") } returns flowOf(
            entity(
                id = "manual-1",
                sourceType = "manual",
                sourceEventTitle = null,
                sourceEventOccurredAt = Instant.parse("2026-04-18T05:30:00Z"),
                createdAt = Instant.parse("2026-04-18T05:30:00Z"),
            ),
        )

        val viewModel = buildViewModel("manual-1")
        advanceUntilIdle()

        val source = viewModel.uiState.value.source
        assertTrue(source.isManual)
        assertNull(source.sourceTitle)
        assertEquals(R.string.commitment_detail_manual_source_fmt, source.sourceLabel?.resId)
        assertEquals(listOf("2026-04-18 14:30"), source.sourceLabel?.args)
    }

    @Test
    fun `source label falls back without leaking raw source type`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "source-fallback") } returns flowOf(
            entity(
                id = "source-fallback",
                sourceType = "gmail",
                sourceEventTitle = null,
                sourceEventOccurredAt = Instant.parse("2026-04-18T06:00:00Z"),
            ),
        )

        val viewModel = buildViewModel("source-fallback")
        advanceUntilIdle()

        val sourceLabel = viewModel.uiState.value.source.sourceLabel
        assertEquals(R.string.commitment_detail_llm_source_original_fmt, sourceLabel?.resId)
        assertEquals(listOf("4/18 15:00"), sourceLabel?.args)
    }

    @Test
    fun `message screenshot source label does not expose import timestamp as promise date`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "screenshot-1") } returns flowOf(
            entity(
                id = "screenshot-1",
                sourceType = "message_screenshot",
                sourceEventTitle = "카카오톡 캡처",
                sourceEventOccurredAt = Instant.parse("2026-05-17T06:00:00Z"),
            ),
        )

        val viewModel = buildViewModel("screenshot-1")
        advanceUntilIdle()

        val sourceLabel = viewModel.uiState.value.source.sourceLabel
        assertEquals(R.string.commitment_detail_source_title_fmt, sourceLabel?.resId)
        assertEquals(listOf("카카오톡 캡처"), sourceLabel?.args)
    }

    @Test
    fun `non action detail state exposes read only trackable with no action buttons`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "schedule-1") } returns flowOf(
            entity(
                id = "schedule-1",
                itemType = CommitmentItemType.SCHEDULE,
                direction = null,
            ),
        )

        val viewModel = buildViewModel("schedule-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.actionButtons.availableActions.isEmpty())
        assertFalse(state.actionButtons.editEnabled)
        assertEquals(CommitmentItemType.SCHEDULE, state.entity?.itemType)
    }

    @Test
    fun `meeting schedule detail includes archived transcript`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "meeting-schedule") } returns flowOf(
            entity(
                id = "meeting-schedule",
                itemType = CommitmentItemType.SCHEDULE,
                direction = null,
                sourceType = SourceType.MEETING,
                sourceRef = "content://meeting/audio",
            ),
        )
        coEvery {
            rawIngestionEventDao.findBySourceRefsForUser("user-1", listOf("content://meeting/audio"))
        } returns listOf(
            RawIngestionEventEntity(
                id = "raw-meeting-1",
                userId = "user-1",
                clientEventId = "client-raw-meeting-1",
                sourceType = SourceType.MEETING,
                sourceRef = "content://meeting/audio",
                eventTitle = "standup.m4a",
                timestamp = Instant.parse("2026-05-19T01:00:00Z"),
            ),
        )
        coEvery { sourceArtifactRepository.findMarkdownOriginal("user-1", "raw-meeting-1") } returns
            ArchivedOriginal(
                artifact = sourceArtifact("raw-meeting-1"),
                markdown = "SPEAKER_01: 제가 자료 보낼게요.",
                markdownTruncated = false,
            )
        coEvery { meetingSpeakerAliasDao.findForRawEvent("user-1", "raw-meeting-1") } returns listOf(
            MeetingSpeakerAliasEntity(
                userId = "user-1",
                rawEventId = "raw-meeting-1",
                speakerId = "SPEAKER_01",
                displayName = "Jake",
                updatedAt = Instant.parse("2026-05-19T01:00:00Z"),
            ),
        )

        val viewModel = buildViewModel("meeting-schedule")
        advanceUntilIdle()

        assertEquals("Jake: 제가 자료 보낼게요.", viewModel.uiState.value.meetingTranscript?.bodyText)
        assertEquals(listOf("SPEAKER_01"), viewModel.uiState.value.meetingTranscript?.speakerIds)
    }

    @Test
    fun `meeting schedule detail lets user rename transcript speakers`() = runTest {
        every { commitmentRepository.observeByIdForUser("user-1", "meeting-alias") } returns flowOf(
            entity(
                id = "meeting-alias",
                itemType = CommitmentItemType.SCHEDULE,
                direction = null,
                sourceType = SourceType.MEETING,
                sourceRef = "content://meeting/audio",
            ),
        )
        coEvery {
            rawIngestionEventDao.findBySourceRefsForUser("user-1", listOf("content://meeting/audio"))
        } returns listOf(
            RawIngestionEventEntity(
                id = "raw-meeting-2",
                userId = "user-1",
                clientEventId = "client-raw-meeting-2",
                sourceType = SourceType.MEETING,
                sourceRef = "content://meeting/audio",
                eventTitle = "standup.m4a",
                timestamp = Instant.parse("2026-05-19T01:00:00Z"),
            ),
        )
        coEvery { sourceArtifactRepository.findMarkdownOriginal("user-1", "raw-meeting-2") } returns
            ArchivedOriginal(
                artifact = sourceArtifact("raw-meeting-2"),
                markdown = "SPEAKER_01: 제가 자료 보낼게요.",
                markdownTruncated = false,
            )
        val aliasSlot = slot<MeetingSpeakerAliasEntity>()

        val viewModel = buildViewModel("meeting-alias")
        advanceUntilIdle()
        viewModel.onSpeakerAliasChange("SPEAKER_01", "나")
        advanceUntilIdle()

        coVerify { meetingSpeakerAliasDao.upsert(capture(aliasSlot)) }
        assertEquals("raw-meeting-2", aliasSlot.captured.rawEventId)
        assertEquals("SPEAKER_01", aliasSlot.captured.speakerId)
        assertEquals("나", aliasSlot.captured.displayName)
        assertEquals("나: 제가 자료 보낼게요.", viewModel.uiState.value.meetingTranscript?.bodyText)
    }


    private fun buildViewModel(id: String): CommitmentDetailViewModel = CommitmentDetailViewModel(
        commitmentRepository = commitmentRepository,
        personEnrichmentRepository = personEnrichmentRepository,
        rawIngestionEventDao = rawIngestionEventDao,
        meetingSpeakerAliasDao = meetingSpeakerAliasDao,
        sourceArtifactRepository = sourceArtifactRepository,
        userPrefsStore = userPrefsStore,
        savedStateHandle = SavedStateHandle(mapOf(BecalmRoute.CommitmentDetail.ARG_ID to id)),
        logger = logger,
        ioDispatcher = testDispatcher,
    )

    private fun propertyValue(instance: Any, name: String): Any? =
        instance::class.memberProperties.first { it.name == name }.getter.call(instance)

    private fun entity(
        id: String,
        itemType: String = CommitmentItemType.ACTION,
        direction: String? = "give",
        quote: String = "quote body",
        sourceEventTitle: String? = "Standup",
        sourceEventOccurredAt: Instant = Instant.parse("2026-04-18T06:00:00Z"),
        actionState: String = "pending",
        sourceType: String = "voice",
        sourceRef: String? = null,
        createdAt: Instant = Instant.parse("2026-04-18T05:00:00Z"),
        lastEditedAt: Instant? = null,
        quoteDisputed: Boolean = false,
        quoteDisputedAt: Instant? = null,
        deletedAt: Instant? = null,
        supersedesCommitmentId: String? = null,
    ): CommitmentEntity = CommitmentEntity(
        id = id,
        userId = "user-1",
        itemType = itemType,
        direction = direction,
        scheduleStatus = null,
        decisionStatus = null,
        counterpartyRaw = null,
        counterpartyRef = "lee@corp.com",
        title = "Title",
        description = null,
        quote = quote,
        sourceEventTitle = sourceEventTitle,
        sourceEventOccurredAt = sourceEventOccurredAt,
        dueAt = null,
        dueHint = null,
        dueIsApproximate = false,
        actionState = actionState,
        sourceType = sourceType,
        sourceRef = sourceRef,
        confidence = 0.8,
        commitmentState = CommitmentLifecycleLegacy.DRAFT,
        syncStatus = "synced",
        createdAt = createdAt,
        updatedAt = createdAt,
        lastEditedBy = if (lastEditedAt == null) null else "user-1",
        lastEditedAt = lastEditedAt,
        quoteDisputed = quoteDisputed,
        quoteDisputedAt = quoteDisputedAt,
        deletedAt = deletedAt,
        supersedesCommitmentId = supersedesCommitmentId,
    )

    private fun sourceArtifact(rawEventId: String): SourceArtifactEntity = SourceArtifactEntity(
        id = "artifact-$rawEventId",
        userId = "user-1",
        rawEventId = rawEventId,
        sourceType = SourceType.MEETING,
        sourceRef = "content://meeting/audio",
        artifactType = "markdown_original",
        localPath = "source_archive/user-1/meeting/$rawEventId.md",
        sha256 = "abc",
        byteSize = 32,
        occurredAt = Instant.parse("2026-05-19T01:00:00Z"),
        createdAt = Instant.parse("2026-05-19T01:00:00Z"),
        updatedAt = Instant.parse("2026-05-19T01:00:00Z"),
    )
}
