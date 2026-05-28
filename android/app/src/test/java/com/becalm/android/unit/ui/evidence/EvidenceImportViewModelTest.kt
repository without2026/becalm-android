package com.becalm.android.unit.ui.evidence

import android.net.Uri
import com.becalm.android.R
import com.becalm.android.core.result.BecalmResult
import com.becalm.android.core.result.BecalmError
import com.becalm.android.data.remote.dto.MeetingSpeakerPreviewDto
import com.becalm.android.data.remote.dto.SourceType
import com.becalm.android.data.repository.MeetingImportResult
import com.becalm.android.data.repository.MeetingSpeakerPreviewResult
import com.becalm.android.data.repository.MeetingSpeakerReviewContext
import com.becalm.android.data.repository.SourceImportRepository
import com.becalm.android.ui.components.UiMessage
import com.becalm.android.ui.evidence.EvidenceImportPersistentStatus
import com.becalm.android.ui.evidence.EvidenceImportStatusAction
import com.becalm.android.ui.evidence.EvidenceImportStatusPhase
import com.becalm.android.ui.evidence.EvidenceImportStatusProjectionPort
import com.becalm.android.ui.evidence.EvidenceImportViewModel
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceImportViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val sourceImportRepository: SourceImportRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { sourceImportRepository.observeLatestMeetingSpeakerReview() } returns flowOf(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    // spec: RUX-008
    fun `persistent projection status is restored by a new ViewModel instance`() = runTest {
        val recoveredStatus = EvidenceImportPersistentStatus.PROCESSING
        val first = EvidenceImportViewModel(sourceImportRepository, FakeStatusProjectionPort(recoveredStatus))
        advanceUntilIdle()
        assertEquals(
            UiMessage.resource(R.string.evidence_import_status_processing_title, "1"),
            first.awaitStatusMessage(),
        )

        val restored = EvidenceImportViewModel(sourceImportRepository, FakeStatusProjectionPort(recoveredStatus))
        advanceUntilIdle()
        assertEquals(
            UiMessage.resource(R.string.evidence_import_status_processing_title, "1"),
            restored.awaitStatusMessage(),
        )
    }

    @Test
    fun `processing status exposes modal surface with count and details action`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.processing(processingCount = 2)),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.PROCESSING, surface.phase)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_processing_title, "2"), surface.title)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_processing_body), surface.body)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_action_details), surface.primaryActionLabel)
    }

    @Test
    fun `long running status uses separate copy`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(
                EvidenceImportPersistentStatus.processing(
                    processingCount = 1,
                    oldestStartedAt = Instant.parse("2026-05-26T00:00:00Z"),
                    phase = EvidenceImportStatusPhase.LONG_RUNNING,
                ),
            ),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.LONG_RUNNING, surface.phase)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_long_running_title), surface.title)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_long_running_body), surface.body)
    }

    @Test
    fun `consent required status uses consent copy and settings action`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.consentRequired(consentRequiredCount = 2)),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.CONSENT_REQUIRED, surface.phase)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_consent_required_title, "2"), surface.title)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_consent_required_body), surface.body)
        assertEquals(EvidenceImportStatusAction.CONSENT_SETTINGS, surface.primaryAction)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_action_consent), surface.primaryActionLabel)
    }

    @Test
    fun `review required status overrides processing copy and points to review action`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(
                EvidenceImportPersistentStatus.reviewRequired(reviewRequiredCount = 3, processingCount = 2),
            ),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.REVIEW_REQUIRED, surface.phase)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_review_required_title, "3"), surface.title)
        assertEquals(UiMessage.resource(R.string.evidence_import_review_action), surface.primaryActionLabel)
        assertNull(surface.secondaryAction)
        assertNull(surface.secondaryActionLabel)
    }

    @Test
    fun `meeting only review required status reopens speaker review instead of person matching`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(
                EvidenceImportPersistentStatus.reviewRequired(
                    reviewRequiredCount = 1,
                    meetingReviewRequiredCount = 1,
                    personReviewRequiredCount = 0,
                ),
            ),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.REVIEW_REQUIRED, surface.phase)
        assertEquals(EvidenceImportStatusAction.MEETING_SPEAKER_REVIEW, surface.primaryAction)
        assertEquals(UiMessage.resource(R.string.evidence_import_speaker_review_action), surface.primaryActionLabel)
        assertNull(surface.secondaryAction)
        assertNull(surface.secondaryActionLabel)
    }

    @Test
    fun `meeting review remains primary when person review is also pending`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(
                EvidenceImportPersistentStatus.reviewRequired(
                    reviewRequiredCount = 2,
                    meetingReviewRequiredCount = 1,
                    personReviewRequiredCount = 1,
                ),
            ),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.REVIEW_REQUIRED, surface.phase)
        assertEquals(EvidenceImportStatusAction.MEETING_SPEAKER_REVIEW, surface.primaryAction)
        assertEquals(UiMessage.resource(R.string.evidence_import_speaker_review_action), surface.primaryActionLabel)
        assertEquals(EvidenceImportStatusAction.REVIEW, surface.secondaryAction)
        assertEquals(UiMessage.resource(R.string.evidence_import_review_action), surface.secondaryActionLabel)
    }

    @Test
    fun `failed status offers direct retry instead of dead-end status action`() = runTest {
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.failed(failedCount = 1)),
        )

        advanceUntilIdle()

        val surface = requireNotNull(viewModel.state.value.statusSurface)
        assertEquals(EvidenceImportStatusPhase.FAILED, surface.phase)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_failed_title, "1"), surface.title)
        assertEquals(EvidenceImportStatusAction.RETRY_FAILED, surface.primaryAction)
        assertEquals(UiMessage.resource(R.string.evidence_import_status_action_retry_failed), surface.primaryActionLabel)
    }

    @Test
    fun `retry failed imports delegates to repository and shows started message`() = runTest {
        coEvery { sourceImportRepository.retryFailedEvidenceImports() } returns BecalmResult.Success(1)
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.failed(failedCount = 1)),
        )

        viewModel.onRetryFailedImports()
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceImportRepository.retryFailedEvidenceImports() }
        assertEquals(
            UiMessage.resource(R.string.evidence_import_retry_started, "1"),
            viewModel.state.value.message,
        )
    }

    @Test
    fun `retry failed imports reports unavailable when no retryable row exists`() = runTest {
        coEvery { sourceImportRepository.retryFailedEvidenceImports() } returns
            BecalmResult.Failure(BecalmError.Validation("failed_evidence", "none"))
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.failed(failedCount = 1)),
        )

        viewModel.onRetryFailedImports()
        advanceUntilIdle()

        assertEquals(
            UiMessage.resource(R.string.evidence_import_retry_unavailable),
            viewModel.state.value.message,
        )
    }

    @Test
    // spec: RUX-009
    fun `meeting audio selection stages preview without blocking loading sheet`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { sourceImportRepository.stageMeetingAudioForSpeakerReview(uri) } returns
            BecalmResult.Success(MeetingImportResult("raw-meeting", "content://saved/audio"))

        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )

        viewModel.onMeetingAudioSelected(uri)
        advanceUntilIdle()

        assertNull(viewModel.state.value.loadingMessage)
        assertEquals(
            UiMessage.resource(R.string.evidence_import_meeting_preview_started),
            viewModel.state.value.message,
        )
        coVerify(exactly = 1) { sourceImportRepository.stageMeetingAudioForSpeakerReview(uri) }
    }

    @Test
    fun `meeting preview loading cancel remains a no-op for non blocking pipeline`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery { sourceImportRepository.stageMeetingAudioForSpeakerReview(uri) } returns
            BecalmResult.Success(MeetingImportResult("raw-meeting", "content://saved/audio"))
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )

        viewModel.onMeetingAudioSelected(uri)
        advanceUntilIdle()

        viewModel.onMeetingPreviewLoadingCancelled()
        advanceUntilIdle()

        assertNull(viewModel.state.value.loadingMessage)
        assertNull(viewModel.state.value.meetingReview)
    }

    @Test
    fun `meeting speaker review cancel suppresses the same persistent review for this session`() = runTest {
        every { sourceImportRepository.observeLatestMeetingSpeakerReview() } returns flowOf(
            MeetingSpeakerPreviewResult(
                rawEventId = "raw-preview",
                sourceRef = "content://saved/audio",
                speakerPreviewId = "preview-1",
                speakers = listOf(MeetingSpeakerPreviewDto(speakerId = "SPEAKER_01")),
                billableSeconds = 60,
            ),
        )
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )
        advanceUntilIdle()
        assertNull(viewModel.state.value.meetingReview)

        viewModel.onMeetingSpeakerReviewAction()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.meetingReview)

        viewModel.onMeetingSpeakerReviewCancelled()
        advanceUntilIdle()

        assertNull(viewModel.state.value.meetingReview)
    }

    @Test
    fun `meeting speaker review action restores a dismissed persistent review`() = runTest {
        every { sourceImportRepository.observeLatestMeetingSpeakerReview() } returns flowOf(
            MeetingSpeakerPreviewResult(
                rawEventId = "raw-preview",
                sourceRef = "content://saved/audio",
                speakerPreviewId = "preview-1",
                speakers = listOf(MeetingSpeakerPreviewDto(speakerId = "SPEAKER_01")),
                billableSeconds = 60,
            ),
        )
        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(
                EvidenceImportPersistentStatus.reviewRequired(
                    reviewRequiredCount = 1,
                    meetingReviewRequiredCount = 1,
                    personReviewRequiredCount = 0,
                ),
            ),
        )
        advanceUntilIdle()
        viewModel.onMeetingSpeakerReviewAction()
        advanceUntilIdle()

        viewModel.onMeetingSpeakerReviewCancelled()
        advanceUntilIdle()
        assertNull(viewModel.state.value.meetingReview)

        viewModel.onMeetingSpeakerReviewAction()
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.meetingReview)
    }

    @Test
    // spec: RUX-009
    fun `meeting speaker review confirmation sends escaped mapping json`() = runTest {
        val capturedContext = slot<MeetingSpeakerReviewContext>()
        every { sourceImportRepository.observeLatestMeetingSpeakerReview() } returns flowOf(
            MeetingSpeakerPreviewResult(
                rawEventId = "raw-preview",
                sourceRef = "content://saved/audio",
                speakerPreviewId = "preview-1",
                speakers = listOf(
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_01"),
                    MeetingSpeakerPreviewDto(speakerId = """SPEAKER_"02\민홍"""),
                ),
                billableSeconds = 60,
            ),
        )
        coEvery {
            sourceImportRepository.confirmMeetingSpeakerReview("raw-preview", capture(capturedContext))
        } returns BecalmResult.Success(MeetingImportResult("raw-meeting", "content://saved/audio"))

        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )

        advanceUntilIdle()
        assertNull(viewModel.state.value.meetingReview)

        viewModel.onMeetingSpeakerReviewAction()
        advanceUntilIdle()
        viewModel.onMeetingSelfSpeakerSelected("""SPEAKER_"02\민홍""")
        advanceUntilIdle()

        assertEquals("""SPEAKER_"02\민홍""", viewModel.state.value.meetingReview?.selectedSpeakerId)
        coVerify(exactly = 0) { sourceImportRepository.confirmMeetingSpeakerReview("raw-preview", any()) }

        viewModel.onMeetingSpeakerReviewConfirmed()
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceImportRepository.confirmMeetingSpeakerReview("raw-preview", any()) }
        val rows = parseRows(capturedContext.captured.speakerMappingsJson)
        assertEquals("""SPEAKER_"02\민홍""", rows[1]["speaker_id"])
        assertEquals("self", rows[1]["relation_to_user"])
        assertEquals(true, rows[1]["confirmed_by_user"])
        assertEquals(false, rows[0]["confirmed_by_user"])
    }

    @Test
    fun `call speaker review treats selected speaker as self and confirms counterparty`() = runTest {
        val capturedContext = slot<MeetingSpeakerReviewContext>()
        every { sourceImportRepository.observeLatestMeetingSpeakerReview() } returns flowOf(
            MeetingSpeakerPreviewResult(
                rawEventId = "raw-call-preview",
                sourceRef = "content://saved/call",
                sourceType = SourceType.CALL_RECORDING,
                speakerPreviewId = "preview-call",
                speakers = listOf(
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_01"),
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_02"),
                ),
                billableSeconds = 24,
            ),
        )
        coEvery {
            sourceImportRepository.confirmMeetingSpeakerReview("raw-call-preview", capture(capturedContext))
        } returns BecalmResult.Success(MeetingImportResult("raw-call", "content://saved/call"))

        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )

        advanceUntilIdle()
        assertNull(viewModel.state.value.meetingReview)

        viewModel.onMeetingSpeakerReviewAction()
        advanceUntilIdle()
        viewModel.onMeetingSelfSpeakerSelected("SPEAKER_01")
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.meetingReview)
        coVerify(exactly = 0) { sourceImportRepository.confirmMeetingSpeakerReview("raw-call-preview", any()) }

        viewModel.onMeetingSpeakerReviewConfirmed()
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceImportRepository.confirmMeetingSpeakerReview("raw-call-preview", any()) }
        assertEquals("SPEAKER_01", capturedContext.captured.selfSpeakerId)
        val rows = parseRows(capturedContext.captured.speakerMappingsJson)
        assertEquals("self", rows[0]["relation_to_user"])
        assertEquals("counterparty", rows[1]["relation_to_user"])
        assertEquals(true, rows[0]["confirmed_by_user"])
        assertEquals(true, rows[1]["confirmed_by_user"])
    }

    @Test
    fun `call speaker review with more than two speakers requires explicit counterparty`() = runTest {
        val capturedContext = slot<MeetingSpeakerReviewContext>()
        every { sourceImportRepository.observeLatestMeetingSpeakerReview() } returns flowOf(
            MeetingSpeakerPreviewResult(
                rawEventId = "raw-call-three",
                sourceRef = "content://saved/call-three",
                sourceType = SourceType.CALL_RECORDING,
                speakerPreviewId = "preview-call-three",
                speakers = listOf(
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_01"),
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_02"),
                    MeetingSpeakerPreviewDto(speakerId = "SPEAKER_03"),
                ),
                billableSeconds = 42,
            ),
        )
        coEvery {
            sourceImportRepository.confirmMeetingSpeakerReview("raw-call-three", capture(capturedContext))
        } returns BecalmResult.Success(MeetingImportResult("raw-call-three", "content://saved/call-three"))

        val viewModel = EvidenceImportViewModel(
            sourceImportRepository,
            FakeStatusProjectionPort(EvidenceImportPersistentStatus.NONE),
        )

        advanceUntilIdle()
        viewModel.onMeetingSpeakerReviewAction()
        advanceUntilIdle()
        viewModel.onMeetingSelfSpeakerSelected("SPEAKER_01")
        advanceUntilIdle()
        viewModel.onMeetingSpeakerReviewConfirmed()
        advanceUntilIdle()

        coVerify(exactly = 0) { sourceImportRepository.confirmMeetingSpeakerReview("raw-call-three", any()) }

        viewModel.onMeetingCounterpartySpeakerSelected("SPEAKER_03")
        viewModel.onMeetingSpeakerReviewConfirmed()
        advanceUntilIdle()

        coVerify(exactly = 1) { sourceImportRepository.confirmMeetingSpeakerReview("raw-call-three", any()) }
        assertEquals("SPEAKER_01", capturedContext.captured.selfSpeakerId)
        val rows = parseRows(capturedContext.captured.speakerMappingsJson)
        assertEquals("self", rows[0]["relation_to_user"])
        assertEquals("participant", rows[1]["relation_to_user"])
        assertEquals("counterparty", rows[2]["relation_to_user"])
        assertEquals(false, rows[1]["confirmed_by_user"])
    }

    private suspend fun EvidenceImportViewModel.awaitStatusMessage(): UiMessage =
        requireNotNull(withTimeout(5_000) { state.first { it.statusMessage != null }.statusMessage })

    private fun parseRows(json: String): List<Map<String, Any?>> {
        val listType = Types.newParameterizedType(
            List::class.java,
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
        return requireNotNull(Moshi.Builder().build().adapter<List<Map<String, Any?>>>(listType).fromJson(json))
    }

    private class FakeStatusProjectionPort(
        status: EvidenceImportPersistentStatus,
    ) : EvidenceImportStatusProjectionPort {
        private val statusFlow = MutableStateFlow(status)
        override fun observeStatus(): Flow<EvidenceImportPersistentStatus> = statusFlow
    }
}
